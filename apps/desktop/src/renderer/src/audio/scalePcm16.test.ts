import { describe, expect, it } from 'vitest'
import { scalePcm16 } from './scalePcm16'

/** The engine path bypasses the worklet's gain node — the renderer scales. */
describe('scalePcm16', () => {
  function chunkOf(samples: number[]): ArrayBuffer {
    const buffer = new ArrayBuffer(samples.length * 2)
    const view = new DataView(buffer)
    samples.forEach((sample, i) => view.setInt16(i * 2, sample, true))
    return buffer
  }

  function samplesOf(chunk: ArrayBuffer): number[] {
    const view = new DataView(chunk)
    return Array.from({ length: view.byteLength / 2 }, (_, i) => view.getInt16(i * 2, true))
  }

  it('unity gain round-trips the samples', () => {
    expect(samplesOf(scalePcm16(chunkOf([100, -200, 32767, -32768]), 1))).toEqual([100, -200, 32767, -32768])
  })

  it('scales and rounds toward the nearest sample', () => {
    expect(samplesOf(scalePcm16(chunkOf([100, -100]), 0.5))).toEqual([50, -50])
    expect(samplesOf(scalePcm16(chunkOf([101]), 0.5))).toEqual([51]) // 50.5 → 51
  })

  it('zero gain silences the chunk', () => {
    expect(samplesOf(scalePcm16(chunkOf([12345, -12345]), 0))).toEqual([0, 0])
  })

  it('clamps defensively against overflow', () => {
    expect(samplesOf(scalePcm16(chunkOf([32767]), 1))).toEqual([32767])
  })

  it('a non-finite gain is treated as zero', () => {
    expect(samplesOf(scalePcm16(chunkOf([500]), Number.NaN))).toEqual([0])
  })
})
