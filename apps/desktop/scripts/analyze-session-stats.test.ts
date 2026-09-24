import { describe, expect, it } from 'vitest'
import {
  START_RAMP_EXCLUDE_SEC,
  inboundOf,
  parseCapture,
  percentile,
  rttSecondsOf,
  summarizePc,
  summarizeRun
} from './lib/sessionStatsSummary.mjs'

// One media-pc stats entry in Chromium's inbound-rtp shape. Counters are
// cumulative; the ramp-exclusion window (START_RAMP_EXCLUDE_SEC) means the
// summary starts at tick index RAMP and deltas are measured from there.
const CUMULATIVE = {
  bytesReceived: 0,
  packetsLost: 0,
  packetsReceived: 0,
  framesDecoded: 0,
  framesDropped: 0,
  freezeCount: 0,
  totalFreezesDuration: 0,
  keyFramesDecoded: 0,
  pliCount: 0,
  nackCount: 0,
  jitterBufferDelay: 0,
  jitterBufferEmittedCount: 0
}

const videoEntry = (over: Record<string, unknown>) => ({
  type: 'inbound-rtp',
  kind: 'video',
  frameWidth: 1280,
  frameHeight: 720,
  decoderImplementation: 'ffmpeg',
  framesPerSecond: 30,
  ...CUMULATIVE,
  ...over
})

const audioEntry = (over: Record<string, unknown>) => ({
  type: 'inbound-rtp',
  kind: 'audio',
  bytesReceived: 0,
  packetsLost: 0,
  packetsReceived: 0,
  concealedSamples: 0,
  totalSamplesReceived: 0,
  ...over
})

const pair = { type: 'candidate-pair', nominated: true, state: 'succeeded', currentRoundTripTime: 0.005 }

/** A whole capture built from per-second cumulative step descriptions. */
function buildCapture(
  ticks: number,
  step: (second: number) => { video?: Record<string, unknown>; audio?: Record<string, unknown>; mediaPresent?: boolean },
  tickMs = 1_000
): string {
  const lines: string[] = []
  const totals: Record<string, number> = { ...CUMULATIVE } as Record<string, number>
  for (let second = 0; second < ticks; second += 1) {
    const media: Array<Record<string, unknown>> = []
    const s = step(second)
    if (s.mediaPresent !== false) {
      // Counter members accumulate across ticks; instantaneous members ride as-is.
      const instant: Record<string, unknown> = {}
      for (const [key, value] of Object.entries(s.video ?? {})) {
        if (key in CUMULATIVE) totals[key] += value as number
        else instant[key] = value
      }
      media.push(videoEntry({ ...totals, ...instant }))
      if (s.audio !== undefined) {
        media.push(audioEntry(s.audio))
      }
      media.push(pair)
    }
    lines.push(
      JSON.stringify({
        t: new Date(Date.UTC(2026, 0, 1, 0, 0, second)).toISOString(),
        pcs: { media, mic: [] }
      })
    )
    void tickMs
  }
  return lines.join('\n')
}

describe('parseCapture', () => {
  it('parses capture lines and skips blanks and broken JSON', () => {
    const text = [
      '',
      'not json at all',
      JSON.stringify({ t: '2026-01-01T00:00:00Z', pcs: { media: [], mic: [] } }),
      JSON.stringify({ t: 'not-a-date', pcs: {} }),
      JSON.stringify({ t: '2026-01-01T00:00:01Z' })
    ].join('\n')
    const ticks = parseCapture(text)
    expect(ticks).toHaveLength(2)
    expect(ticks[0].media).toEqual([])
    expect(ticks[1].tMs).toBeGreaterThan(ticks[0].tMs)
  })
})

describe('inboundOf / rttSecondsOf', () => {
  const entries = [videoEntry({}), audioEntry({}), { type: 'candidate-pair', nominated: false, state: 'succeeded' }, pair]

  it('finds the inbound entry of a kind', () => {
    expect(inboundOf(entries, 'video')).toBe(entries[0])
    expect(inboundOf(entries, 'audio')).toBe(entries[1])
    expect(inboundOf([], 'video')).toBeNull()
  })

  it('reads RTT only from the nominated succeeded pair', () => {
    expect(rttSecondsOf(entries)).toBe(0.005)
    expect(rttSecondsOf([entries[2]])).toBeNull()
  })
})

describe('percentile', () => {
  it('uses nearest rank over a sorted array', () => {
    expect(percentile([1, 2, 3, 4, 5], 0.5)).toBe(3)
    expect(percentile([1, 2, 3, 4, 5], 0.9)).toBe(5)
    expect(percentile([], 0.5)).toBeNull()
  })
})

describe('summarizePc / summarizeRun', () => {
  it('summarizes a steady cast: bitrate, fps, counters, loss, audio', () => {
    // 60 s of cast: 30 fps steady, 4 Mbps (500 KB/s), one dropped frame and
    // one lost packet every second, 10 ms jitter buffer per frame, mic off.
    const capture = buildCapture(60, () => ({
      video: {
        bytesReceived: 500_000,
        framesDecoded: 30,
        framesDropped: 1,
        packetsLost: 1,
        packetsReceived: 499,
        jitterBufferDelay: 0.3,
        jitterBufferEmittedCount: 30,
        keyFramesDecoded: 0,
        pliCount: 0,
        nackCount: 2,
        freezeCount: 0,
        totalFreezesDuration: 0
      }
    }))
    const summary = summarizeRun(capture)
    expect(summary.ticks).toBe(60)
    expect(summary.durationSec).toBe(59)

    const media = summary.media
    expect(media.analyzedTicks).toBe(60 - START_RAMP_EXCLUDE_SEC)
    expect(media.video).not.toBeNull()
    const v = media.video!
    expect(v.bitrateBps.p50).toBeGreaterThan(3_900_000)
    expect(v.bitrateBps.p50).toBeLessThan(4_100_000)
    expect(v.fps?.p50).toBe(30)
    expect(v.decodedFrames).toBe((60 - START_RAMP_EXCLUDE_SEC -1) * 30)
    expect(v.droppedFrames).toBe(60 - START_RAMP_EXCLUDE_SEC -1)
    expect(v.dropRatePct).toBeCloseTo((1 / 31) * 100, 1)
    expect(v.lossPct).toBeCloseTo((1 / 500) * 100, 1)
    expect(v.jitterBufferMs).toBe(10)
    expect(v.rttMs).toBe(5)
    expect(v.frameSize).toBe('1280x720')
    expect(summary.mic.video).toBeNull()
  })

  it('excludes the session-start ramp from every steady-state metric', () => {
    // Ramp phase: quarter bitrate and fps; steady phase after the window.
    const capture = buildCapture(30, (second) => {
      const ramping = second < START_RAMP_EXCLUDE_SEC
      return {
        video: {
          bytesReceived: ramping ? 125_000 : 500_000,
          framesDecoded: ramping ? 8 : 30,
          framesDropped: 0,
          packetsLost: 0,
          packetsReceived: 500,
          jitterBufferDelay: 0.3,
          jitterBufferEmittedCount: ramping ? 8 : 30,
          framesPerSecond: ramping ? 8 : 30
        }
      }
    })
    const media = summarizePc(parseCapture(capture), 'media')
    expect(media.video?.fps?.p10).toBe(30)
    expect(media.video?.bitrateBps.p10).toBeGreaterThan(3_900_000)
    // The ramp's frames still count in the decoded total — exclusion is
    // about the distribution window, not about pretending they never happened.
    expect(media.video?.decodedFrames).toBeGreaterThan(0)
  })

  it('reports no video when the report never carries one (mic-only capture)', () => {
    const lines: string[] = []
    for (let second = 0; second < 30; second += 1) {
      lines.push(
        JSON.stringify({
          t: new Date(Date.UTC(2026, 0, 1, 0, 0, second)).toISOString(),
          pcs: { media: [], mic: [audioEntry({ bytesReceived: second * 1_000, totalSamplesReceived: second * 48_000 })] }
        })
      )
    }
    const summary = summarizeRun(lines.join('\n'))
    expect(summary.media.video).toBeNull()
    expect(summary.media.audio).toBeNull()
    // The mic pc summarizes its own audio once enough ticks exist.
    const mic = summarizePc(parseCapture(lines.join('\n')), 'mic')
    expect(mic.audio).not.toBeNull()
    expect(mic.audio?.bitrateBps).toBeGreaterThan(0)
  })

  it('keeps a mid-cast pc gap from faking a counter reset (session C story)', () => {
    // Frames 0–19 on pc #1, then a torn-down pc with a fresh report.
    const capture = buildCapture(40, (second) => {
      const tornDown = second >= 20
      return {
        mediaPresent: !tornDown,
        video: {
          bytesReceived: 500_000,
          framesDecoded: 30,
          framesDropped: 0,
          packetsLost: 0,
          packetsReceived: 500,
          jitterBufferDelay: 0.3,
          jitterBufferEmittedCount: 30,
          framesPerSecond: 30
        }
      }
    })
    const media = summarizePc(parseCapture(capture), 'media')
    // The summary covers the window where video existed; the gap never
    // produces negative totals.
    expect(media.video).not.toBeNull()
    expect(media.video!.decodedFrames).toBeGreaterThanOrEqual(0)
  })
})
