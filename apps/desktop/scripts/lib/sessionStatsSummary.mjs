// Phase16 analysis core: turns one (or two) capture-session-stats.mjs JSONL
// files into per-run summaries — the mechanical "documented before/after" the
// roadmap demands for every accepted optimization.
//
// Input shape (capture script): one JSON line per tick —
//   {"t":"<ISO>","pcs":{"media":[…stats entries…],"mic":[…]}}
//
// The stats entries follow RTCStats: cumulative counters (bytesReceived,
// framesDecoded, jitterBufferDelay, …) grow over the pc's lifetime, while
// instantaneous members (framesPerSecond, jitter, RTT) are point readings.
// So totals are first-vs-last deltas over the analyzed window, and
// distributions are per-tick series between adjacent ticks. Pure
// data-in/data-out, zero dependencies — vitest covers it directly
// (scripts/analyze-session-stats.test.ts); the CLI is a thin printer.

/**
 * Session-start ticks excluded from every steady-state metric: each cast
 * ramps its capture format over the first ~12 s (reliability.md, session A
 * finding 3) — measuring the ramp would grade start-up, not the run.
 */
export const START_RAMP_EXCLUDE_SEC = 15

/** The entry's numeric member, or null when absent/not a number. */
function num(entry, key) {
  const value = entry[key]
  return typeof value === 'number' ? value : null
}

/** The inbound-rtp entry of a given kind from one stats report, or null. */
export function inboundOf(entries, kind) {
  for (const entry of entries) {
    if (entry.type === 'inbound-rtp' && entry.kind === kind) return entry
  }
  return null
}

/** The nominated, succeeded candidate-pair's RTT in seconds, or null. */
export function rttSecondsOf(entries) {
  for (const entry of entries) {
    if (
      entry.type === 'candidate-pair' &&
      entry.nominated === true &&
      entry.state === 'succeeded' &&
      typeof entry.currentRoundTripTime === 'number'
    ) {
      return entry.currentRoundTripTime
    }
  }
  return null
}

/**
 * Parse a capture JSONL into normalized ticks. Blank/unparsable lines are
 * skipped (a capture can end mid-write); a tick with no pcs is kept — its
 * emptiness is part of the run's story (pcs torn down, session C).
 */
export function parseCapture(text) {
  const ticks = []
  for (const line of String(text).split('\n')) {
    const trimmed = line.trim()
    if (trimmed === '') continue
    let parsed
    try {
      parsed = JSON.parse(trimmed)
    } catch {
      continue
    }
    if (typeof parsed !== 'object' || parsed === null || typeof parsed.t !== 'string') continue
    const tMs = Date.parse(parsed.t)
    if (!Number.isFinite(tMs)) continue
    const pcsBag = parsed.pcs
    ticks.push({
      tMs,
      media: Array.isArray(pcsBag?.media) ? pcsBag.media : [],
      mic: Array.isArray(pcsBag?.mic) ? pcsBag.mic : []
    })
  }
  return ticks
}

/** Nearest-rank percentile (p in 0..1) over a SORTED array; null when empty. */
export function percentile(sortedValues, p) {
  if (sortedValues.length === 0) return null
  const idx = Math.min(sortedValues.length - 1, Math.max(0, Math.round(p * (sortedValues.length - 1))))
  return sortedValues[idx]
}

function percentiles(values) {
  const sorted = [...values].sort((a, b) => a - b)
  return {
    p10: percentile(sorted, 0.1),
    p50: percentile(sorted, 0.5),
    p90: percentile(sorted, 0.9)
  }
}

function round(value, digits) {
  if (value === null || !Number.isFinite(value)) return null
  const factor = 10 ** digits
  return Math.round(value * factor) / factor
}

/**
 * The receive-side summary of one pc over one capture: `video` (the media pc)
 * and/or `audio` (game audio on the media pc; the mic on the mic pc).
 * null-pc / null-track fields mean "the report never carried it".
 */
export function summarizePc(ticks, pcId, { excludeStartSec = START_RAMP_EXCLUDE_SEC } = {}) {
  if (ticks.length === 0) return { pcId, analyzedTicks: 0, video: null, audio: null }
  const firstTMs = ticks[0].tMs
  const inWindow = ticks.filter((tick) => (tick.tMs - firstTMs) / 1000 >= excludeStartSec)
  if (inWindow.length < 2) return { pcId, analyzedTicks: inWindow.length, video: null, audio: null }

  const entriesOf = (tick) => tick[pcId === 'media' ? 'media' : 'mic']
  const videoSeries = []
  const audioSeries = []
  for (const tick of inWindow) {
    const entries = entriesOf(tick)
    const video = inboundOf(entries, 'video')
    videoSeries.push(
      video === null
        ? null
        : { tMs: tick.tMs, video, rttSec: rttSecondsOf(entries) }
    )
    audioSeries.push({ tMs: tick.tMs, audio: inboundOf(entries, 'audio') })
  }

  const result = { pcId, analyzedTicks: inWindow.length, video: null, audio: null }

  const videoTicks = videoSeries.filter((tick) => tick !== null && tick.video !== null)
  if (videoTicks.length >= 2) {
    const first = videoTicks[0].video
    const last = videoTicks[videoTicks.length - 1].video
    const spanSec = (videoTicks[videoTicks.length - 1].tMs - videoTicks[0].tMs) / 1000
    if (spanSec > 0) {
      const bitrateSeries = []
      const fpsSeries = []
      const jitterBufferSeries = []
      const rttSeries = []
      for (let i = 0; i < videoTicks.length; i += 1) {
        const fps = num(videoTicks[i].video, 'framesPerSecond')
        if (fps !== null) fpsSeries.push(fps)
        const rtt = videoTicks[i].rttSec
        if (rtt !== null) rttSeries.push(rtt * 1000)
        if (i > 0) {
          const before = videoTicks[i - 1].video
          const now = videoTicks[i].video
          const dtSec = (videoTicks[i].tMs - videoTicks[i - 1].tMs) / 1000
          bitrateSeries.push(((num(now, 'bytesReceived') - num(before, 'bytesReceived')) * 8) / dtSec)
          const delayBefore = num(before, 'jitterBufferDelay')
          const emittedBefore = num(before, 'jitterBufferEmittedCount')
          const delayNow = num(now, 'jitterBufferDelay')
          const emittedNow = num(now, 'jitterBufferEmittedCount')
          if (delayBefore !== null && emittedBefore !== null && delayNow !== null && emittedNow !== null) {
            const dEmitted = emittedNow - emittedBefore
            const dDelay = delayNow - delayBefore
            if (dEmitted > 0 && dDelay >= 0) jitterBufferSeries.push((dDelay / dEmitted) * 1000)
          }
        }
      }
      // No instantaneous fps member in the report — the decode rate is the fallback.
      const decodeRate =
        (num(last, 'framesDecoded') - num(first, 'framesDecoded')) / spanSec
      const counterDelta = (key) => {
        const a = num(first, key)
        const b = num(last, key)
        return a !== null && b !== null ? b - a : null
      }
      const decoded = counterDelta('framesDecoded')
      const dropped = counterDelta('framesDropped')
      const lost = counterDelta('packetsLost')
      const receivedPackets = counterDelta('packetsReceived')
      const totalFrames = decoded + dropped
      result.video = {
        bitrateBps: percentiles(bitrateSeries),
        fps: fpsSeries.length > 0 ? percentiles(fpsSeries) : null,
        decodeRateAvg: round(decodeRate, 1),
        decodedFrames: decoded,
        droppedFrames: dropped,
        dropRatePct: totalFrames !== null && totalFrames > 0 ? round((dropped / totalFrames) * 100, 2) : null,
        packetsLost: lost,
        lossPct:
          lost !== null && receivedPackets !== null && lost + receivedPackets >0
            ? round((lost / (lost + receivedPackets)) * 100, 3)
            : null,
        freezeCount: counterDelta('freezeCount'),
        freezesSec: counterDelta('totalFreezesDuration') === null ? null : round(counterDelta('totalFreezesDuration'), 2),
        keyFramesDecoded: counterDelta('keyFramesDecoded'),
        pliCount: counterDelta('pliCount'),
        nackCount: counterDelta('nackCount'),
        jitterBufferMs: jitterBufferSeries.length > 0 ? round(percentile([...jitterBufferSeries].sort((a, b) => a - b), 0.5), 1) : null,
        rttMs: rttSeries.length > 0 ? round(percentile([...rttSeries].sort((a, b) => a - b), 0.5), 1) : null,
        frameSize:
          num(last, 'frameWidth') !== null && num(last, 'frameHeight') !== null
            ? `${num(last, 'frameWidth')}x${num(last, 'frameHeight')}`
            : null
      }
    }
  }

  const audioTicks = audioSeries.filter((tick) => tick.audio !== null)
  if (audioTicks.length >= 2) {
    const first = audioTicks[0].audio
    const last = audioTicks[audioTicks.length - 1].audio
    const spanSec = (audioTicks[audioTicks.length - 1].tMs - audioTicks[0].tMs) / 1000
    if (spanSec > 0) {
      const counterDelta = (key) => {
        const a = num(first, key)
        const b = num(last, key)
        return a !== null && b !== null ? b - a : null
      }
      const lost = counterDelta('packetsLost')
      const receivedPackets = counterDelta('packetsReceived')
      const concealed = counterDelta('concealedSamples')
      const samples = counterDelta('totalSamplesReceived')
      result.audio = {
        bitrateBps: round(((num(last, 'bytesReceived') - num(first, 'bytesReceived')) * 8) / spanSec, 0),
        packetsLost: lost,
        lossPct:
          lost !== null && receivedPackets !== null && lost + receivedPackets > 0
            ? round((lost / (lost + receivedPackets)) * 100, 3)
            : null,
        concealmentPct: concealed !== null && samples !== null && samples > 0 ? round((concealed / samples) * 100, 2) : null
      }
    }
  }
  return result
}

/**
 * The full run summary: both pcs plus the capture envelope.
 * @param {string} text capture JSONL
 */
export function summarizeRun(text, options) {
  const ticks = parseCapture(text)
  const durationSec = ticks.length > 1 ? round((ticks[ticks.length - 1].tMs - ticks[0].tMs) / 1000, 1) : 0
  return {
    ticks: ticks.length,
    durationSec,
    media: summarizePc(ticks, 'media', options),
    mic: summarizePc(ticks, 'mic', options)
  }
}
