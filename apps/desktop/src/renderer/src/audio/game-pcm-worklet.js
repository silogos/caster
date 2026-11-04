/**
 * ADR-012: the game-audio PCM worklet — a stereo ring the main thread feeds
 * with Float32 frames (from the mobile's "game-audio" DataChannel), rendered
 * at the graph's 128-frame quanta; underruns play silence — a dropped sliver
 * beats a stalled stream. Loaded as a real asset (Blob URLs are unreliable
 * for worklets in file:// renderers — found live: AbortError on addModule).
 */
class GamePcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super()
    this.capacity = 16384
    this.left = new Float32Array(this.capacity)
    this.right = new Float32Array(this.capacity)
    this.read = 0
    this.filled = 0
    this.port.onmessage = (event) => {
      const { left, right } = event.data
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
    for (let i = 0; i < outL.length; i++) {
      if (this.filled > 0) {
        outL[i] = this.left[this.read]
        outR[i] = this.right[this.read]
        this.read = (this.read + 1) % this.capacity
        this.filled -= 1
      } else {
        outL[i] = 0
        outR[i] = 0
      }
    }
    return true
  }
}

registerProcessor('game-pcm', GamePcmWorklet)
