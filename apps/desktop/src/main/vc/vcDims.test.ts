import { describe, expect, it } from 'vitest'
import { vcDims, VC_DIM_MULTIPLE } from './vcDims'

describe('vcDims (ADR-007: cap 1920×1080, multiples of 4, aspect preserved)', () => {
  it('passes through a small landscape stream unchanged (already multiples of 4)', () => {
    expect(vcDims(1280, 720)).toEqual({ width: 1280, height: 720 })
  })

  it('caps a 4K stream to 1080p preserving the aspect', () => {
    expect(vcDims(2560, 1440)).toEqual({ width: 1920, height: 1080 })
  })

  it('caps a tall portrait stream by height', () => {
    // 800×1280 portrait: height is the long edge → scale = 1080/1280; exact
    // scaled width 675 rounds to the closest multiple of 4 = 676.
    expect(vcDims(800, 1280)).toEqual({ width: 676, height: 1080 })
  })

  it('floors non-multiples of four after scaling (softcam constraint)', () => {
    // 1922 wide stays under the cap but must round to a multiple of 4.
    const dims = vcDims(1922, 1080)
    expect(dims.width % VC_DIM_MULTIPLE).toBe(0)
    expect(dims.height % VC_DIM_MULTIPLE).toBe(0)
    expect(dims).toEqual({ width: 1920, height: 1080 })
  })

  it('never returns a zero dimension for a tiny stream', () => {
    expect(vcDims(5, 3)).toEqual({ width: 4, height: 4 })
  })

  it('refuses a non-positive stream size', () => {
    expect(() => vcDims(0, 720)).toThrow()
    expect(() => vcDims(1280, -1)).toThrow()
  })
})
