/**
 * ADR-013: the `game-audio` DataChannel's desktop side. The channel payload
 * v1 (webrtc.md) is one text announce followed by one Opus packet per binary
 * message; the legacy payload (ADR-012, older mobile builds) is raw Int16LE
 * PCM with no announce. Because the channel is `ordered=false`, an announce
 * may trail the first packets — the stream is therefore **classified by
 * message type**: binary messages are buffered until the announce arrives (or
 * the burst forces the legacy verdict), then flushed down the classified path.
 *
 * Opus decode uses WebCodecs `AudioDecoder`; the config carries a synthesized
 * 19-byte OpusHead (explicit beats implicit — Chromium's decoder wants the
 * extradata, and the pre-skip then comes from the announce, not defaults).
 * Decoded planes feed the mixer's worklet ring — the audible path (gain,
 * mute, cable routing, probes, the 3 s quiet fallback) is untouched.
 *
 * All WebCodecs surfaces are injected: production wires Chromium's globals,
 * tests fake them. Numbers only in the logs — never the samples.
 */

import type { AudioMixer, MixerLogger } from './mixer'

/** webrtc.md channel payload v1. */
export interface GameAudioAnnounce {
  v: number
  codec: string
  sampleRate: number
  channels: number
  preskip: number
}

/** The values this receiver accepts — anything else refuses the channel. */
export const ANNOUNCE_VERSION = 1
export const ANNOUNCE_CODEC = 'opus'
export const ANNOUNCE_SAMPLE_RATE = 48_000
export const ANNOUNCE_CHANNELS = 2

/** magic 8 + version 1 + channels 1 + preskip 2 + rate 4 + gain 2 + mapping 1. */
export const OPUS_HEAD_SIZE = 19

/**
 * Strict parse — null unless it is exactly the v1 opus/48000/2 announce with
 * an in-range pre-skip (the mobile's GameAudioOpusFormatTest pins the same
 * contract on the sender side).
 */
export function parseAnnounce(json: string): GameAudioAnnounce | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(json)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const a = parsed as Record<string, unknown>
  if (a.v !== ANNOUNCE_VERSION) return null
  if (a.codec !== ANNOUNCE_CODEC) return null
  if (a.sampleRate !== ANNOUNCE_SAMPLE_RATE) return null
  if (a.channels !== ANNOUNCE_CHANNELS) return null
  const preskip = a.preskip
  if (typeof preskip !== 'number' || !Number.isInteger(preskip)) return null
  if (preskip < 0 || preskip > ANNOUNCE_SAMPLE_RATE) return null
  return { v: ANNOUNCE_VERSION, codec: ANNOUNCE_CODEC, sampleRate: ANNOUNCE_SAMPLE_RATE, channels: ANNOUNCE_CHANNELS, preskip }
}

/**
 * The OpusHead the WebCodecs config needs (RFC 7845 §3.1): pre-skip is
 * 16-bit **little-endian** (verified against an ffmpeg-produced file during
 * the #104 live debug — an ffmpeg head carries `38 01` for 312), the sample
 * rate 32-bit little-endian, output gain 0, mapping family 0.
 */
export function opusHead(preskip: number): Uint8Array {
  const head = new Uint8Array(OPUS_HEAD_SIZE)
  const magic = 'OpusHead'
  for (let i = 0; i < magic.length; i++) head[i] = magic.charCodeAt(i)
  head[8] = 1 // version
  head[9] = ANNOUNCE_CHANNELS
  head[10] = preskip & 0xff
  head[11] = (preskip >>> 8) & 0xff
  head[12] = ANNOUNCE_SAMPLE_RATE & 0xff
  head[13] = (ANNOUNCE_SAMPLE_RATE >>> 8) & 0xff
  head[14] = (ANNOUNCE_SAMPLE_RATE >>> 16) & 0xff
  head[15] = (ANNOUNCE_SAMPLE_RATE >>> 24) & 0xff
  // gain 0 (16..17), mapping family 0 (18) stay zeroed
  return head
}

/** Minimal WebCodecs `AudioData` surface — only what the planes copy needs. */
export interface AudioDataLike {
  readonly numberOfFrames: number
  readonly numberOfChannels: number
  copyTo(destination: Float32Array, options: { planeIndex: number }): void
  close(): void
}

export interface AudioDecoderConfigLike {
  codec: string
  sampleRate: number
  numberOfChannels: number
  description?: Uint8Array
}

export interface AudioDecoderLike {
  decode(chunk: { type: 'key'; timestamp: number; data: Uint8Array }): void
  close(): void
}

/** The injected surface over Chromium's `AudioDecoder` + `EncodedAudioChunk`. */
export interface WebCodecsSurface {
  /** False when this Chromium has no WebCodecs audio at all. */
  available(): boolean
  /** Resolves false when the config itself is unsupported. */
  isConfigSupported(config: AudioDecoderConfigLike): Promise<boolean>
  createDecoder(config: AudioDecoderConfigLike, hooks: {
    output: (data: AudioDataLike) => void
    error: (error: Error) => void
  }): AudioDecoderLike
}

/** Production surface: Chromium's globals, null-shaped when they are absent. */
export function chromiumWebCodecs(): WebCodecsSurface | null {
  const decoderCtor = (globalThis as Record<string, unknown>).AudioDecoder as
    | (new (init: {
        output: (data: AudioDataLike) => void
        error: (error: { message?: string }) => void
      }) => {
        configure(config: AudioDecoderConfigLike): void
        decode(chunk: { type: 'key'; timestamp: number; data: Uint8Array }): void
        close(): void
      }) & { isConfigSupported(config: AudioDecoderConfigLike): Promise<{ supported: boolean }> }
    | undefined
  const chunkCtor = (globalThis as Record<string, unknown>).EncodedAudioChunk as
    | (new (init: { type: 'key'; timestamp: number; data: Uint8Array }) => { type: 'key'; timestamp: number; data: Uint8Array })
    | undefined
  if (decoderCtor === undefined || chunkCtor === undefined) return null
  return {
    available: () => true,
    isConfigSupported: (config) =>
      decoderCtor.isConfigSupported(config).then((result) => result.supported === true),
    createDecoder: (config, hooks) => {
      // The WebCodecs constructor carries the callbacks — `new AudioDecoder()`
      // without the init object throws (found live in the #104 debug: the
      // throw escaped startOpus, the mode hung in unclassified, and the
      // stream misclassified as PCM — the "electrical buzz").
      const decoder = new decoderCtor({
        output: hooks.output,
        error: (error) => hooks.error(new Error(error?.message ?? 'AudioDecoder error'))
      })
      decoder.configure(config)
      return {
        decode: (chunk) => decoder.decode(new chunkCtor(chunk)),
        close: () => decoder.close()
      }
    }
  }
}

export interface GameAudioChannelOptions {
  mixer: AudioMixer
  log: MixerLogger
  /** Production defaults to Chromium's WebCodecs; tests fake it. */
  webCodecs?: WebCodecsSurface
}

export interface GameAudioFeed {
  /** One DataChannel message — text (announce) or ArrayBuffer (media). */
  onMessage(data: unknown): void
  /** Frees the decoder; the mixer's fallback timer handles the audible side. */
  detach(): void
}

/** Binary messages buffered before the stream classifies — a few at most. */
const CLASSIFICATION_WINDOW_MESSAGES = 8

/** Opus access-unit duration at the encoder's 20 ms framing (microseconds). */
const PACKET_DURATION_US = 20_000

/** The bandwidth evidence line's window (issue #104 acceptance). */
const BANDWIDTH_LOG_INTERVAL_MS = 2_000

export async function attachGameAudioChannel(options: GameAudioChannelOptions): Promise<GameAudioFeed | null> {
  const { mixer, log } = options
  const pcmFeed = await mixer.attachGamePcmSource()
  if (pcmFeed === null) return null
  const webCodecs = options.webCodecs ?? chromiumWebCodecs()

  let mode: 'unclassified' | 'opus' | 'pcm' | 'refused' = 'unclassified'
  const pendingBinary: ArrayBuffer[] = []
  let decoder: AudioDecoderLike | null = null
  let nextTimestampUs = 0
  let windowBytes = 0

  const bandwidthTimer = setInterval(() => {
    log('info', 'game-audio channel bandwidth', { kbps: Math.round((windowBytes * 8) / (BANDWIDTH_LOG_INTERVAL_MS / 1_000) / 1_000), mode })
    windowBytes = 0
  }, BANDWIDTH_LOG_INTERVAL_MS)

  const refuse = (reason: string): void => {
    log('warn', `game-audio channel refused (${reason}) — track fallback stands`)
    mode = 'refused'
  }

  const startOpus = async (announce: GameAudioAnnounce): Promise<void> => {
    if (webCodecs === null || !webCodecs.available()) {
      refuse('WebCodecs AudioDecoder missing')
      return
    }
    const config: AudioDecoderConfigLike = {
      codec: 'opus',
      sampleRate: ANNOUNCE_SAMPLE_RATE,
      numberOfChannels: ANNOUNCE_CHANNELS,
      description: opusHead(announce.preskip)
    }
    let supported = false
    try {
      supported = await webCodecs.isConfigSupported(config)
    } catch (error) {
      log('warn', 'game audio decoder support check failed', { error: String(error) })
    }
    if (!supported) {
      refuse('opus decoder config unsupported')
      return
    }
    try {
      decoder = webCodecs.createDecoder(config, {
      output: (data) => {
        try {
          if (data.numberOfFrames === 0) return
          const left = new Float32Array(data.numberOfFrames)
          const right = new Float32Array(data.numberOfFrames)
          data.copyTo(left, { planeIndex: 0 })
          if (data.numberOfChannels > 1) data.copyTo(right, { planeIndex: 1 })
          else right.set(left)
          pcmFeed.pushFrames(left, right)
        } finally {
          data.close()
        }
      },
      error: (error) => {
        // Stop feeding; the mixer's quiet timer hands the path to the track.
        log('warn', 'game audio decoder failed — awaiting the track fallback', { error: String(error) })
        mode = 'refused'
        try {
          decoder?.close()
        } catch {
          // A decoder that errored may already be closed.
        }
        decoder = null
      }
      })
    } catch (error) {
      refuse('decoder construction failed: ' + String(error))
      return
    }
    mode = 'opus'
    log('info', 'game audio switched to the opus DataChannel payload', {
      preskip: announce.preskip,
      codec: 'opus/48000/2'
    })
    for (const chunk of pendingBinary.splice(0)) feedBinary(chunk)
  }

  const feedBinary = (chunk: ArrayBuffer): void => {
    if (mode === 'pcm') {
      pcmFeed.push(chunk)
      return
    }
    if (mode === 'opus') {
      const current = decoder
      if (current === null) return
      try {
        current.decode({ type: 'key', timestamp: nextTimestampUs, data: new Uint8Array(chunk) })
        nextTimestampUs += PACKET_DURATION_US
      } catch (error) {
        log('warn', 'game audio decode submission failed', { error: String(error) })
      }
    }
  }

  return {
    onMessage: (data) => {
      if (mode === 'refused') return
      if (typeof data === 'string') {
        const announce = parseAnnounce(data)
        if (announce === null) {
          // Garbage text on a classified stream is noise — only an
          // unclassified stream refuses on it.
          if (mode === 'unclassified') refuse('malformed announce')
          return
        }
        if (mode === 'opus') return // one announce per channel; a second is noise
        if (mode === 'pcm') {
          // ordered=false let the announce trail the burst: the early
          // packets flushed as PCM (a brief buzz) — reclassify; from here
          // on packets decode as Opus.
          log('warn', 'game audio announce trailed the pcm classification — reclassifying to opus')
        }
        void startOpus(announce).catch((error) => {
          // Fail closed — never leave the stream in limbo (the #104 buzz
          // was exactly an escaped setup exception).
          refuse('decoder setup failed: ' + String(error))
        })
        return
      }
      if (!(data instanceof ArrayBuffer)) return
      windowBytes += data.byteLength
      if (mode === 'unclassified') {
        pendingBinary.push(data)
        // A burst with no announce is a legacy PCM sender — classify as PCM.
        if (pendingBinary.length >= CLASSIFICATION_WINDOW_MESSAGES) {
          mode = 'pcm'
          for (const chunk of pendingBinary.splice(0)) pcmFeed.push(chunk)
        }
        return
      }
      feedBinary(data)
    },
    detach: () => {
      clearInterval(bandwidthTimer)
      if (decoder !== null) {
        try {
          decoder.close()
        } catch {
          // Closing twice is harmless — the state may already be closed.
        }
        decoder = null
      }
      pcmFeed.detach()
    }
  }
}

/**
 * The DataChannel fires messages the moment it opens; the mixer's worklet
 * module load (attach) is asynchronous. The announce MUST NOT be swallowed
 * by that window — a lost announce makes the stream classify as legacy PCM
 * (opus bytes decoded as Int16 = the #104 "electrical buzz", found live).
 * The page wires `onmessage` synchronously into this buffer, then drains
 * once the feed exists.
 */
export interface MessageBuffer {
  push(data: unknown): void
  drainInto(feed: GameAudioFeed): void
}

const MESSAGE_BUFFER_CAPACITY = 64

export function createMessageBuffer(): MessageBuffer {
  const pending: unknown[] = []
  return {
    push(data: unknown): void {
      if (pending.length < MESSAGE_BUFFER_CAPACITY) pending.push(data)
    },
    drainInto(feed: GameAudioFeed): void {
      for (const data of pending.splice(0)) feed.onMessage(data)
    }
  }
}
