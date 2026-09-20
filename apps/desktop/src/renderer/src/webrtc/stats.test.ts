import { describe, expect, it } from 'vitest'
import { bitrateBps, sampleVideoReceive } from './stats'

// Stats entries in the shape Chromium's getStats() yields (spread to plain
// records). Numbers per RTCStats: jitter/currentRoundTripTime are seconds.
const INBOUND_VIDEO = {
  type: 'inbound-rtp',
  kind: 'video',
  bytesReceived: 1_500_000,
  packetsLost: 2,
  jitter: 0.003,
  framesDecoded: 900,
  framesDropped: 1,
  decoderImplementation: 'ffmpeg'
}
const INBOUND_AUDIO = {
  type: 'inbound-rtp',
  kind: 'audio',
  bytesReceived: 999,
  jitter: 0.01,
  framesDecoded: 42
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
      decoderImplementation: 'ffmpeg'
    })
  })

  it('yields zeros and nulls when stats are missing (stream still ramping up)', () => {
    const sample = sampleVideoReceive([INBOUND_AUDIO])
    expect(sample.bytesReceived).toBe(0)
    expect(sample.rttMs).toBeNull()
    expect(sample.decoderImplementation).toBeNull()
  })

  it('does not report RTT from a pair that has not succeeded', () => {
    const sample = sampleVideoReceive([FAILED_PAIR])
    expect(sample.rttMs).toBeNull()
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
