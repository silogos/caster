import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'

/**
 * Feature 2026-11e: the worklet's bounded playout target — the production
 * low-latency contract (the TikTok `setAudioMaxDelayByMs` analog): silence
 * until the ring holds the target, drain, re-prime after a drained-to-empty
 * ring, and shed the oldest samples above the target (drop late, never
 * stall). target 0 must stay the unbounded ADR-012 play-on-arrival path.
 *
 * The worklet file cannot import (addModule cannot resolve ESM), so these
 * tests eval it with shimmed globals — the same file the real graph loads.
 */

interface RegisteredWorklet {
  new (): {
    port: { postMessage: (m: unknown) => void; onmessage: ((e: { data: unknown }) => void) | null }
    process: (inputs: unknown[], outputs: Float32Array[][]) => boolean
  }
}

const SAMPLE_RATE = 48_000
const QUANTUM = 128

function loadWorklet(): RegisteredWorklet {
  const registered: Record<string, RegisteredWorklet> = {}
  class ShimProcessor {
    port = { postMessage: vi.fn(), onmessage: null as ((e: { data: unknown }) => void) | null }
  }
  const code = readFileSync(new URL('./game-pcm-worklet.js', import.meta.url), 'utf8')
  new Function('AudioWorkletProcessor', 'sampleRate', 'registerProcessor', code)(
    ShimProcessor as unknown as new () => object,
    SAMPLE_RATE,
    (name: string, ctor: RegisteredWorklet) => {
      registered[name] = ctor
    }
  )
  return registered['game-pcm']
}

function makeWorklet(targetMs = 0) {
  const Worklet = loadWorklet()
  const worklet = new Worklet()
  if (targetMs > 0) worklet.port.onmessage?.({ data: { targetMs } })
  return worklet
}

/** Push one quantum of audio whose L samples are numbered `from..from+QUANTUM`. */
function pushQuantum(worklet: ReturnType<typeof makeWorklet>, from: number): void {
  const left = new Float32Array(QUANTUM)
  const right = new Float32Array(QUANTUM)
  for (let i = 0; i < QUANTUM; i++) {
    left[i] = from + i
    right[i] = from + i
  }
  worklet.port.onmessage?.({ data: { left, right } })
}

/** Run one render quantum; returns the L channel the graph would hear. */
function render(worklet: ReturnType<typeof makeWorklet>): Float32Array {
  const outL = new Float32Array(QUANTUM)
  const outR = new Float32Array(QUANTUM)
  worklet.process([], [[outL, outR]])
  return outL
}

const allSilent = (samples: Float32Array): boolean => samples.every((v) => v === 0)
const allAudible = (samples: Float32Array): boolean => samples.every((v) => v !== 0)

describe('game pcm worklet — unbounded (target 0, ADR-012)', () => {
  it('plays on arrival — no priming', () => {
    const worklet = makeWorklet()
    pushQuantum(worklet, 1) // markers 1..128 — never zero, so silence is distinguishable
    const out = render(worklet)
    expect(out[0]).toBe(1) // the first pushed sample drains immediately
    expect(allAudible(out)).toBe(true)
  })

  it('a negative targetMs is ignored', () => {
    const worklet = makeWorklet()
    worklet.port.onmessage?.({ data: { targetMs: -5 } })
    pushQuantum(worklet, 1)
    expect(render(worklet)[0]).toBe(1) // still play-on-arrival
  })
})

describe('game pcm worklet — bounded target (Feature 2026-11e)', () => {
  const TARGET_MS = 20 // 20 ms @48 kHz = 960 samples = 7.5 quanta

  it('holds silence until the ring holds the target, then drains', () => {
    const worklet = makeWorklet(TARGET_MS)
    for (let k = 0; k < 7; k++) {
      pushQuantum(worklet, k * QUANTUM) // 896 samples < 960
      expect(allSilent(render(worklet))).toBe(true)
    }
    pushQuantum(worklet, 7 * QUANTUM) // 1024 ≥ 960 — primed now
    const out = render(worklet)
    expect(allAudible(out)).toBe(true)
  })

  it('sheds the backlog above the target — the newest audio plays', () => {
    const worklet = makeWorklet(TARGET_MS)
    for (let k = 0; k < 8; k++) pushQuantum(worklet, k * QUANTUM)
    const out = render(worklet)
    // 1024 filled, target 960 — the oldest 64 samples shed, so the first
    // audible sample is #64, not #0.
    expect(out[0]).toBe(64)
    expect(allAudible(out)).toBe(true)
  })

  it('a drained-to-empty ring re-primes before audible playback resumes', () => {
    const worklet = makeWorklet(TARGET_MS)
    for (let k = 0; k < 8; k++) pushQuantum(worklet, k * QUANTUM)
    for (let k = 0; k < 8; k++) render(worklet) // drain past the end (input stopped)
    expect(allSilent(render(worklet))).toBe(true) // underrun — waiting to re-prime

    for (let k = 100; k < 108; k++) pushQuantum(worklet, k * QUANTUM) // refill
    const out = render(worklet)
    expect(allAudible(out)).toBe(true)
    expect(out[0]).toBe(100 * QUANTUM + 64) // shed again — newest audio
  })

  it('a dropped-to-target change mid-stream keeps the played audio flowing', () => {
    const worklet = makeWorklet(TARGET_MS)
    for (let k = 0; k < 8; k++) pushQuantum(worklet, k * QUANTUM)
    render(worklet) // primed + first audible quantum
    worklet.port.onmessage?.({ data: { targetMs: 0 } }) // back to play-on-arrival
    pushQuantum(worklet, 8 * QUANTUM)
    const out = render(worklet)
    expect(allAudible(out)).toBe(true) // no re-priming on target 0
  })
})
