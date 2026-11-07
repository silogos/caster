/**
 * ADR-012: the game-audio PCM worklet — a stereo ring the main thread feeds
 * with Float32 frames (from the mobile's "game-audio" DataChannel), rendered
 * at the graph's 128-frame quanta; underruns play silence — a dropped sliver
 * beats a stalled stream. Loaded as a real asset (Blob URLs are unreliable
 * for worklets in file:// renderers — found live: AbortError on addModule).
 *
 * Feature 2026-11e adds the bounded playout target: a `{targetMs}` port
 * message (0 = the default, play on arrival) puts the ring under the
 * production low-latency contract (the TikTok `setAudioMaxDelayByMs`
 * analog) — silence until the ring holds the target, then drain; a
 * drained-to-empty ring re-primes; a backlog above the target sheds the
 * OLDEST samples so the played audio stays near the newest arrival
 * (drop late, never stall). No imports here — addModule cannot resolve
 * them; the depth math stays inline.
 */
class GamePcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super()
    this.capacity = 16384
    this.left = new Float32Array(this.capacity)
    this.right = new Float32Array(this.capacity)
    this.read = 0
    this.filled = 0
    // Bounded playout (Feature 2026-11e): 0 = play on arrival (ADR-012).
    // > 0 = prime to this many samples before draining.
    this.targetSamples = 0
    this.primed = true
    // Ring-depth telemetry (ADR-013 follow-up): one report per ~1 s, the raw
    // material for tuning a bounded playout target. Numbers only — no samples.
    this.quantaPerSecond = Math.round(sampleRate / 128)
    this.quanta = 0
    this.port.onmessage = (event) => {
      const data = event.data
      if (typeof data?.targetMs === 'number') {
        if (data.targetMs < 0) return // malformed — the current target stands
        this.targetSamples = Math.round((data.targetMs * sampleRate) / 1000)
        this.primed = this.filled >= this.targetSamples
        return
      }
      const { left, right } = data
      const n = Math.min(left.length, this.capacity - this.filled)
      const tail = (this.read + this.filled) % this.capacity
      for (let i = 0; i < n; i++) {
        this.left[(tail + i) % this.capacity] = left[i]
        this.right[(tail + i) % this.capacity] = right[i]
      }
      this.filled += n
    }
  }

  process(inputs, outputs) {
    const out = outputs[0]
    const outL = out[0]
    const outR = out.length > 1 ? out[1] : out[0]
    if (this.targetSamples > 0 && !this.primed && this.filled < this.targetSamples) {
      // Under the target depth — play silence rather than draining early.
      for (let i = 0; i < outL.length; i++) {
        outL[i] = 0
        outR[i] = 0
      }
    } else {
      this.primed = true
      if (this.targetSamples > 0) {
        // Shed the backlog above the target — oldest first.
        const excess = this.filled - this.targetSamples
        if (excess > 0) {
          this.read = (this.read + excess) % this.capacity
          this.filled = this.targetSamples
        }
      }
      for (let i = 0; i < outL.length; i++) {
        if (this.filled > 0) {
          outL[i] = this.left[this.read]
          outR[i] = this.right[this.read]
          this.read = (this.read + 1) % this.capacity
          this.filled -= 1
        } else {
          outL[i] = 0
          outR[i] = 0
          if (this.targetSamples > 0) this.primed = false
        }
      }
    }
    if (++this.quanta >= this.quantaPerSecond) {
      this.quanta = 0
      this.port.postMessage({ depthMs: Math.round((this.filled * 1000) / sampleRate) })
    }
    return true
  }
}

registerProcessor('game-pcm', GamePcmWorklet)
