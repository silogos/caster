import { logger } from '../log'
import type { SoftcamCamera, VcCameraBackend } from './softcamLibrary'
import type { RegistrationResult, SoftcamRegistration } from './registration'
import { vcDims } from './vcDims'
import type { VcPumpCommand } from '../../shared/types'

/**
 * The virtual camera's state machine (ADR-007) — the seam every platform
 * adapter sits behind (Windows softcam today; PipeWire/Syphon later). It owns
 * WHEN a camera exists and WHAT the phone is told; it never touches pixels.
 *
 *   unavailable ──(platform/dll unsupported)────────────── nothing to do
 *   needsRegistration ──(vc-start → UAC)──▶ available ──(vc-start)──▶ active
 *        ▲                                                        │
 *        └───────────── every exit path (vc-stop, cast gone, dispose) ─┘
 *
 * Status pushes go out as the vc-query reply AND on every change (the phone's
 * toggle is a pure function of the last vc-status — webrtc.md proto 2).
 */

const LOG_SCOPE = 'vc'

export type VcState = 'unavailable' | 'needsRegistration' | 'available' | 'active'

export interface VcStatusPush {
  available: boolean
  needsRegistration: boolean
  active: boolean
}

/** What the renderer pump needs: start publishing at these dims, or stop (shared type). */
export type PumpCommand = VcPumpCommand

export interface VirtualCameraOptions {
  /** The platform adapter (Windows softcam / macOS Syphon — ADR-007's seam). */
  backend: VcCameraBackend
  registration: SoftcamRegistration
  /** The stream's current size (camera dims are fixed at creation from it). */
  streamSize: () => { width: number; height: number } | null
  /** The signaling-layer sender — reaches the phone only on proto ≥ 2. */
  sendStatus: (status: VcStatusPush) => void
  /** Renderer pump control (the frame source; renderer owns the pixels). */
  onPumpCommand: (command: VcPumpCommand) => void
  log?: (level: 'debug' | 'info' | 'warn' | 'error', message: string, details?: Record<string, unknown>) => void
}

export class VirtualCameraController {
  private state: VcState = 'unavailable'
  private camera: SoftcamCamera | null = null
  private cameraBytes = 0
  private initPromise: Promise<void> | null = null
  /** Guards re-entrant starts while the UAC registration is in flight. */
  private starting = false
  private readonly options: VirtualCameraOptions
  private readonly log: NonNullable<VirtualCameraOptions['log']>

  constructor(options: VirtualCameraOptions) {
    this.options = options
    this.log = options.log ?? ((level, message, details) => logger[level](LOG_SCOPE, message, details))
  }

  /** The active backend's pixel format (for frame-size validation); null while inactive. */
  get activePixelFormat(): 'bgr24' | 'bgra' | null {
    return this.state === 'active' ? this.options.backend.pixelFormat : null
  }

  /**
   * Resolves the initial state (platform support + registration detection).
   * Idempotent; vc-* handlers await it so an early query can't race detection.
   */
  ready(): Promise<void> {
    if (this.initPromise === null) {
      this.initPromise = this.detectInitialState()
    }
    return this.initPromise
  }

  private async detectInitialState(): Promise<void> {
    if (this.options.backend.support !== 'ok' || !this.options.registration.filesPresent) {
      this.state = 'unavailable'
      this.log('info', `virtual camera unavailable on this desktop (support=${this.options.backend.support})`)
      return
    }
    this.state = (await this.options.registration.isRegistered()) ? 'available' : 'needsRegistration'
    this.log('info', `virtual camera ${this.state}`)
  }

  private status(): VcStatusPush {
    return {
      available: this.state !== 'unavailable',
      needsRegistration: this.state === 'needsRegistration',
      active: this.state === 'active'
    }
  }

  private pushStatus(): void {
    this.options.sendStatus(this.status())
  }

  /** Signaling: the phone asked (cast start / reconnect). */
  async onVcQuery(): Promise<void> {
    await this.ready()
    this.pushStatus()
  }

  /** Signaling: the phone's toggle turned on. */
  async onVcStart(): Promise<void> {
    await this.ready()
    if (this.state === 'active' || this.starting) return
    if (this.state === 'unavailable') {
      this.pushStatus()
      return
    }
    if (this.state === 'needsRegistration') {
      this.starting = true
      try {
        const result: RegistrationResult = await this.options.registration.register()
        if (result !== 'registered') {
          // Declined UAC is a user decision, failed is an honest failure — both
          // keep the one-time-setup state; the phone's copy says exactly that.
          this.log('info', `registration ${result} — virtual camera stays needsRegistration`)
          this.pushStatus()
          return
        }
        this.state = 'available'
        this.log('info', 'softcam registered — creating the camera')
      } finally {
        this.starting = false
      }
    }
    this.startCamera()
  }

  private startCamera(): void {
    const size = this.options.streamSize()
    if (size === null) {
      // No stream yet — the toggle is cast-scoped, so this is rare (a race
      // between cast start and vc-start); the phone re-queries on the next
      // session-info-driven UI update.
      this.log('warn', 'vc-start with no stream size — staying available')
      this.pushStatus()
      return
    }
    // The camera's dims are fixed at creation from the stream's current size
    // (vcDims: ≤1920×1080, multiples of 4); rotation afterwards letterboxes
    // inside them — re-creating the camera would glitch consumers mid-use.
    const dims = vcDims(size.width, size.height)
    const camera = this.options.backend.createCamera(dims.width, dims.height, {
      // The backend died under us (helper crash, EPIPE) — stop honestly; the
      // phone's toggle follows the vc-status push.
      onDead: () => {
        if (this.state === 'active') this.teardown('backend-dead')
      }
    })
    if (camera === null) {
      this.log('warn', 'camera creation failed — staying available')
      this.pushStatus()
      return
    }
    this.camera = camera
    this.cameraBytes = dims.width * dims.height * (this.options.backend.pixelFormat === 'bgr24' ? 3 : 4)
    this.state = 'active'
    this.log('info', 'virtual camera active', { width: dims.width, height: dims.height })
    this.options.onPumpCommand({ active: true, width: dims.width, height: dims.height, pixelFormat: this.options.backend.pixelFormat })
    this.pushStatus()
  }

  /** Signaling: the phone's toggle turned off. */
  async onVcStop(): Promise<void> {
    await this.ready()
    if (this.state !== 'active') return
    this.teardown('vc-stop')
  }

  /** A frame from the renderer pump (BGR24, the camera's exact dims). */
  onFrame(bytes: Uint8Array): void {
    if (this.camera === null) return
    if (bytes.byteLength !== this.cameraBytes) {
      // Never feed a mismatched buffer to the native memcpy — drop with the
      // honest reason; the next frame is a moment away.
      this.log('warn', 'frame size mismatch — dropped', { expected: this.cameraBytes, got: bytes.byteLength })
      return
    }
    this.camera.sendFrame(bytes)
  }

  /** The cast is gone (end, mobile gone, bye, waiting) — the camera dies with it. */
  onCastGone(): void {
    if (this.state === 'active') this.teardown('cast-gone')
  }

  /** App quit — delete the camera without protocol chatter (socket may be gone). */
  dispose(): void {
    if (this.camera !== null) this.teardown('dispose', { push: false })
  }

  private teardown(reason: string, options: { push?: boolean } = { push: true }): void {
    if (this.camera !== null) {
      this.camera.close()
      this.camera = null
      this.cameraBytes = 0
    }
    this.state = 'available'
    this.options.onPumpCommand({ active: false, width: null, height: null })
    this.log('info', `virtual camera stopped (${reason})`)
    if (options.push) this.pushStatus()
  }
}
