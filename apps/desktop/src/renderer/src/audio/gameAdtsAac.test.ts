import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createGameAacChannel,
  parseAdtsFrame,
  GAME_AUDIO_FORMAT_AAC,
  type AudioDataLike,
  type AudioDecoderFactory,
  type AudioDecoderLike,
  type GameAacChannelOptions
} from './gameAdtsAac'

/**
 * ADR-013's receiver: the in-band handshake (ack only when WebCodecs can
 * actually decode), ADTS validation with re-sync, the receiver-side
 * sample-clock timestamps, and the pcm/aac routing — browser APIs injected,
 * vitest runs without Chromium.
 */

/** Builds one valid ADTS message around a raw payload (the mobile's exact header math). */
function adtsPacket(rawPayload: Uint8Array): ArrayBuffer {
  const total = rawPayload.length + 7
  const bytes = new Uint8Array(total)
  bytes[0] = 0xff
  bytes[1] = 0xf9 // MPEG-4, layer 0, protection_absent
  bytes[2] = 0x4c // LC profile, 48 kHz (index 3), stereo hi bits
  bytes[3] = ((2 & 0x3) << 6) | (total >> 11)
  bytes[4] = (total >> 3) & 0xff
  bytes[5] = ((total & 0x7) << 5) | 0x1f
  bytes[6] = 0xfc
  bytes.set(rawPayload, 7)
  return bytes.buffer
}

/** A decode-output fake: two planar f32 frames, values = plane+index. */
class FakeAudioData implements AudioDataLike {
  constructor(readonly numberOfFrames: number) {}
  copyTo(destination: Float32Array, options: { planeIndex: number }): void {
    for (let i = 0; i < this.numberOfFrames; i++) destination[i] = options.planeIndex + i
  }
  close(): void {}
}

class FakeAudioDecoder implements AudioDecoderLike {
  static instances: FakeAudioDecoder[] = []
  readonly configured: Array<Record<string, unknown>> = []
  readonly decoded: Array<{ timestamp: number; data: Uint8Array }> = []
  decodeQueueSize = 0
  private onOutput?: (audioData: AudioDataLike) => void

  constructor(init: { output: (audioData: AudioDataLike) => void; error: (error: DOMException) => void }) {
    this.onOutput = init.output
    FakeAudioDecoder.instances.push(this)
  }

  configure(config: Record<string, unknown>): void {
    this.configured.push(config)
  }

  /** Synchronously "decodes": two frames whose planar values mark the planes. */
  decode(chunk: { type: 'key'; timestamp: number; data: Uint8Array }): void {
    this.decoded.push({ timestamp: chunk.timestamp, data: chunk.data })
    this.onOutput?.(new FakeAudioData(2))
  }

  flush(): Promise<void> {
    return Promise.resolve()
  }

  close(): void {}
}

interface Feed {
  pcm: ArrayBuffer[]
  frames: Array<{ left: Float32Array; right: Float32Array }>
}

function makeChannel(factory: AudioDecoderFactory | null = FakeAudioDecoder as unknown as AudioDecoderFactory): {
  channel: ReturnType<typeof createGameAacChannel>
  feed: Feed
} {
  const feed: Feed = { pcm: [], frames: [] }
  const options: GameAacChannelOptions = {
    pushPcm: (chunk) => feed.pcm.push(chunk),
    pushFrames: (left, right) => feed.frames.push({ left, right })
  }
  if (factory !== null) options.createDecoder = factory
  return { channel: createGameAacChannel(options), feed }
}

const AAC_ANNOUNCE = JSON.stringify({ v: 1, type: 'announce', format: GAME_AUDIO_FORMAT_AAC })

afterEach(() => {
  FakeAudioDecoder.instances = []
  vi.restoreAllMocks()
})

describe('parseAdtsFrame', () => {
  it('extracts the payload after the 7-byte header', () => {
    const payload = new Uint8Array([1, 2, 3, 4, 5])
    const frame = parseAdtsFrame(adtsPacket(payload))
    expect(frame).not.toBeNull()
    expect(Array.from(frame!.payload)).toEqual([1, 2, 3, 4, 5])
  })

  it('rejects short messages, broken syncwords, wrong layer bits, and lying lengths', () => {
    expect(parseAdtsFrame(new ArrayBuffer(4))).toBeNull()
    const wrongSync = new Uint8Array(16)
    wrongSync[0] = 0xfe
    expect(parseAdtsFrame(wrongSync.buffer)).toBeNull()
    const wrongLayer = new Uint8Array(16)
    wrongLayer[0] = 0xff
    wrongLayer[1] = 0xf3 // layer != 0
    expect(parseAdtsFrame(wrongLayer.buffer)).toBeNull()
    const lyingLength = new Uint8Array(16)
    lyingLength[0] = 0xff
    lyingLength[1] = 0xf9
    lyingLength[3] = 0x7f // 13-bit length ~ 32767 > message
    expect(parseAdtsFrame(lyingLength.buffer)).toBeNull()
  })
})

describe('game audio channel handshake (ADR-013)', () => {
  it('opens in pcm mode: binary goes to the PCM push untouched', () => {
    const { channel, feed } = makeChannel()
    const chunk = new ArrayBuffer(16)
    channel.onBinary(chunk)
    expect(feed.pcm).toEqual([chunk])
    expect(feed.frames).toHaveLength(0)
    expect(channel.counters.mode).toBe('pcm')
  })

  it('acks the aac announce and switches the wire to aac', () => {
    const { channel } = makeChannel()
    const ack = channel.onAnnounce(AAC_ANNOUNCE)
    expect(ack).toBe(JSON.stringify({ v: 1, type: 'ack', format: GAME_AUDIO_FORMAT_AAC }))
    expect(channel.counters.mode).toBe('aac')
    // Idempotent re-announce (the mobile retries on the unordered channel).
    expect(channel.onAnnounce(AAC_ANNOUNCE)).toBeNull()
  })

  it('never acks without a usable decoder — pcm stands', () => {
    const { channel, feed } = makeChannel(null)
    expect(channel.onAnnounce(AAC_ANNOUNCE)).toBeNull()
    expect(channel.counters.mode).toBe('pcm')
    const chunk = new ArrayBuffer(16)
    channel.onBinary(chunk)
    expect(feed.pcm).toEqual([chunk])
  })

  it('a pcm16 announce switches back — the encoder-failure revert', () => {
    const { channel, feed } = makeChannel()
    channel.onAnnounce(AAC_ANNOUNCE)
    channel.onAnnounce(JSON.stringify({ v: 1, type: 'announce', format: 'pcm16' }))
    expect(channel.counters.mode).toBe('pcm')
    const chunk = new ArrayBuffer(16)
    channel.onBinary(chunk)
    expect(feed.pcm).toEqual([chunk])
  })

  it('garbage or foreign announce text is ignored', () => {
    const { channel } = makeChannel()
    expect(channel.onAnnounce('not json')).toBeNull()
    expect(channel.onAnnounce(JSON.stringify({ v: 2, type: 'announce', format: GAME_AUDIO_FORMAT_AAC }))).toBeNull()
    expect(channel.counters.mode).toBe('pcm')
  })
})

describe('game audio AAC decode path (ADR-013)', () => {
  it('validates ADTS, decodes with sample-clock timestamps, and posts planar pairs', () => {
    const { channel, feed } = makeChannel()
    channel.onAnnounce(AAC_ANNOUNCE)
    const decoder = FakeAudioDecoder.instances[0]
    expect(decoder.configured[0]).toMatchObject({ codec: 'mp4a.40.2', sampleRate: 48_000, numberOfChannels: 2 })

    channel.onBinary(adtsPacket(new Uint8Array([9, 9, 9])))
    channel.onBinary(adtsPacket(new Uint8Array([8, 8, 8])))

    // The receiver-side sample clock: frame n × 1024 × 1e6 / 48000.
    expect(decoder.decoded.map((entry) => entry.timestamp)).toEqual([0, 21333])
    expect(decoder.decoded[0].data).toEqual(new Uint8Array([9, 9, 9]))
    // The decoded AudioData (plane i → value i at frame 0) reaches the worklet shape.
    expect(feed.frames).toHaveLength(2)
    expect(Array.from(feed.frames[0].left)).toEqual([0, 1])
    expect(Array.from(feed.frames[0].right)).toEqual([1, 2])
    expect(channel.counters.framesRendered).toBe(2)
    expect(channel.counters.framesIn).toBe(2)
  })

  it('counts invalid frames without crashing — the next header resyncs', () => {
    const { channel } = makeChannel()
    channel.onAnnounce(AAC_ANNOUNCE)
    const decoder = FakeAudioDecoder.instances[0]
    channel.onBinary(new ArrayBuffer(3)) // too short
    channel.onBinary(adtsPacket(new Uint8Array([1, 1]))) // valid
    expect(decoder.decoded).toHaveLength(1)
    expect(channel.counters.invalidFrames).toBe(1)
    expect(channel.counters.framesIn).toBe(1)
  })

  it('drops frames when the decoder is backlogged instead of queueing unbounded', () => {
    const { channel } = makeChannel()
    channel.onAnnounce(AAC_ANNOUNCE)
    const decoder = FakeAudioDecoder.instances[0]
    Object.defineProperty(decoder, 'decodeQueueSize', { value: 31 })
    channel.onBinary(adtsPacket(new Uint8Array([1])))
    expect(decoder.decoded).toHaveLength(0)
    expect(channel.counters.invalidFrames).toBe(1)
  })

  it('counts wire bytes for the stats line', () => {
    const { channel } = makeChannel()
    const packet = adtsPacket(new Uint8Array([1, 2, 3]))
    channel.onBinary(packet)
    expect(channel.counters.bytesIn).toBe(packet.byteLength)
  })
})
