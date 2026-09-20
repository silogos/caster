import { describe, expect, it } from 'vitest'

import { contentRectForAspect, MIN_CONTENT_AREA } from './windowGeometry'

/**
 * The window-follows-stream math (review-time restructure of the receiver
 * window): keep the window's content AREA, reshape to the stream's aspect,
 * clamp to the display work area. The landscape complaint this fixes: a
 * window shaped for a portrait cast left the rotated (landscape) stream
 * letterboxed — now the window itself follows the orientation.
 */
describe('contentRectForAspect', () => {
  const workArea = { x: 0, y: 25, width: 1512, height: 957 }

  it('reshapes a portrait window for a landscape stream, keeping the content area', () => {
    const portrait = { x: 100, y: 100, width: 600, height: 900 }
    const rect = contentRectForAspect(portrait, 1280 / 800, workArea)
    // 600x900 = 540000 area; landscape 16:10 → ~929x581 (same area, new shape).
    expect(rect.width).toBe(930)
    expect(rect.height).toBe(581)
  })

  it('reshapes a landscape window for a portrait stream', () => {
    const landscape = { x: 100, y: 100, width: 960, height: 608 }
    const rect = contentRectForAspect(landscape, 800 / 1280, workArea)
    // 960x608 ≈ 583680 area → ~604x966 keeps the area, but the work area
    // is only 957 tall → clamped to 598x957: the portrait window fills the
    // full screen height, video edge-to-edge.
    expect(rect.width).toBe(598)
    expect(rect.height).toBe(957)
  })

  it('never exceeds the work area height', () => {
    const huge = { x: 0, y: 25, width: 1512, height: 957 }
    const rect = contentRectForAspect(huge, 800 / 1280, workArea)
    expect(rect.height).toBeLessThanOrEqual(workArea.height)
    expect(rect.width).toBeLessThanOrEqual(workArea.width)
    // Aspect preserved: the portrait stream still fills the clamped window.
    expect(rect.width / rect.height).toBeCloseTo(800 / 1280, 1)
  })

  it('keeps the position when it fits the work area', () => {
    const content = { x: 200, y: 200, width: 960, height: 608 }
    const rect = contentRectForAspect(content,1280 / 800, workArea)
    expect(rect.x).toBe(200)
    expect(rect.y).toBe(200)
  })

  it('clamps the position so the content stays on screen', () => {
    const corner = { x: 1500, y: 900, width: 600, height: 900 }
    const rect = contentRectForAspect(corner, 800 / 1280, workArea)
    expect(rect.x).toBe(workArea.x + workArea.width - rect.width)
    expect(rect.y).toBe(workArea.y + workArea.height - rect.height)
  })

  it('floors a degenerate tiny window at the minimum content area', () => {
    const tiny = { x: 0, y: 25, width: 100, height: 100 }
    const rect = contentRectForAspect(tiny, 1280 / 800, workArea)
    expect(rect.width * rect.height).toBeGreaterThanOrEqual(MIN_CONTENT_AREA)
  })

  it('returns the input rect unchanged for a non-positive or non-finite aspect', () => {
    const content = { x: 0, y: 0, width: 800, height: 600 }
    expect(contentRectForAspect(content, 0, workArea)).toEqual(content)
    expect(contentRectForAspect(content, Number.NaN, workArea)).toEqual(content)
    expect(contentRectForAspect(content, Number.POSITIVE_INFINITY, workArea)).toEqual(content)
  })
})
