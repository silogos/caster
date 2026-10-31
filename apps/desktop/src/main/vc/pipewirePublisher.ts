import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { delimiter, join } from 'node:path'
import { logger } from '../log'
import type { SoftcamRegistration } from './registration'
import type { VcCamera, VcCameraBackend, VcCameraHandlers } from './softcamLibrary'

/**
 * The Linux backend of the virtual camera (ADR-007's recorded follow-up):
 * a user-space PipeWire camera node — no kernel module, no /dev/video*. The
 * system's `gst-launch-1.0` is the helper: it reads raw BGRA frames from
 * stdin (fdsrc pulls exactly one blocksize-sized buffer per frame, so no
 * framing protocol is needed — the byte stream self-synchronizes) and
 * provides them as a camera node (`pipewiresink mode=provide`,
 * `media.role=Camera`). Reach: portal-aware apps (Firefox/Chrome) yes, OBS
 * transitional, raw /dev/video* consumers no — the recorded limitation.
 * Everything above the seam (controller, proto 2, phone card, always-on,
 * no-signal idle) is untouched. Process spawning is injectable for tests;
 * the pipeline itself is verified on a Linux rig (#73's acceptance).
 */

const LOG_SCOPE = 'vc-pipewire'

/** The GStreamer binary the backend rides on — from the system, not vendored. */
const GST_LAUNCH = 'gst-launch-1.0'

/**
 * Timestamps declare 60/1 while frames arrive at the cast's real rate
 * (30/60/120) — pipewiresink's provide mode relays buffers as they come;
 * the declared rate only shapes the node's advertised caps. If a rig shows
 * pacing trouble, this is the knob (#73's on-device checklist).
 */
const DECLARED_FRAMERATE = '60/1'

/** PipeWire needs no per-machine registration — the node exists while gst runs. */
export function pipewireNoopRegistration(filesPresent: boolean): SoftcamRegistration {
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

export type HelperSpawner = (command: string, args: string[]) => HelperProcess

export interface PipewireBackendOptions {
  /** Injectable for tests; default spawns gst-launch-1.0 from the system. */
  spawnHelper?: HelperSpawner
  /** Injectable for tests; default resolves GST_LAUNCH from PATH. */
  resolveBinary?: () => string | null
}

/** Find an executable on PATH (sync — the capability gate runs at load time). */
const findOnPath = (binary: string): string | null => {
  const path = process.env['PATH'] ?? ''
  for (const dir of path.split(delimiter)) {
    if (dir === '') continue
    const candidate = join(dir, binary)
    if (existsSync(candidate)) return candidate
  }
  return null
}

export function loadPipewireBackend(options: PipewireBackendOptions = {}): VcCameraBackend {
  if (process.platform !== 'linux') {
    return { support: 'unsupported-platform', pixelFormat: 'bgra', idleNoSignal: false, createCamera: () => null }
  }
  const resolveBinary = options.resolveBinary ?? (() => findOnPath(GST_LAUNCH))
  const binary = resolveBinary()
  if (binary === null) {
    logger.warn(LOG_SCOPE, `${GST_LAUNCH} not found on PATH — install gstreamer (gst-launch-1.0) for the virtual camera`)
    return { support: 'dll-missing', pixelFormat: 'bgra', idleNoSignal: false, createCamera: () => null }
  }
  const spawnHelper: HelperSpawner =
    options.spawnHelper ?? ((command, args) => spawn(command, args, { stdio: ['pipe', 'ignore', 'pipe'] }) as unknown as HelperProcess)

  return {
    support: 'ok',
    pixelFormat: 'bgra',
    // Capture-card parity (batch B): with no stream the node publishes a
    // "No input video" still rather than disappearing or freezing.
    idleNoSignal: true,
    createCamera(width, height, handlers?: VcCameraHandlers): VcCamera | null {
      // One frame = one fdsrc buffer of exactly width*height*4 bytes (BGRA,
      // top-down — the same format the macOS pump produces).
      const blocksize = width * height * 4
      const args = [
        '-q',
        'fdsrc',
        'fd=0',
        `blocksize=${blocksize}`,
        '!',
        'rawvideoparse',
        'use-sink-caps=false',
        `format=BGRA`,
        `width=${width}`,
        `height=${height}`,
        `framerate=${DECLARED_FRAMERATE}`,
        '!',
        'pipewiresink',
        'mode=provide',
        'stream-properties=props,media.role=Camera,node.name=zero-friction-camera,node.description=Zero-Friction Camera'
      ]
      let child: HelperProcess
      try {
        child = spawnHelper(binary, args)
      } catch (error) {
        logger.warn(LOG_SCOPE, 'gst-launch failed to spawn', { error: String(error) })
        return null
      }
      let dead = false

      const died = (why: string): void => {
        if (dead) return
        dead = true
        logger.warn(LOG_SCOPE, `gst-launch died — ${why}`)
        handlers?.onDead?.()
      }
      child.on('exit', (code) => died(`exit ${code ?? 'null'}`))
      child.stderr.on('data', (chunk: Buffer) => logger.debug(LOG_SCOPE, `gst: ${chunk.toString().trim()}`))
      child.stdin.on('error', (error) => died(String(error)))

      logger.info(LOG_SCOPE, 'pipewire camera node via gst-launch', { width, height })
      return {
        sendFrame(bytes: Uint8Array): void {
          if (dead) return
          // Raw bytes only — fdsrc's blocksize framing does the rest. The
          // pipe's own backpressure is enough: Node buffers writes, and a
          // stalled gst exits (onDead) so we stop honestly.
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
