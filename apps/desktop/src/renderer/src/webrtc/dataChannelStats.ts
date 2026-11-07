// DataChannel receive counters over a stats interval (ADR-013): the
// `game-audio` channel is invisible to getStats (SCTP, not RTP), so its
// wire bytes and frame counters are the only channel visibility the stats
// line gets. Pure — consumes the live counters object from
// gameAdtsAac.createGameAacChannel and turns it into per-interval samples,
// the same shape sampleAudioReceive/bitrateBps feed the log line with.

import { bitrateBps } from './stats'
import type { GameChannelCounters } from '../audio/gameAdtsAac'

/** One interval's receive sample for the `game-audio` DataChannel. */
export interface GameChannelSample {
  bitrateBps: number
  mode: 'pcm' | 'aac'
  framesIn: number
  invalidFrames: number
  decodeErrors: number
  /** Cumulative rendered frames — the liveness signal (null = never decoded). */
  framesRendered: number
  /**
   * Frames queued inside the decoder at sample time — the decode-pipeline
   * residence signal (a growing queue means the renderer is starved).
   */
  decodeQueueDepth: number
  /**
   * The worklet ring's depth in ms — the playout-tuning telemetry. The
   * sampler doesn't know the mixer; it emits null and the wiring (main.ts)
   * overrides with the mixer's live value.
   */
  ringDepthMs: number | null
}

interface CountersState {
  lastBytesIn: number
}

/**
 * Interval sampler over a (mutating) counters object. The first call
 * establishes the baseline; counter resets (a fresh channel object) yield
 * null rather than a bogus negative window — the same contract as
 * jitterBufferMs in stats.ts.
 */
export function createGameChannelSampler(read: () => GameChannelCounters): {
  sample(intervalMs: number): GameChannelSample | null
} {
  let state: CountersState | null = null
  let lastMode: GameChannelCounters['mode'] = 'pcm'
  return {
    sample(intervalMs: number): GameChannelSample | null {
      const counters = read()
      if (intervalMs <= 0) return null
      if (counters.mode !== lastMode) {
        // A format switch re-arms the byte baseline — the two formats' rates
        // are not comparable across the boundary.
        state = null
        lastMode = counters.mode
      }
      if (state !== null && counters.bytesIn < state.lastBytesIn) {
        // Counter reset (channel rebuilt) — skip the bogus window.
        state = { lastBytesIn: counters.bytesIn }
        return null
      }
      const sample: GameChannelSample = {
        bitrateBps: Math.round(bitrateBps(counters.bytesIn, state?.lastBytesIn ?? counters.bytesIn, intervalMs)),
        mode: counters.mode,
        framesIn: counters.framesIn,
        invalidFrames: counters.invalidFrames,
        decodeErrors: counters.decodeErrors,
        framesRendered: counters.framesRendered,
        decodeQueueDepth: counters.decodeQueueDepth,
        ringDepthMs: null
      }
      state = { lastBytesIn: counters.bytesIn }
      return sample
    }
  }
}
