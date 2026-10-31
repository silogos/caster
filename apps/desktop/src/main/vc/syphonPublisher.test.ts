import { describe, expect, it } from 'vitest'
import { join } from 'node:path'
import { loadSyphonBackend, syphonNoopRegistration } from './syphonPublisher'

/**
 * The macOS backend (ADR-007) with the helper process faked: frames go to the
 * child's stdin as header+bytes, close() kills the child, and a dead child
 * reports through onDead so the controller can stop honestly. The real helper
 * (spawn + Syphon publish) is exercised live on the Mac.
 */

interface FakeChild {
  writes: Buffer[]
  stdinError: (error: Error) => void
  emitExit: (code: number | null) => void
  killed: boolean
  stdin: { write: (chunk: unknown) => boolean; end: () => void; on: (event: string, listener: (error: Error) => void) => void }
  on: (event: string, listener: (code: number | null) => void) => void
  kill: (signal: string) => void
  stderr: { on: (event: string, listener: (chunk: Buffer) => void) => void }
}

function fakeChild(): FakeChild {
  const writes: Buffer[] = []
  let exitListener: ((code: number | null) => void) | null = null
  let errorListener: ((error: Error) => void) | null = null
  const child: FakeChild = {
    writes,
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

/** The platform gate is faked for the duration of a test (darwin → the backend; anything else → unsupported). */
function withPlatform<T>(platform: string, fn: () => T): T {
  const real = Object.getOwnPropertyDescriptor(process, 'platform')
  Object.defineProperty(process, 'platform', { value: platform, configurable: true })
  try {
    return fn()
  } finally {
    if (real !== undefined) Object.defineProperty(process, 'platform', real)
  }
}

/** The real vendored artifacts (resources/syphon) — their presence is part of what these tests pin. */
const VENDORED_DIR = join('resources', 'syphon')

describe('syphonPublisher (macOS backend, ADR-007)', () => {
  it('reports unsupported off darwin without touching the helper', () => {
    withPlatform('linux', () => {
      const backend = loadSyphonBackend({ dir: '/irrelevant', spawnHelper: () => { throw new Error('must not spawn') } })
      expect(backend.support).toBe('unsupported-platform')
      expect(backend.createCamera(8, 4)).toBeNull()
    })
  })

  it('spawns the helper on createCamera and pipes header+frame per sendFrame', () => {
    withPlatform('darwin', () => {
      const child = fakeChild()
      const deaths: string[] = []
      const backend = loadSyphonBackend({ dir: VENDORED_DIR, spawnHelper: () => child })
      expect(backend.support).toBe('ok')
      expect(backend.pixelFormat).toBe('bgra')
      const camera = backend.createCamera(8, 4, { onDead: () => deaths.push('dead') })
      expect(camera).not.toBeNull()
      const frame = new Uint8Array(8 * 4 * 4)
      camera!.sendFrame(frame)
      camera!.sendFrame(frame)
      // Two frames → 2 × (12-byte header + frame bytes).
      expect(child.writes).toHaveLength(4)
      const header = child.writes[0]
      expect(header.readUInt32LE(0)).toBe(0x5a465256) // "ZFRV"
      expect(header.readUInt32LE(4)).toBe(8)
      expect(header.readUInt32LE(8)).toBe(4)
      expect(child.writes[1]).toHaveLength(8 * 4 * 4)
      expect(deaths).toEqual([])
    })
  })

  it('close() ends stdin and kills the child without reporting dead (intentional)', () => {
    withPlatform('darwin', () => {
      const child = fakeChild()
      const deaths: string[] = []
      const backend = loadSyphonBackend({ dir: VENDORED_DIR, spawnHelper: () => child })
      const camera = backend.createCamera(8, 4, { onDead: () => deaths.push('dead') })!
      camera.close()
      expect(child.killed).toBe(true)
      child.emitExit(0) // the kill lands — no honest-dead report for our own close
      expect(deaths).toEqual([])
      // A camera already closed ignores further frames.
      camera.sendFrame(new Uint8Array(16))
      expect(child.writes.filter((w) => w.length > 12)).toHaveLength(0)
    })
  })

  it('an unexpected helper exit reports dead exactly once (honest stop)', () => {
    withPlatform('darwin', () => {
      const child = fakeChild()
      const deaths: string[] = []
      const backend = loadSyphonBackend({ dir: VENDORED_DIR, spawnHelper: () => child })
      const camera = backend.createCamera(8, 4, { onDead: () => deaths.push('dead') })!
      child.emitExit(1)
      expect(deaths).toEqual(['dead'])
      child.stdinError(new Error('EPIPE'))
      expect(deaths).toEqual(['dead']) // already dead — once is enough
      camera.sendFrame(new Uint8Array(8 * 4 * 4)) // dropped silently
      expect(child.writes).toHaveLength(0)
    })
  })

  it('the no-op registration reports present when the backend is ok', async () => {
    expect(syphonNoopRegistration(true).filesPresent).toBe(true)
    const registration = syphonNoopRegistration(false)
    expect(registration.filesPresent).toBe(false)
    expect(await registration.isRegistered()).toBe(true)
  })
})
