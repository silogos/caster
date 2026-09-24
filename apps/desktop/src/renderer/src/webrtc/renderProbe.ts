// Phase16 render probe: the receiver-side decode→render visibility that the
// stats line cannot give. `requestVideoFrameCallback` hands us one callback per
// presented frame — the *only* direct view of presentation pacing — and the
// intervals between callbacks are the render-stability signal (a clean direct
// composite should present at the stream's own cadence; gaps are held or
// dropped presentations).
//
// Pure split so it is unit-testable: interval collection over an injected
// video element, summary math over the raw stamps. Production glue (renderer
// main.ts) wraps the live `<video>` behind the Phase15 debug hook — the probe
// never runs unless a script asks for it.

/** The video surface the probe needs — satisfied by HTMLVideoElement, faked in tests. */
export interface VideoWithFrameCallback {
  requestVideoFrameCallback(
    callback: (now: number, metadata: { presentedFrames: number }) => void
  ): number
}

/** The probe's verdict over one measurement window. */
export interface RenderProbeSummary {
  /** Frames presented during the window. */
  frames: number
  /** Mean presentation interval, ms. */
  meanIntervalMs: number
  /** 95th-percentile interval, ms — the tail is where stutter shows. */
  p95IntervalMs: number
  /** Longest interval, ms. */
  maxIntervalMs: number
  /** Intervals ≥ gapMultiple × the median — held or dropped presentations. */
  gapCount: number
  /** The interval the gap count was measured against, ms. */
  medianIntervalMs: number
}

/**
 * Collect `frameCount` presentation timestamps from the video element's
 * `requestVideoFrameCallback`. Resolves with the raw high-resolution stamps
 * (first callback's timestamp included — its interval is discarded by the
 * summary). The stall clock restarts at every presentation: rejects when
 * `timeoutMs` pass without one — either the element has no live stream, or
 * the stream went silent mid-window.
 */
export function collectPresentationStamps(
  video: VideoWithFrameCallback,
  frameCount: number,
  timeoutMs: number,
  setTimeoutFn: (fn: () => void, ms: number) => unknown = setTimeout,
  clearTimeoutFn: (handle: unknown) => void = (handle) => {
    clearTimeout(handle as Parameters<typeof clearTimeout>[0])
  }
): Promise<Array<number>> {
  if (frameCount < 2) {
    return Promise.reject(new Error('frameCount must be at least two — one interval needs two stamps'))
  }
  return new Promise((resolve, reject) => {
    const stamps: Array<number> = []
    let timeoutHandle: unknown = null
    const armTimeout = (): void => {
      clearTimeoutFn(timeoutHandle)
      timeoutHandle = setTimeoutFn(() => {
        reject(
          stamps.length > 0
            ? new Error(`render probe timed out after ${stamps.length} of ${frameCount} frames — stream stalled?`)
            : new Error('render probe timed out — no frame presented (no live stream on the element?)')
        )
      }, timeoutMs)
    }
    const tick = (now: number): void => {
      stamps.push(now)
      if (stamps.length >= frameCount) {
        clearTimeoutFn(timeoutHandle)
        resolve(stamps)
        return
      }
      // The stall clock restarts at every frame: a slow-but-live cadence must
      // never read as a stall, only silence between presentations may.
      armTimeout()
      video.requestVideoFrameCallback(tick)
    }
    // No frame yet at all — the same clock covers the stream-less element.
    armTimeout()
    video.requestVideoFrameCallback(tick)
  })
}

/** Interval between consecutive stamps that counts as a presentation gap. */
export const GAP_MULTIPLE = 1.5

/**
 * Summarize presentation stamps: interval distribution plus gaps measured
 * against the median (robust to one long interval skewing the reference —
 * unlike the mean, the median stays at the stream's true cadence).
 */
export function summarizeStamps(stamps: Array<number>): RenderProbeSummary {
  if (stamps.length < 2) throw new Error('summarizeStamps needs at least two stamps')
  const intervals: Array<number> = []
  for (let i = 1; i < stamps.length; i += 1) {
    const interval = stamps[i] - stamps[i - 1]
    if (interval >= 0) intervals.push(interval)
  }
  const sorted = [...intervals].sort((a, b) => a - b)
  const median = sorted[Math.floor(sorted.length / 2)]
  const p95 = sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))]
  let gapCount = 0
  for (const interval of intervals) {
    if (interval >= median * GAP_MULTIPLE) gapCount += 1
  }
  const mean = intervals.reduce((sum, value) => sum + value, 0) / intervals.length
  return {
    frames: intervals.length,
    meanIntervalMs: round1(mean),
    p95IntervalMs: round1(p95),
    maxIntervalMs: round1(sorted[sorted.length - 1]),
    gapCount,
    medianIntervalMs: round1(median)
  }
}

function round1(value: number): number {
  return Number(value.toFixed(1))
}
