/**
 * ADR-013: the receiver side of the game-audio AAC path — the in-band
 * format handshake (webrtc.md channel sub-protocol v:1), ADTS frame
 * validation, and WebCodecs AAC decode into the same `{left, right}`
 * Float32 pairs the ADR-012 PCM path feeds the worklet with. The codec +
 * framing are imported from TikTok's cast (teardown audio-importable.md):
 * AAC-LC 192 kbps CBR stereo 48 kHz, every frame ADTS-wrapped, no
 * out-of-band csd — the ASC (`0x11 0x90` = LC/48k/stereo) is synthesized
 * from the announced format, exactly as the reference does.
 *
 * Browser APIs live behind the injected `decoderFactory` (default: WebCodecs
 * `AudioDecoder`, probed at runtime), so vitest covers the routing without
 * Chromium. Wire bytes are counted for the stats line — the DataChannel is
 * invisible to getStats, so these counters are the only channel visibility.
 *
 * Logging rules: numbers and formats only — never frame content.
 */

export const GAME_AUDIO_FORMAT_AAC = 'adts-aac-192'
export const GAME_AUDIO_FORMAT_PCM = 'pcm16'

/** The channel sub-protocol version (in-band — not the signaling Envelope's). */
const HANDSHAKE_VERSION = 1

/** AAC-LC / 48 kHz / stereo as two bytes — the ASC the reference synthesizes. */
export const ASC_AAC_LC_48K_STEREO = new Uint8Array([0x11, 0x90])

/** AAC-LC's fixed access-unit size — the receiver's timestamp grid unit. */
export const AAC_SAMPLES_PER_FRAME = 1024
export const AAC_SAMPLE_RATE = 48_000

/** Decoded frames buffered inside the decoder before the receiver drops instead. */
const MAX_DECODE_QUEUE = 30

/** Minimal WebCodecs `AudioDecoder` surface — satisfied by Chromium, faked in tests. */
export interface AudioDecoderLike {
  configure(config: {
    codec: string
    sampleRate: number
    numberOfChannels: number
    description?: Uint8Array
  }): void
  decode(chunk: EncodedAudioChunkLike): void
  flush(): Promise<void>
  close(): void
  readonly decodeQueueSize: number
}

export type AudioDecoderFactory = new (init: {
  output: (audioData: AudioDataLike) => void
  error: (error: DOMException) => void
}) => AudioDecoderLike

/** Minimal WebCodecs `AudioData` surface. */
export interface AudioDataLike {
  readonly numberOfFrames: number
  copyTo(destination: Float32Array, options: { planeIndex: number; format: 'f32-planar' }): void
  close(): void
}

/** Minimal `EncodedAudioChunk` surface (what `decode()` actually consumes). */
export interface EncodedAudioChunkLike {
  readonly type: 'key'
  readonly timestamp: number
  readonly byteLength: number
}

export interface EncodedAudioChunkInit {
  type: 'key'
  timestamp: number
  data: Uint8Array
}

/**
 * `AudioDecoder.decode()` takes a real `EncodedAudioChunk` instance — a
 * plain init object throws `TypeError` in Chromium (found via the Electron
 * decode harness: every frame rejected). Node tests have no WebCodecs, so
 * there a plain {type, timestamp, byteLength} record is passed and the
 * injected fakes consume that.
 */
function makeChunk(init: EncodedAudioChunkInit): EncodedAudioChunkLike {
  const w = globalThis as unknown as {
    EncodedAudioChunk?: new (init: EncodedAudioChunkInit) => EncodedAudioChunkLike
  }
  if (typeof w.EncodedAudioChunk === 'function') return new w.EncodedAudioChunk(init)
  return { type: init.type, timestamp: init.timestamp, byteLength: init.data.byteLength }
}

export interface GameChannelCounters {
  /** Wire bytes received on the channel (both formats). */
  bytesIn: number
  /** ADTS frames accepted for decode. */
  framesIn: number
  /** Messages rejected by the ADTS validation. */
  invalidFrames: number
  /** Frames the decoder produced and handed to the mixer. */
  framesRendered: number
  /** Decoder construction or decode errors. */
  decodeErrors: number
  /** Current wire format. */
  mode: 'pcm' | 'aac'
}

export interface GameAacChannelOptions {
  /** The mixer's ADR-012 PCM push — unchanged semantics. */
  pushPcm(chunk: ArrayBuffer): void
  /** Decoded frames, the worklet's message shape. */
  pushFrames(left: Float32Array, right: Float32Array): void
  /** Tests inject; production defaults to a WebCodecs probe. */
  createDecoder?: AudioDecoderFactory
  log?(level: 'debug' | 'info' | 'warn', message: string, details?: Record<string, unknown>): void
}

export interface GameAacChannel {
  /** A text message arrived — returns the ack text to send, or null. */
  onAnnounce(text: string): string | null
  /** A binary message arrived — routed per the current mode. */
  onBinary(data: ArrayBuffer): void
  readonly counters: GameChannelCounters
  /** Releases the decoder (channel closed). */
  detach(): void
}

/** One ADTS packet's payload: the raw AAC frame after its 7-byte header. */
interface AdtsFrame {
  payload: Uint8Array
}

/**
 * Validates one DataChannel message as a single ADTS packet (the mobile
 * sends exactly one frame per message — SCTP preserves message boundaries).
 * Returns null when the syncword, layer/protection bits, or the header's
 * own length field disagree with the message — that frame is dropped and
 * the next message re-syncs from its header (why TikTok frames in ADTS).
 */
export function parseAdtsFrame(data: ArrayBuffer): AdtsFrame | null {
  if (data.byteLength < 8) return null
  const bytes = new Uint8Array(data)
  if (bytes[0] !== 0xff) return null
  // MPEG-4, layer 0, protection_absent=1 — the exact header the mobile wraps.
  if ((bytes[1] & 0xf6) !== 0xf0) return null
  const length = ((bytes[3] & 0x03) << 11) | (bytes[4] << 3) | (bytes[5] >> 5)
  if (length < 8 || length > bytes.byteLength) return null
  return { payload: bytes.subarray(7, length) }
}

/** Decoded AudioData → the worklet's planar pair. */
function audioDataToFrames(audioData: AudioDataLike): { left: Float32Array; right: Float32Array } | null {
  try {
    const left = new Float32Array(audioData.numberOfFrames)
    const right = new Float32Array(audioData.numberOfFrames)
    audioData.copyTo(left, { planeIndex: 0, format: 'f32-planar' })
    audioData.copyTo(right, { planeIndex: 1, format: 'f32-planar' })
    return { left, right }
  } catch {
    return null
  }
}

/** The WebCodecs probe — production's decoder factory. */
function defaultDecoderFactory(): AudioDecoderFactory | null {
  const w = globalThis as unknown as { AudioDecoder?: AudioDecoderFactory }
  return typeof w.AudioDecoder === 'function' ? w.AudioDecoder : null
}

/**
 * The channel's receiver state machine. Opens in `pcm` (ADR-012
 * byte-identical) — a capable desktop acks the AAC announce and switches;
 * a `pcm16` announce (encoder failure on the mobile) switches back.
 */
export function createGameAacChannel(options: GameAacChannelOptions): GameAacChannel {
  const log = options.log ?? (() => {})
  let decoder: AudioDecoderLike | null = null
  let frameIndex = 0
  const counters: GameChannelCounters = {
    bytesIn: 0,
    framesIn: 0,
    invalidFrames: 0,
    framesRendered: 0,
    decodeErrors: 0,
    mode: 'pcm'
  }

  function buildDecoder(): AudioDecoderLike | null {
    const factory = options.createDecoder ?? defaultDecoderFactory()
    if (factory === null) return null
    try {
      const instance = new factory({
        output: (audioData) => {
          const frames = audioDataToFrames(audioData)
          audioData.close()
          if (frames !== null) {
            counters.framesRendered++
            options.pushFrames(frames.left, frames.right)
          }
        },
        error: (error) => {
          counters.decodeErrors++
          log('warn', 'game audio AAC decoder failed — frames resync at the next ADTS header', {
            error: String(error)
          })
        }
      })
      instance.configure({
        codec: 'mp4a.40.2',
        sampleRate: AAC_SAMPLE_RATE,
        numberOfChannels: 2,
        description: ASC_AAC_LC_48K_STEREO
      })
      return instance
    } catch (error) {
      counters.decodeErrors++
      log('warn', 'game audio AAC decoder unavailable — staying on PCM', { error: String(error) })
      return null
    }
  }

  return {
    onAnnounce(text: string): string | null {
      let parsed: unknown
      try {
        parsed = JSON.parse(text)
      } catch {
        return null
      }
      const obj = parsed as { v?: number; type?: string; format?: string }
      if (obj.v !== HANDSHAKE_VERSION || obj.type !== 'announce') return null
      if (obj.format === GAME_AUDIO_FORMAT_AAC) {
        if (counters.mode === 'aac') return null // already switched — idempotent re-announce
        const instance = buildDecoder()
        if (instance === null) return null // no WebCodecs — stay on PCM, never ack
        decoder = instance
        counters.mode = 'aac'
        log('info', 'game audio switched to the AAC path (adts-aac-192)')
        return JSON.stringify({ v: HANDSHAKE_VERSION, type: 'ack', format: GAME_AUDIO_FORMAT_AAC })
      }
      if (obj.format === GAME_AUDIO_FORMAT_PCM) {
        if (counters.mode !== 'pcm') {
          counters.mode = 'pcm'
          log('info', 'game audio switched back to the raw PCM path (pcm16 announce)')
        }
        return null
      }
      return null
    },

    onBinary(data: ArrayBuffer): void {
      counters.bytesIn += data.byteLength
      if (counters.mode === 'pcm') {
        options.pushPcm(data)
        return
      }
      const frame = parseAdtsFrame(data)
      if (frame === null || decoder === null) {
        counters.invalidFrames++
        return
      }
      if (decoder.decodeQueueSize > MAX_DECODE_QUEUE) {
        counters.invalidFrames++
        return // the renderer is starved — drop, never build an unbounded backlog
      }
      // Receiver-side sample clock (the teardown's getJitterFreePTS insight):
      // timestamps from counted frames, not arrival — loss cannot shift the grid.
      const timestampUs = Math.round((frameIndex * AAC_SAMPLES_PER_FRAME * 1_000_000) / AAC_SAMPLE_RATE)
      frameIndex++
      counters.framesIn++
      try {
        decoder.decode(makeChunk({ type: 'key', timestamp: timestampUs, data: frame.payload }))
      } catch (error) {
        counters.decodeErrors++
        log('warn', 'game audio AAC decode rejected a frame', { error: String(error) })
      }
    },

    counters,

    detach(): void {
      if (decoder !== null) {
        try {
          decoder.close()
        } catch {
          // A failed decoder may already be closed.
        }
        decoder = null
      }
    }
  }
}
