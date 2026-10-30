import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { logger } from '../log'

/**
 * Thin koffi FFI adapter for softcam.dll (ADR-007): the only native dependency
 * — a prebuilt MIT DirectShow filter with a 3-function C API. Loaded lazily and
 * only on win32-x64 (no arm64 build exists), so macOS dev and tests never touch
 * koffi or the DLL. The camera owns no logic; the state machine is virtualCamera.ts.
 *
 * Frame format (softcam source, SenderAPI/FrameBuffer): BGR, 3 bytes/pixel,
 * tightly packed, top-left row first, width×height exactly as created.
 */

const LOG_SCOPE = 'vc-softcam'

/** softcam.h: "reference framerate" — 0 = deliver each frame immediately (real-time source), no internal pacing sleep. */
export const SOFTCAM_FRAMERATE_UNPACED = 0

export type SoftcamSupport = 'ok' | 'unsupported-platform' | 'dll-missing'

/**
 * The pixel format a platform backend consumes (ADR-007) — it rides the pump
 * command so the renderer produces exactly what the camera wants:
 * Windows/softcam = BGR 24-bit packed; macOS/Syphon = BGRA 4-byte (a texture).
 */
export type VcPixelFormat = 'bgr24' | 'bgra'

export interface VcCamera {
  /** Publishes one frame in the backend's format (see VcCameraBackend.pixelFormat). */
  sendFrame(bytes: Uint8Array): void
  /** Tears the camera down — must always run on every exit path. */
  close(): void
}

export interface VcCameraBackend {
  support: SoftcamSupport
  /** What the renderer must produce for sendFrame. */
  pixelFormat: VcPixelFormat
  /** Null when the platform is unsupported, files are missing, or creation failed. */
  createCamera(width: number, height: number, handlers?: VcCameraHandlers): VcCamera | null
}

/** Backend-side failure signals (e.g. the macOS helper process dying). */
export interface VcCameraHandlers {
  onDead?: () => void
}

export interface SoftcamLibrary extends VcCameraBackend {}
export interface SoftcamCamera extends VcCamera {}

interface KoffiExports {
  scCreateCamera(width: number, height: number, framerate: number): unknown
  scDeleteCamera(camera: unknown): void
  scSendFrame(camera: unknown, imageBits: Uint8Array): void
}

export function loadSoftcam(dllDir: string): SoftcamLibrary {
  // softcam.dll ships x64 builds only; other platforms (and win-arm64) report
  // honest unavailability — the phone hides its toggle.
  if (process.platform !== 'win32' || process.arch !== 'x64') {
    return { support: 'unsupported-platform', pixelFormat: 'bgr24', createCamera: () => null }
  }
  const dllPath = join(dllDir, 'softcam.dll')
  if (!existsSync(dllPath)) {
    logger.warn(LOG_SCOPE, `softcam.dll not found at ${dllPath}`)
    return { support: 'dll-missing', pixelFormat: 'bgr24', createCamera: () => null }
  }
  try {
    // Lazy require: nothing outside this win32 path loads koffi (macOS dev, tests).
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const koffi = require('koffi') as typeof import('koffi')
    // Registers the opaque camera handle type the signatures below name.
    koffi.pointer('scCamera', koffi.opaque())
    const lib = koffi.load(dllPath)
    const exports: KoffiExports = {
      // Unpaced (framerate 0): frames are delivered as they arrive — the cast
      // IS the clock (ADR-007's no-cap rule); a non-zero rate would sleep inside
      // scSendFrame to pace delivery, blocking the caller.
      scCreateCamera: lib.func('scCamera *scCreateCamera(int width, int height, float framerate)'),
      scDeleteCamera: lib.func('void scDeleteCamera(scCamera *camera)'),
      scSendFrame: lib.func('void scSendFrame(scCamera *camera, const void *image_bits)')
    }
    return {
      support: 'ok',
      pixelFormat: 'bgr24',
      createCamera(width, height) {
        const handle = exports.scCreateCamera(width, height, SOFTCAM_FRAMERATE_UNPACED)
        if (handle === null || handle === undefined) {
          logger.warn(LOG_SCOPE, 'scCreateCamera returned null (another instance exists?)')
          return null
        }
        return {
          sendFrame: (bgr) => exports.scSendFrame(handle, bgr),
          close: () => exports.scDeleteCamera(handle)
        }
      }
    }
  } catch (error) {
    logger.warn(LOG_SCOPE, `softcam.dll could not be loaded — reporting unavailable`, { error: String(error) })
    return { support: 'dll-missing', pixelFormat: 'bgr24', createCamera: () => null }
  }
}
