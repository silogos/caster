export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

/**
 * The floor for a reshaped cast window's content area — keeps a tiny window
 * from collapsing to a sliver (matches the cast-state minimum window size).
 */
export const MIN_CONTENT_AREA = 320 * 240

const clamp = (value: number, low: number, high: number): number =>
  Math.min(Math.max(value, low), Math.max(low, high))

/**
 * The cast window's content rect for a stream's aspect (window behavior — the
 * one control the receiver owns besides volume, overview.md). The window keeps
 * its current content AREA (the size feel the user chose), reshaped to the
 * stream's aspect so the letterboxed video fills it edge-to-edge in every
 * orientation, and clamped to the display's work area. The origin follows the
 * current position, clamped so the content stays on screen.
 *
 * Pure math, no Electron imports — unit-tested on plain Node (the window that
 * applies it is a thin wrapper in window.ts).
 */
export function contentRectForAspect(content: Rect, aspect: number, workArea: Rect): Rect {
  if (!(aspect > 0) || !Number.isFinite(aspect)) return content
  const area = Math.max(content.width * content.height, MIN_CONTENT_AREA)
  let width = Math.round(Math.sqrt(area * aspect))
  let height = Math.round(width / aspect)
  if (height > workArea.height) {
    height = workArea.height
    width = Math.round(height * aspect)
  }
  if (width > workArea.width) {
    width = workArea.width
    height = Math.round(width / aspect)
  }
  const x = clamp(content.x, workArea.x, workArea.x + workArea.width - width)
  const y = clamp(content.y, workArea.y, workArea.y + workArea.height - height)
  return { x, y, width, height }
}
