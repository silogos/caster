import { describe, expect, it } from 'vitest'
import { createGameChannelSampler } from './dataChannelStats'
import type { GameChannelCounters } from '../audio/gameAdtsAac'

/**
 * ADR-013's stats sampling over the live channel counters: wire bitrate per
 * interval, format switches re-arming the byte baseline, and counter resets
 * (a rebuilt channel) yielding null instead of a bogus negative window.
 */
function counters(mode: GameChannelCounters['mode'] = 'pcm'): GameChannelCounters {
  return { bytesIn: 0, framesIn: 0, invalidFrames: 0, framesRendered: 0, decodeErrors: 0, mode }
}

describe('game channel sampler (ADR-013)', () => {
  it('the first sample reports a zero bitrate and establishes the baseline', () => {
    const c = counters()
    const sampler = createGameChannelSampler(() => c)
    expect(sampler.sample(1000)).toMatchObject({ bitrateBps: 0, mode: 'pcm' })
  })

  it('bitrate reflects the byte delta over the interval', () => {
    const c = counters()
    const sampler = createGameChannelSampler(() => c)
    sampler.sample(1000)
    c.bytesIn += 25_000 // ~0.2 Mbps worth per second
    const sample = sampler.sample(1000)
    expect(sample?.bitrateBps).toBe(200_000)
  })

  it('a format switch re-arms the baseline (no cross-format window)', () => {
    const c = counters('pcm')
    const sampler = createGameChannelSampler(() => c)
    sampler.sample(1000)
    c.bytesIn += 25_000
    c.mode = 'aac'
    const sample = sampler.sample(1000)
    // The byte delta since the pcm sample must not leak into the aac bitrate.
    expect(sample).toMatchObject({ mode: 'aac', bitrateBps: 0 })
  })

  it('a counter reset (rebuilt channel) yields null instead of a negative window', () => {
    let c = counters()
    const sampler = createGameChannelSampler(() => c)
    sampler.sample(1000)
    c.bytesIn += 25_000
    expect(sampler.sample(1000)?.bitrateBps).toBe(200_000)
    c = counters() // the channel object was replaced — bytesIn went backwards
    expect(sampler.sample(1000)).toBeNull()
    c.bytesIn += 25_000
    expect(sampler.sample(1000)?.bitrateBps).toBe(200_000)
  })

  it('non-positive intervals are refused', () => {
    const c = counters()
    const sampler = createGameChannelSampler(() => c)
    expect(sampler.sample(0)).toBeNull()
  })
})
