/**
 * Pure dimension rule for the virtual camera (ADR-007): softcam requires
 * width/height that are positive multiples of four, and the camera output is
 * capped at 1080p-class sizes. The stream's size at camera creation fixes the
 * camera; later rotation/quality steps letterbox inside it (a dimension change
 * would glitch camera consumers mid-use).
 */

export const VC_MAX_WIDTH = 1920
export const VC_MAX_HEIGHT = 1080
/** softcam's constraint (softcam.h: width and height must be multiples of four). */
export const VC_DIM_MULTIPLE = 4

export interface VcDims {
  width: number
  height: number
}

/** Largest dims ≤ the cap preserving the aspect, then floored to multiples of 4 (min 4). */
export function vcDims(streamWidth: number, streamHeight: number): VcDims {
  if (streamWidth <= 0 || streamHeight <= 0) {
    throw new Error(`vcDims needs positive stream size, got ${streamWidth}x${streamHeight}`)
  }
  const scale = Math.min(VC_MAX_WIDTH / streamWidth, VC_MAX_HEIGHT / streamHeight, 1)
  // Round (not floor) the scaled value to the multiple of 4 — closest legal
  // size to the true aspect; a ≤2 px drift is hidden by the letterbox draw.
  const round4 = (value: number): number =>
    Math.max(VC_DIM_MULTIPLE, Math.round((value * scale) / VC_DIM_MULTIPLE) * VC_DIM_MULTIPLE)
  return { width: round4(streamWidth), height: round4(streamHeight) }
}
