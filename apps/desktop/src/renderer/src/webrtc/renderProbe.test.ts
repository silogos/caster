import { describe, expect, it, vi } from 'vitest'
import { GAP_MULTIPLE, collectPresentationStamps, summarizeStamps, type VideoWithFrameCallback } from './renderProbe'

// A fake video element whose `requestVideoFrameCallback` hands back stamps on a
// scriptable schedule — the probe only needs the callback surface, never a
// real <video> (rendering is Chromium's job; this file tests the arithmetic).
class FakeVideo implements VideoWithFrameCallback {
  private handle: number = 0
  /** Callbacks waiting for their frame, in registration order. */
  private pending: Array<{ id: number; callback: (now: number, metadata: { presentedFrames: number }) => void }> = []
  /** Frames the element would present next, as timestamps. */
  nextStamps: Array<number> = []

  requestVideoFrameCallback(callback: (now: number, metadata: { presentedFrames: number }) => void): number {
    this.handle += 1
    const id = this.handle
    this.pending.push({ id, callback })
    return id
  }

  /** Present `this.nextStamps` — each pending callback gets one stamp. */
  presentAll(): void {
    const stamps = this.nextStamps
    this.nextStamps = []
    for (const stamp of stamps) this.presentAt(stamp)
  }

  presentAt(stamp: number): void {
    const entry = this.pending.shift()
    expect(entry).toBeDefined()
    entry!.callback(stamp, { presentedFrames: this.handle })
  }
}

describe('collectPresentationStamps', () => {
  it('collects stamps until the frame count is reached', async () => {
    const video = new FakeVideo()
    const collecting = collectPresentationStamps(video, 4, 1_000)
    video.nextStamps = [100, 133, 166, 200]
    video.presentAll()
    await expect(collecting).resolves.toEqual([100, 133, 166, 200])
  })

  it('rejects when no frame is presented before the timeout', async () => {
    vi.useFakeTimers()
    try {
      const video = new FakeVideo()
      // Fake timers swap in for the probe's default setTimeout/clearTimeout.
      const collecting = expect(collectPresentationStamps(video, 3, 500)).rejects.toThrow('no frame presented')
      await vi.advanceTimersByTimeAsync(600)
      await collecting
    } finally {
      vi.useRealTimers()
    }
  })

  it('rejects mid-collection when the stream stalls between frames', async () => {
    vi.useFakeTimers()
    try {
      const video = new FakeVideo()
      const collecting = expect(collectPresentationStamps(video, 3, 500)).rejects.toThrow('timed out after 1 of 3 frames')
      video.presentAt(100)
      await vi.advanceTimersByTimeAsync(600)
      await collecting
    } finally {
      vi.useRealTimers()
    }
  })

  it('refuses a one-frame window — an interval needs two stamps', async () => {
    const video = new FakeVideo()
    await expect(collectPresentationStamps(video, 1, 500)).rejects.toThrow('at least two')
  })
})

describe('summarizeStamps', () => {
  it('summarizes a clean cadence with zero gaps', () => {
    // 30 fps —33 ms apart.
    const stamps = [0, 33, 66, 100, 133, 166]
    const summary = summarizeStamps(stamps)
    expect(summary.frames).toBe(5)
    expect(summary.gapCount).toBe(0)
    expect(summary.medianIntervalMs).toBe(33)
    expect(summary.meanIntervalMs).toBe(33.2)
  })

  it('counts held presentations as gaps, without letting them skew the median', () => {
    // Five clean 33 ms intervals, then one 300 ms stall.
    const stamps = [0, 33, 66, 100, 133, 166, 466]
    const summary = summarizeStamps(stamps)
    expect(summary.gapCount).toBe(1)
    expect(summary.medianIntervalMs).toBe(33)
    expect(summary.maxIntervalMs).toBe(300)
    expect(summary.p95IntervalMs).toBe(300)
  })

  it('measures gaps against the median, not the mean (GAP_MULTIPLE contract)', () => {
    expect(GAP_MULTIPLE).toBe(1.5)
    // Mean is dragged up by the stall; the median is not — a stall of
    // 1.4× the median must NOT count as a gap, one of 1.5× must.
    const clean = [0, 40, 80, 120, 160, 200, 240, 280, 320, 360]
    const borderline = [...clean, 360 + (14 * 4)] // 56 ms = 1.4x median
    const gap = [...clean, 360 + (15 * 4)] // 60 ms = 1.5x median
    expect(summarizeStamps(borderline).gapCount).toBe(0)
    expect(summarizeStamps(gap).gapCount).toBe(1)
  })

  it('refuses fewer than two stamps', () => {
    expect(() => summarizeStamps([100])).toThrow('at least two')
  })
})
