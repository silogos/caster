import { describe, expect, it, vi } from 'vitest'
import {
  ANNOUNCE_SAMPLE_RATE,
  OPUS_HEAD_SIZE,
  attachGameAudioChannel,
  createMessageBuffer,
  opusHead,
  parseAnnounce,
  type AudioDataLike,
  type AudioDecoderConfigLike,
  type AudioDecoderLike,
  type WebCodecsSurface
} from './game-audio-channel'
import {
  AudioMixer,
  type AudioContextLike,
  type AudioWorkletLike,
  type AudioWorkletNodeLike,
  type GainNodeLike,
  type MediaStreamAudioSourceNodeLike,
  type MediaStreamAudioDestinationNodeLike,
  type MixerLogger
} from './mixer'

/**
 * ADR-013's channel payload v1 receiver: message-type classification
 * (announce → Opus decoder; binary-first → legacy PCM), WebCodecs decode,
 * planes into the mixer's worklet ring — with a real AudioMixer behind a
 * fake Web Audio surface (the mixer.test.ts pattern) and a fake WebCodecs
 * surface. The fallback-to-track behavior itself lives in mixer.test.ts.
 */

class FakeGain implements GainNodeLike {
  gain = { value: Number.NaN }
  connectedTo: unknown[] = []
  disconnectCount = 0
  connect(node: unknown): unknown {
    this.connectedTo.push(node)
    return node
  }
  disconnect(): void {
    this.disconnectCount += 1
    this.connectedTo = []
  }
}

class FakeSource implements MediaStreamAudioSourceNodeLike {
  connectedTo: unknown[] = []
  disconnectCount = 0
  constructor(readonly stream: unknown) {}
  connect(node: unknown): unknown {
    this.connectedTo.push(node)
    return node
  }
  disconnect(): void {
    this.disconnectCount += 1
  }
}

class FakeContext implements AudioContextLike {
  readonly destination = { name: 'destination' }
  gains: FakeGain[] = []
  sources: FakeSource[] = []
  resume = vi.fn((): Promise<void> => Promise.resolve())
  state = 'running'
  audioWorklet: AudioWorkletLike | undefined = undefined

  createGain(): FakeGain {
    const gain = new FakeGain()
    this.gains.push(gain)
    return gain
  }
  createMediaStreamSource(stream: unknown): FakeSource {
    const source = new FakeSource(stream)
    this.sources.push(source)
    return source
  }
  createMediaStreamDestination(): MediaStreamAudioDestinationNodeLike {
    return { stream: { id: 'send-tap' } }
  }
  enableWorkletHost(): FakeWorkletHost {
    const host = new FakeWorkletHost()
    this.audioWorklet = host
    return host
  }
}

class FakeWorkletNode implements AudioWorkletNodeLike {
  port = { postMessage: vi.fn() }
  connectedTo: unknown[] = []
  disconnectCount = 0
  channelCount = 2
  connect(node: unknown): unknown {
    this.connectedTo.push(node)
    return node
  }
  disconnect(): void {
    this.disconnectCount += 1
  }
}

class FakeWorkletHost implements AudioWorkletLike {
  readonly node = new FakeWorkletNode()
  addedModules: string[] = []
  addModule(url: string): Promise<void> {
    this.addedModules.push(url)
    return Promise.resolve()
  }
}

interface DecodedAudio {
  frames: number
  left: number
  right: number
  closed: boolean
}

class FakeWebCodecs implements WebCodecsSurface {
  availability = true
  configSupported = true
  supportCheckThrows = false
  decodeThrows = false
  constructThrows: Error | null = null
  lastConfig: AudioDecoderConfigLike | null = null
  decodeCalls: Array<{ type: 'key'; timestamp: number; data: Uint8Array }> = []
  decoderClosed = false
  errorHook: ((error: Error) => void) | null = null
  outputHook: ((data: AudioDataLike) => void) | null = null
  decoded: DecodedAudio[] = []

  available(): boolean {
    return this.availability
  }

  isConfigSupported(config: AudioDecoderConfigLike): Promise<boolean> {
    if (this.supportCheckThrows) return Promise.reject(new Error('support check exploded'))
    this.lastConfig = config
    return Promise.resolve(this.configSupported)
  }

  createDecoder(
    config: AudioDecoderConfigLike,
    hooks: { output: (data: AudioDataLike) => void; error: (error: Error) => void }
  ): AudioDecoderLike {
    if (this.constructThrows !== null) throw this.constructThrows
    this.lastConfig = config
    this.outputHook = hooks.output
    this.errorHook = hooks.error
    return {
      decode: (chunk) => {
        if (this.decodeThrows) throw new Error('decode exploded')
        this.decodeCalls.push(chunk)
      },
      close: () => {
        this.decoderClosed = true
      }
    }
  }

  /** Test-side decode completion: emit an f32-planar stereo AudioData. */
  emit(frames: number, left: number, right: number): void {
    const audio: DecodedAudio = { frames, left, right, closed: false }
    this.decoded.push(audio)
    this.outputHook?.({
      numberOfFrames: frames,
      numberOfChannels: 2,
      copyTo: (destination, options) => destination.fill(options.planeIndex === 0 ? left : right),
      close: () => {
        audio.closed = true
      }
    })
  }
}

const makeRig = (options: { webCodecs?: FakeWebCodecs } = {}) => {
  const context = new FakeContext()
  const host = context.enableWorkletHost()
  const log = vi.fn() as unknown as MixerLogger & ReturnType<typeof vi.fn>
  const mixer = new AudioMixer({
    createAudioContext: () => context,
    createWorkletNode: () => host.node,
    log
  })
  const webCodecs = options.webCodecs ?? new FakeWebCodecs()
  return { context, host, log, mixer, webCodecs }
}

const attach = async (rig: ReturnType<typeof makeRig>) => {
  const feed = await attachGameAudioChannel({ mixer: rig.mixer, log: rig.log, webCodecs: rig.webCodecs })
  expect(feed).not.toBeNull()
  return feed!
}

const flushDecoderStart = async (): Promise<void> => {
  await Promise.resolve()
  await Promise.resolve()
}

/** Two stereo Int16LE frames: 0.5/−0.5 then 0.25/−0.25 after the mixer's conversion. */
const pcmChunk = (): ArrayBuffer => {
  const view = new DataView(new ArrayBuffer(8))
  view.setInt16(0, 16384, true)
  view.setInt16(2, -16384, true)
  view.setInt16(4, 8192, true)
  view.setInt16(6, -8192, true)
  return view.buffer
}

const posted = (rig: ReturnType<typeof makeRig>): Array<{ left: number[]; right: number[] }> =>
  rig.host.node.port.postMessage.mock.calls.map(
    (call) => call[0] as { left: Float32Array; right: Float32Array }
  ).map((message) => ({ left: Array.from(message.left), right: Array.from(message.right) }))

describe('channel payload v1 pieces (ADR-013)', () => {
  it('opusHead is the exact 19-byte RFC 7845 header (preskip LE — as ffmpeg writes it — rate LE, gain 0, mapping 0)', () => {
    const head = opusHead(312)
    expect(head.byteLength).toBe(OPUS_HEAD_SIZE)
    expect(Array.from(head.subarray(0, 8))).toEqual(Array.from('OpusHead').map((c) => c.charCodeAt(0)))
    expect(head[8]).toBe(1) // version
    expect(head[9]).toBe(2) // channels
    // Preskip is 16-bit LITTLE-endian (RFC 7845 §3.1; ffmpeg's head carries
    // `38 01` for 312 — found in the #104 live debug, BE was a misread).
    expect(head[10]).toBe(312 & 0xff)
    expect(head[11]).toBe(312 >>> 8)
    const rate = head[12] | (head[13] << 8) | (head[14] << 16) | (head[15] << 24)
    expect(rate).toBe(ANNOUNCE_SAMPLE_RATE)
    expect(head[16]).toBe(0) // output gain
    expect(head[17]).toBe(0)
    expect(head[18]).toBe(0) // mapping family
  })

  it('parseAnnounce accepts the v1 opus announce and rejects everything else', () => {
    expect(parseAnnounce('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')).toEqual({
      v: 1,
      codec: 'opus',
      sampleRate: 48000,
      channels: 2,
      preskip: 312
    })
    expect(parseAnnounce('{"v":2,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')).toBeNull()
    expect(parseAnnounce('{"v":1,"codec":"aac","sampleRate":48000,"channels":2,"preskip":312}')).toBeNull()
    expect(parseAnnounce('{"v":1,"codec":"opus","sampleRate":44100,"channels":2,"preskip":312}')).toBeNull()
    expect(parseAnnounce('{"v":1,"codec":"opus","sampleRate":48000,"channels":1,"preskip":312}')).toBeNull()
    expect(parseAnnounce('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":-5}')).toBeNull()
    expect(parseAnnounce('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":1.5}')).toBeNull()
    expect(parseAnnounce('not json')).toBeNull()
  })
})

describe('game-audio channel classification (ADR-013)', () => {
  it('binary-first classifies legacy PCM after the window and flushes in order', async () => {
    const rig = makeRig()
    const feed = await attach(rig)

    for (let i = 0; i < 8; i++) feed.onMessage(pcmChunk())
    // The whole window flushed through the legacy converter, in order.
    expect(posted(rig)).toHaveLength(8)
    expect(posted(rig)[0]).toEqual({ left: [0.5, 0.25], right: [-0.5, -0.25] })
    expect(rig.webCodecs.lastConfig).toBeNull()
  })

  it('an announce configures the opus decoder and buffered packets decode in order', async () => {
    const rig = makeRig()
    const feed = await attach(rig)

    feed.onMessage(pcmChunk()) // races the announce (ordered=false) — buffered
    feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()
    expect(rig.webCodecs.lastConfig).not.toBeNull()
    expect(rig.webCodecs.lastConfig?.codec).toBe('opus')
    expect(rig.webCodecs.lastConfig?.numberOfChannels).toBe(2)
    expect(Array.from(rig.webCodecs.lastConfig!.description!)).toEqual(Array.from(opusHead(312)))

    feed.onMessage(new ArrayBuffer(7).slice(0)) // post-announce packets decode
    feed.onMessage(new ArrayBuffer(11).slice(0))
    expect(rig.webCodecs.decodeCalls).toHaveLength(3) // buffered one + these two
    expect(rig.webCodecs.decodeCalls.map((call) => call.timestamp)).toEqual([0, 20_000, 40_000])
    expect(posted(rig)).toHaveLength(0) // nothing decoded yet — no audible output
  })

  it('decoded planes land in the worklet ring and the AudioData closes', async () => {
    const rig = makeRig()
    const feed = await attach(rig)
    feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()

    rig.webCodecs.emit(960, 0.25, -0.75)
    expect(posted(rig)).toEqual([{ left: Array(960).fill(0.25), right: Array(960).fill(-0.75) }])
    expect(rig.webCodecs.decoded[0].closed).toBe(true)
  })

  it('mono-shaped decode output duplicates the plane instead of posting silence', async () => {
    const rig = makeRig()
    const feed = await attach(rig)
    feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()

    rig.webCodecs.outputHook?.({
      numberOfFrames: 2,
      numberOfChannels: 1,
      copyTo: (destination, options) => destination.fill(options.planeIndex === 0 ? 0.5 : 0),
      close: () => undefined
    })
    expect(posted(rig)).toEqual([{ left: [0.5, 0.5], right: [0.5, 0.5] }])
  })

  it('a malformed announce refuses the channel — the track fallback stands', async () => {
    const rig = makeRig()
    const feed = await attach(rig)
    feed.onMessage('{"v":1,"codec":"aac"}')
    await flushDecoderStart()

    expect(rig.log).toHaveBeenCalledWith('warn', 'game-audio channel refused (malformed announce) — track fallback stands')
    feed.onMessage(pcmChunk())
    expect(posted(rig)).toHaveLength(0) // refused mode feeds nothing
    expect(rig.webCodecs.lastConfig).toBeNull()
  })

  it('no WebCodecs in this Chromium refuses the opus mode', async () => {
    const rig = makeRig()
    rig.webCodecs.availability = false
    const feed = await attach(rig)
    feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()

    expect(rig.log).toHaveBeenCalledWith('warn', 'game-audio channel refused (WebCodecs AudioDecoder missing) — track fallback stands')
    expect(rig.webCodecs.lastConfig).toBeNull()
  })

  it('an unsupported decoder config refuses the opus mode', async () => {
    const rig = makeRig()
    rig.webCodecs.configSupported = false
    const feed = await attach(rig)
    feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()

    expect(rig.log).toHaveBeenCalledWith('warn', 'game-audio channel refused (opus decoder config unsupported) — track fallback stands')
  })

  it('a decoder failure closes the decoder and stops feeding', async () => {
    const rig = makeRig()
    const feed = await attach(rig)
    feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()

    rig.webCodecs.errorHook?.(new Error('chunk 42 exploded'))
    expect(rig.webCodecs.decoderClosed).toBe(true)
    feed.onMessage(new ArrayBuffer(4).slice(0))
    expect(rig.webCodecs.decodeCalls).toHaveLength(0)
  })

  it('a synchronous decode throw does not kill the feed', async () => {
    const rig = makeRig()
    const feed = await attach(rig)
    feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()

    rig.webCodecs.decodeThrows = true
    expect(() => feed.onMessage(new ArrayBuffer(4).slice(0))).not.toThrow()
    expect(rig.log).toHaveBeenCalledWith('warn', 'game audio decode submission failed', expect.anything())
  })

  it('a decoder construction throw refuses the channel instead of hanging unclassified (the #104 buzz)', async () => {
    const rig = makeRig()
    // Chromium throws when AudioDecoder is constructed without its init
    // object — the old code's exact failure: the escape left the stream in
    // 'unclassified' limbo, later misclassified as PCM (electrical buzz).
    rig.webCodecs.constructThrows = new Error("Failed to construct 'AudioDecoder': 1 argument required")
    const feed = await attach(rig)
    feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()

    expect(rig.log).toHaveBeenCalledWith('warn', 'game-audio channel refused (decoder construction failed: Error: Failed to construct \'AudioDecoder\': 1 argument required) — track fallback stands')
    feed.onMessage(new ArrayBuffer(4).slice(0))
    expect(posted(rig)).toHaveLength(0)
  })

  it('an announce trailing the burst reclassifies pcm to opus instead of staying legacy', async () => {
    const rig = makeRig()
    const feed = await attach(rig)

    // 8 binaries classify as legacy PCM; the (unordered) announce lands after.
    for (let i = 0; i < 8; i++) feed.onMessage(pcmChunk())
    feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()

    expect(rig.webCodecs.lastConfig).not.toBeNull()
    feed.onMessage(new ArrayBuffer(5).slice(0))
    feed.onMessage(new ArrayBuffer(9).slice(0))
    expect(rig.webCodecs.decodeCalls).toHaveLength(2) // opus mode, not more PCM
  })

  it('detach closes the decoder and the fallback timer', async () => {
    vi.useFakeTimers()
    try {
      const rig = makeRig()
      const feed = await attach(rig)
      feed.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
      await flushDecoderStart()

      feed.detach()
      expect(rig.webCodecs.decoderClosed).toBe(true)
      // No bandwidth line after detach — the 2 s interval is gone.
      const loggedBandwidth = rig.log.mock.calls.filter((call) => call[1] === 'game-audio channel bandwidth').length
      vi.advanceTimersByTime(6_000)
      expect(rig.log.mock.calls.filter((call) => call[1] === 'game-audio channel bandwidth').length).toBe(loggedBandwidth)
    } finally {
      vi.useRealTimers()
    }
  })

  it('the bandwidth line reports bytes seen and the current mode', async () => {
    vi.useFakeTimers()
    try {
      const rig = makeRig()
      const feed = await attach(rig)
      feed.onMessage(pcmChunk())
      feed.onMessage(pcmChunk())
      vi.advanceTimersByTime(2_000)

      expect(rig.log).toHaveBeenCalledWith('info', 'game-audio channel bandwidth', { kbps: expect.any(Number), mode: 'unclassified' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('a fresh channel replaces the previous feeder (renegotiation)', async () => {
    const rig = makeRig()
    const first = await attach(rig)
    first.onMessage('{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}')
    await flushDecoderStart()

    const second = await attach(rig)
    second.detach() // the caller's replacement pattern
    // First decoder closed by the second attach's replacement in main.ts —
    // here the mixer survives: the worklet is reused, not rebuilt.
    expect(rig.host.addedModules).toHaveLength(1)
    expect(rig.webCodecs.decoderClosed).toBe(false) // only detach() closes
  })
})

describe('createMessageBuffer (the attach-window guard, #104 found live)', () => {
  it('messages pushed before the feed exists drain in arrival order', () => {
    const buffer = createMessageBuffer()
    const seen: unknown[] = []
    buffer.push('a')
    buffer.push(new ArrayBuffer(4))
    buffer.push('c')
    buffer.drainInto({ onMessage: (data) => seen.push(data), detach: () => undefined })
    expect(seen).toHaveLength(3)
    expect(seen[0]).toBe('a')
    expect(seen[1]).toBeInstanceOf(ArrayBuffer)
    expect(seen[2]).toBe('c')
  })

  it('drain is one-shot and the buffer bounds itself', () => {
    const buffer = createMessageBuffer()
    let drained = 0
    for (let i = 0; i < 100; i++) buffer.push(i) // capacity 64
    buffer.drainInto({ onMessage: () => { drained += 1 }, detach: () => undefined })
    expect(drained).toBe(64)
    buffer.drainInto({ onMessage: () => { drained += 1 }, detach: () => undefined })
    expect(drained).toBe(64) // splice emptied it — nothing twice
  })
})
