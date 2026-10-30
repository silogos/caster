import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { logger } from '../log'
import type { SoftcamRegistration } from './registration'
import type { VcCamera, VcCameraBackend, VcCameraHandlers } from './softcamLibrary'

/**
 * The macOS backend of the virtual camera (ADR-007): a Syphon publisher —
 * OBS (built-in Syphon Client source) and VJ apps on the same Mac see the
 * feed; call apps (Zoom/Meet/Discord) do not — the recorded macOS limitation,
 * with CMIO staying the open door. No Apple entitlement, no registration:
 * the "camera" is our own helper process (`syphon-helper`, vendored under
 * resources/syphon with the BSD-3 Syphon.framework) spawned per vc-start and
 * fed raw BGRA frames over stdin; it uploads them to a Metal texture and
 * publishes via Syphon. Process spawning is injectable for tests.
 */

const LOG_SCOPE = 'vc-syphon'
/** Frame header on the helper's stdin: magic "ZFRV", width, height (little-endian). */
const FRAME_MAGIC = 0x5a465256

/** Syphon needs no per-machine registration — the helper runs on demand. */
export function syphonNoopRegistration(filesPresent: boolean): SoftcamRegistration {
  return {
    filesPresent,
    isRegistered: async () => true,
    register: async () => 'registered'
  } as unknown as SoftcamRegistration
}

/** The helper process surface this module uses — fakes in tests, real spawn in production. */
export interface HelperProcess {
  stdin: {
    write(chunk: Uint8Array | string): boolean
    end(): void
    on(event: 'error', listener: (error: Error) => void): void
  }
  on(event: 'exit', listener: (code: number | null) => void): void
  stderr: { on(event: 'data', listener: (chunk: Buffer) => void): void }
  kill(signal?: string): void
}

export type HelperSpawner = (helperPath: string) => HelperProcess

export interface SyphonBackendOptions {
  /** Directory holding syphon-helper + Syphon.framework. */
  dir: string
  /** Injectable for tests; default spawns the real helper. */
  spawnHelper?: HelperSpawner
}

export function loadSyphonBackend(options: SyphonBackendOptions): VcCameraBackend {
  if (process.platform !== 'darwin') {
    return { support: 'unsupported-platform', pixelFormat: 'bgra', createCamera: () => null }
  }
  const helperPath = join(options.dir, 'syphon-helper')
  const frameworkPresent = existsSync(join(options.dir, 'Syphon.framework', 'Syphon'))
  if (!existsSync(helperPath) || !frameworkPresent) {
    logger.warn(LOG_SCOPE, `syphon helper/framework not found under ${options.dir}`)
    return { support: 'dll-missing', pixelFormat: 'bgra', createCamera: () => null }
  }
  const spawnHelper: HelperSpawner =
    options.spawnHelper ??
    ((path) => spawn(path, [], { stdio: ['pipe', 'ignore', 'pipe'] }) as unknown as HelperProcess)

  return {
    support: 'ok',
    pixelFormat: 'bgra',
    createCamera(width, height, handlers?: VcCameraHandlers): VcCamera | null {
      let child: HelperProcess
      try {
        child = spawnHelper(helperPath)
      } catch (error) {
        logger.warn(LOG_SCOPE, 'helper failed to spawn', { error: String(error) })
        return null
      }
      let dead = false
      const header = Buffer.alloc(12)
      header.writeUInt32LE(FRAME_MAGIC, 0)
      header.writeUInt32LE(width, 4)
      header.writeUInt32LE(height, 8)

      const died = (why: string): void => {
        if (dead) return
        dead = true
        logger.warn(LOG_SCOPE, `helper died — ${why}`)
        handlers?.onDead?.()
      }
      child.on('exit', (code) => died(`exit ${code ?? 'null'}`))
      child.stderr.on('data', (chunk: Buffer) => logger.debug(LOG_SCOPE, `helper: ${chunk.toString().trim()}`))
      child.stdin.on('error', (error) => died(String(error)))

      logger.info(LOG_SCOPE, 'syphon publishing via helper', { width, height })
      return {
        sendFrame(bytes: Uint8Array): void {
          if (dead) return
          // The pipe's own backpressure is enough: Node buffers writes, and the
          // helper drains at GPU speed; a stalled helper exits and we stop.
          child.stdin.write(header)
          child.stdin.write(bytes)
        },
        close(): void {
          if (dead) return
          dead = true
          child.stdin.end()
          child.kill('SIGTERM')
        }
      }
    }
  }
}
