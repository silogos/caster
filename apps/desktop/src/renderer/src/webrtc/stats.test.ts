import { describe, expect, it } from 'vitest'
import { bitrateBps, concealmentRate, jitterBufferMs, sampleAudioReceive, sampleVideoReceive } from './stats'

// Stats entries in the shape Chromium's getStats() yields (spread to plain
// records). Numbers per RTCStats: jitter/currentRoundTripTime are seconds,
// and the Phase16 cumulative counters (jitterBufferDelay,
// totalFreezesDuration) are seconds too.
const INBOUND_VIDEO = {
  type: 'inbound-rtp',
  kind: 'video',
  bytesReceived: 1_500_000,
  packetsLost: 2,
  jitter: 0.003,
  framesDecoded: 900,
  framesDropped: 1,
  decoderImplementation: 'ffmpeg',
  framesPerSecond: 29.8,
  framesReceived: 905,
  freezeCount: 1,
  totalFreezesDuration: 0.25,
  jitterBufferDelay: 4.5,
  jitterBufferEmittedCount: 900,
  keyFramesDecoded: 3,
  nackCount: 7,
  pliCount: 2,
  frameWidth: 1280,
  frameHeight: 720
}
const INBOUND_AUDIO = {
  type: 'inbound-rtp',
  kind: 'audio',
  bytesReceived: 999,
  packetsLost: 1,
  jitter: 0.01,
  framesDecoded: 42,
  concealedSamples: 480,
  totalSamplesReceived: 96_000
}
const NOMINATED_PAIR = {
  type: 'candidate-pair',
  nominated: true,
  state: 'succeeded',
  currentRoundTripTime: 0.0025
}
const FAILED_PAIR = {
  type: 'candidate-pair',
  nominated: true,
  state: 'failed'
}
const UNNOMINATED_PAIR = {
  type: 'candidate-pair',
  nominated: false,
  state: 'succeeded',
  currentRoundTripTime: 0.999
}

describe('sampleVideoReceive', () => {
  it('extracts the video inbound-rtp and the nominated pair RTT', () => {
    const sample = sampleVideoReceive([INBOUND_VIDEO, INBOUND_AUDIO, NOMINATED_PAIR, UNNOMINATED_PAIR])
    expect(sample).toEqual({
      bytesReceived: 1_500_000,
      packetsLost: 2,
      jitterMs: 3,
      framesDecoded: 900,
      framesDropped: 1,
      rttMs: 2.5,
      decoderImplementation: 'ffmpeg',
      framesPerSecond: 29.8,
      framesReceived: 905,
      freezeCount: 1,
      totalFreezesDuration: 0.25,
      jitterBufferDelay: 4.5,
      jitterBufferEmittedCount: 900,
      keyFramesDecoded: 3,
      nackCount: 7,
      pliCount: 2,
      frameWidth: 1280,
      frameHeight: 720
    })
  })

  it('yields zeros and nulls when stats are missing (stream still ramping up)', () => {
    const sample = sampleVideoReceive([INBOUND_AUDIO])
    expect(sample.bytesReceived).toBe(0)
    expect(sample.rttMs).toBeNull()
    expect(sample.decoderImplementation).toBeNull()
    // Phase16 decode/render fields: absent means null, never a fake zero.
    expect(sample.framesPerSecond).toBeNull()
    expect(sample.freezeCount).toBeNull()
    expect(sample.jitterBufferDelay).toBeNull()
    expect(sample.frameWidth).toBeNull()
  })

  it('does not report RTT from a pair that has not succeeded', () => {
    const sample = sampleVideoReceive([FAILED_PAIR])
    expect(sample.rttMs).toBeNull()
  })
})

describe('sampleAudioReceive', () => {
  it('extracts the audio inbound-rtp sample when one exists', () => {
    const sample = sampleAudioReceive([INBOUND_VIDEO, INBOUND_AUDIO, NOMINATED_PAIR])
    expect(sample).toEqual({
      bytesReceived: 999,
      packetsLost: 1,
      jitterMs: 10,
      concealedSamples: 480,
      totalSamplesReceived: 96_000
    })
  })

  it('returns null when the report has no audio inbound-rtp (mic off)', () => {
    expect(sampleAudioReceive([INBOUND_VIDEO])).toBeNull()
  })

  it('yields nulls for concealment counters the report does not carry', () => {
    const sample = sampleAudioReceive([{ type: 'inbound-rtp', kind: 'audio', bytesReceived: 10 }])
    expect(sample?.bytesReceived).toBe(10)
    expect(sample?.concealedSamples).toBeNull()
    expect(sample?.totalSamplesReceived).toBeNull()
  })
})

describe('jitterBufferMs', () => {
  const cumulative = (delaySec: number, emitted: number) =>
    sampleVideoReceive([{ ...INBOUND_VIDEO, jitterBufferDelay: delaySec, jitterBufferEmittedCount: emitted }])

  it('computes the per-frame residence over the interval between two samples', () => {
    // 3 s more delay across 300 more frames = 10 ms per frame.
    const before = cumulative(4.5, 900)
    const now = cumulative(7.5, 1_200)
    expect(jitterBufferMs(now, before)).toBe(10)
  })

  it('falls back to the total-so-far mean when there is no before-sample', () => {
    // 4.5 s across 900 frames total = 5 ms per frame.
    expect(jitterBufferMs(cumulative(4.5, 900), null)).toBe(5)
  })

  it('yields null on a counter reset (pc rebuild), never a bogus window', () => {
    expect(jitterBufferMs(cumulative(0.1, 30), cumulative(4.5, 900))).toBeNull()
  })

  it('yields null while the report carries no jitter-buffer counters', () => {
    const bare = sampleVideoReceive([{ type: 'inbound-rtp', kind: 'video', bytesReceived: 1 }])
    expect(jitterBufferMs(bare, null)).toBeNull()
  })
})

describe('concealmentRate', () => {
  const cumulative = (concealed: number, total: number) =>
    sampleAudioReceive([{ ...INBOUND_AUDIO, concealedSamples: concealed, totalSamplesReceived: total }])!

  it('computes the interval fraction of concealed samples', () => {
    // 480 more concealed out of 960 more samples = half.
    expect(concealmentRate(cumulative(960, 96_000), cumulative(480, 95_040))).toBe(0.5)
  })

  it('falls back to the total fraction without a before-sample', () => {
    expect(concealmentRate(cumulative(480, 96_000), null)).toBe(0.005)
  })

  it('yields null on a reset or missing counters', () => {
    expect(concealmentRate(cumulative(10, 500), cumulative(480, 96_000))).toBeNull()
    const missing = { bytesReceived: 10, packetsLost: 0, jitterMs: 0, concealedSamples: null, totalSamplesReceived: null }
    expect(concealmentRate(missing, null)).toBeNull()
  })
})

describe('bitrateBps', () => {
  it('computes bits per second from two byte counters', () => {
    // 125_000 bytes over 1000 ms = 1_000_000 bits/s = 1 Mbps.
    expect(bitrateBps(125_000, 0, 1000)).toBe(1_000_000)
  })

  it('never goes negative on counter resets', () => {
    expect(bitrateBps(0, 1000, 1000)).toBe(0)
  })

  it('guards a zero interval', () => {
    expect(bitrateBps(100, 0, 0)).toBe(0)
  })
})
