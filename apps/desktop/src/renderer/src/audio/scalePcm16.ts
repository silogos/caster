/**
 * Feature 2026-11e: scale one raw PCM16LE interleaved chunk by a linear gain
 * (the mixer's game level) — the audio-engine path bypasses the worklet's
 * gain node, so the renderer is where volume/mute apply. Clamps to i16
 * (a 0..1 gain never clips, but rounding still clamps defensively).
 * Pure — unit-covered like routing.ts.
 */

export function scalePcm16(chunk: ArrayBuffer, gain: number): ArrayBuffer {
  const bytes = new Uint8Array(chunk.byteLength)
  const source = new DataView(chunk)
  const target = new DataView(bytes.buffer)
  const clampedGain = Number.isFinite(gain) ? Math.max(0, Math.min(1, gain)) : 0
  const sampleCount = Math.floor(chunk.byteLength / 2)
  for (let i = 0; i < sampleCount; i++) {
    const scaled = Math.round(source.getInt16(i * 2, true) * clampedGain)
    target.setInt16(i * 2, Math.max(-32768, Math.min(32767, scaled)), true)
  }
  return bytes.buffer
}
