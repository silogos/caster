/**
 * The renderer-side virtual camera pump (ADR-007): one decoded frame in, one
 * packed BGR24 buffer out — 1:1, no cap, driven by the stream itself. The
 * frame SOURCE (MediaStreamTrackProcessor — decode-side, works minimized) and
 * the CANVAS (WebGL byte-packing) are injected so this orchestration is
 * node-testable; production wiring lives in main.ts.
 *
 * Responsibilities kept here: start/stop lifecycle, the reuse-scratch-buffer
 * read, VideoFrame.close() discipline (frames must not queue in the GC), and
 * bounded context-loss recovery with an honest failure report.
 */

export interface PumpFrame {
  /** Returned to the source's pool — always called after the frame is consumed. */
  close(): void
}

/** Decode-side frame source — production: MediaStreamTrackProcessor reader loop. */
export interface PumpFrameSource {
  start(onFrame: (frame: PumpFrame) => void): void
  stop(): void
}

/** Fixed-size camera canvas — production: webglBgrCanvas (GPU scale + byte pack/swap). */
export interface PumpCanvas {
  drawFrame(frame: PumpFrame): void
  readPixels(out: Uint8Array): void
  isLost(): boolean
}

/** The backend's pixel format (ADR-007): 'bgr24' packs 3 bytes/px, 'bgra' keeps 4. */
export type PumpFormat = 'bgr24' | 'bgra'

export function frameBytes(width: number, height: number, format: PumpFormat): number {
  return width * height * (format === 'bgr24' ? 3 : 4)
}

export interface FramePumpDeps {
  createSource: (track: unknown) => PumpFrameSource
  createCanvas: (width: number, height: number, format: PumpFormat) => PumpCanvas
  /** The IPC hop to the main process (bytes are copied by structured clone). */
  send: (bytes: Uint8Array, width: number, height: number) => void
  /** Bounded recovery gave up — the main process must stop the camera honestly. */
  reportFailure: (reason: string) => void
  /** Context-loss re-creations before giving up (ADR-007: recreate, then honest stop). */
  maxCanvasRecoveries?: number
  log?: (level: 'debug' | 'info' | 'warn' | 'error', message: string, details?: Record<string, unknown>) => void
}

const DEFAULT_MAX_CANVAS_RECOVERIES = 3

export class FramePump {
  private source: PumpFrameSource | null = null
  private canvas: PumpCanvas | null = null
  private scratch: Uint8Array | null = null
  private dims: { width: number; height: number; format: PumpFormat } | null = null
  private recoveries = 0
  private readonly deps: FramePumpDeps
  private readonly maxRecoveries: number
  private readonly log: NonNullable<FramePumpDeps['log']>

  constructor(deps: FramePumpDeps) {
    this.deps = deps
    this.maxRecoveries = deps.maxCanvasRecoveries ?? DEFAULT_MAX_CANVAS_RECOVERIES
    this.log = deps.log ?? (() => undefined)
  }

  get active(): boolean {
    return this.source !== null
  }

  /** Begin pumping `track` into a camera of the fixed `width`×`height` in `format`. */
  start(track: unknown, width: number, height: number, format: PumpFormat): void {
    this.stop()
    this.dims = { width, height, format }
    this.scratch = new Uint8Array(frameBytes(width, height, format))
    this.canvas = this.deps.createCanvas(width, height, format)
    this.recoveries = 0
    const source = this.deps.createSource(track)
    this.source = source
    this.log('info', 'vc pump started', { width, height, format })
    source.start((frame) => this.onFrame(frame))
  }

  /** Stop pumping — the camera itself is the main process's call. */
  stop(): void {
    if (this.source !== null) {
      this.source.stop()
      this.source = null
    }
    this.canvas = null
    this.scratch = null
    this.dims = null
  }

  private onFrame(frame: PumpFrame): void {
    try {
      if (this.canvas === null || this.scratch === null || this.dims === null) {
        return // stopped between queue and delivery — just drop it
      }
      if (this.canvas.isLost()) {
        if (!this.recoverCanvas()) return
      }
      this.canvas.drawFrame(frame)
      this.canvas.readPixels(this.scratch)
      this.deps.send(this.scratch, this.dims.width, this.dims.height)
    } finally {
      frame.close()
    }
  }

  /** Context loss: recreate the canvas (same dims/format); give up honestly after N. */
  private recoverCanvas(): boolean {
    if (this.dims === null) return false
    if (this.recoveries >= this.maxRecoveries) {
      this.log('error', 'vc pump canvas lost too often — stopping honestly')
      this.stop()
      this.deps.reportFailure('canvas-lost')
      return false
    }
    this.recoveries += 1
    this.canvas = this.deps.createCanvas(this.dims.width, this.dims.height, this.dims.format)
    this.log('warn', 'vc pump canvas recovered after context loss', { attempt: this.recoveries })
    return true
  }
}
