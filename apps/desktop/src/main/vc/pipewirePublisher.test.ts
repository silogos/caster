import { describe, expect, it } from 'vitest'
import { loadPipewireBackend, pipewireNoopRegistration } from './pipewirePublisher'

/**
 * The Linux backend (ADR-007 follow-up) with the helper process faked: raw
 * BGRA frames go to gst-launch's stdin unframed (fdsrc's blocksize does the
 * framing), the pipeline advertises a camera node, close() kills the child,
 * and a dead child reports through onDead so the controller stops honestly.
 * The real pipeline (gst-launch + pipewiresink) is exercised on a Linux rig
 * — issue #73's on-device checklist.
 */

interface FakeChild {
  writes: Buffer[]
  spawnArgs: string[]
  stdinError: (error: Error) => void
  emitExit: (code: number | null) => void
  killed: boolean
  stdin: { write: (chunk: unknown) => boolean; end: () => void; on: (event: string, listener: (error: Error) => void) => void }
  on: (event: string, listener: (code: number | null) => void) => void
  kill: (signal: string) => void
  stderr: { on: (event: string, listener: (chunk: Buffer) => void) => void }
}

function fakeChild(spawnArgs: string[] = []): FakeChild {
  const writes: Buffer[] = []
  let exitListener: ((code: number | null) => void) | null = null
  let errorListener: ((error: Error) => void) | null = null
  const child: FakeChild = {
    writes,
    spawnArgs,
    killed: false,
    emitExit: (code) => exitListener?.(code),
    stdinError: (error) => errorListener?.(error),
    stdin: {
      write: (chunk) => {
        writes.push(Buffer.from(chunk as Uint8Array))
        return true
      },
      end: () => writes.push(Buffer.from('<end>')),
      on: (_event, listener) => {
        errorListener = listener
      }
    },
    on: (_event, listener) => {
      exitListener = listener
    },
    kill: () => {
      child.killed = true
    },
    stderr: { on: () => undefined }
  }
  return child
}

/** The platform gate is faked for the duration of a test (linux → the backend; anything else → unsupported). */
function withPlatform<T>(platform: string, fn: () => T): T {
  const real = Object.getOwnPropertyDescriptor(process, 'platform')
  Object.defineProperty(process, 'platform', { value: platform, configurable: true })
  try {
    return fn()
  } finally {
    if (real !== undefined) Object.defineProperty(process, 'platform', real)
  }
}

describe('pipewirePublisher (Linux backend, ADR-007 follow-up)', () => {
  it('reports unsupported off linux without spawning anything', () => {
    withPlatform('darwin', () => {
      const backend = loadPipewireBackend({
        resolveBinary: () => '/usr/bin/gst-launch-1.0',
        spawnHelper: () => {
          throw new Error('must not spawn')
        }
      })
      expect(backend.support).toBe('unsupported-platform')
      expect(backend.createCamera(8, 4)).toBeNull()
    })
  })

  it('reports dll-missing when gst-launch-1.0 is not on PATH (honest availability)', () => {
    withPlatform('linux', () => {
      const backend = loadPipewireBackend({ resolveBinary: () => null })
      expect(backend.support).toBe('dll-missing')
      expect(backend.createCamera(8, 4)).toBeNull()
    })
  })

  it('spawns gst-launch with the camera pipeline and pipes RAW frames (no framing header)', () => {
    withPlatform('linux', () => {
      let spawned: FakeChild | null = null
      const backend = loadPipewireBackend({
        resolveBinary: () => '/usr/bin/gst-launch-1.0',
        spawnHelper: (_command, args) => {
          const child = fakeChild(args)
          spawned = child
          return child
        }
      })
      expect(backend.support).toBe('ok')
      expect(backend.pixelFormat).toBe('bgra')
      expect(backend.idleNoSignal).toBe(true)
      const deaths: string[] = []
      const camera = backend.createCamera(8, 4, { onDead: () => deaths.push('dead') })
      expect(camera).not.toBeNull()
      // The pipeline: fdsrc blocksize = one BGRA frame → rawvideoparse BGRA
      // dims → pipewiresink providing a Camera-role node with our name.
      const args = spawned!.spawnArgs
      expect(args.join(' ')).toContain('fdsrc')
      expect(args).toContain('blocksize=128') // 8×4×4
      expect(args.join(' ')).toContain('format=BGRA')
      expect(args).toContain('width=8')
      expect(args).toContain('height=4')
      expect(args.join(' ')).toContain('pipewiresink')
      expect(args).toContain('mode=provide')
      expect(args.join(' ')).toContain('media.role=Camera')
      expect(args.join(' ')).toContain('Zero-Friction Camera')
      // Raw bytes only — fdsrc's blocksize framing replaces syphon's header.
      const frame = new Uint8Array(128)
      camera!.sendFrame(frame)
      camera!.sendFrame(frame)
      expect(spawned!.writes).toHaveLength(2)
      expect(spawned!.writes[0]).toHaveLength(128)
      expect(deaths).toEqual([])
    })
  })

  it('close() ends stdin and kills the child without reporting dead (intentional)', () => {
    withPlatform('linux', () => {
      const child = fakeChild()
      const deaths: string[] = []
      const backend = loadPipewireBackend({ resolveBinary: () => '/usr/bin/gst-launch-1.0', spawnHelper: () => child })
      const camera = backend.createCamera(8, 4, { onDead: () => deaths.push('dead') })!
      camera.close()
      expect(child.killed).toBe(true)
      child.emitExit(0)
      expect(deaths).toEqual([])
      camera.sendFrame(new Uint8Array(16))
      // Only the <end> marker from stdin.end() — no frame bytes after close.
      expect(child.writes.filter((w) => w.length === 16)).toHaveLength(0)
    })
  })

  it('an unexpected gst exit reports dead exactly once (honest stop)', () => {
    withPlatform('linux', () => {
      const child = fakeChild()
      const deaths: string[] = []
      const backend = loadPipewireBackend({ resolveBinary: () => '/usr/bin/gst-launch-1.0', spawnHelper: () => child })
      const camera = backend.createCamera(8, 4, { onDead: () => deaths.push('dead') })!
      child.emitExit(1)
      expect(deaths).toEqual(['dead'])
      child.stdinError(new Error('EPIPE'))
      expect(deaths).toEqual(['dead'])
      camera.sendFrame(new Uint8Array(128))
      expect(child.writes).toHaveLength(0)
    })
  })

  it('a spawn failure returns null (the controller reports unavailable)', () => {
    withPlatform('linux', () => {
      const backend = loadPipewireBackend({
        resolveBinary: () => '/usr/bin/gst-launch-1.0',
        spawnHelper: () => {
          throw new Error('spawn EACCES')
        }
      })
      expect(backend.createCamera(8, 4)).toBeNull()
    })
  })

  it('the no-op registration reports present when the backend is ok', async () => {
    expect(pipewireNoopRegistration(true).filesPresent).toBe(true)
    const registration = pipewireNoopRegistration(false)
    expect(registration.filesPresent).toBe(false)
    expect(await registration.isRegistered()).toBe(true)
  })
})
