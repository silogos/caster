import { afterEach, describe, expect, it, vi } from 'vitest'
import { EventEmitter } from 'node:events'
import type { spawn } from 'node:child_process'
import { AudioEngine } from './audioEngine'

/**
 * Feature 2026-11e: the sidecar's lifecycle owner. The engine is a plain
 * PCM pipe — these tests pin the lifecycle contract: a missing binary is a
 * logged no-op (the worklet path stands), the format header precedes every
 * sample byte, depth lines become state pushes, and a dead child hands the
 * ear path back to the worklet.
 */

class FakeStream extends EventEmitter {
  resume = vi.fn() // readline calls resume() on its input
}
class FakeChild extends EventEmitter {
  stdin = { write: vi.fn() }
  stdout = new FakeStream()
  stderr = new FakeStream()
  kill = vi.fn()
  exited = false
}

function makeEngine(
  options: { binaryExists?: boolean; spawnFn?: typeof spawn } = {}
) {
  const states: unknown[] = []
  const log = vi.fn()
  const engine = new AudioEngine({
    binaryPath: '/fake/caster-audio-engine',
    log: log as unknown as (level: 'debug' | 'info' | 'warn' | 'error', message: string, details?: Record<string, unknown>) => void,
    onState: (state) => states.push(state),
    spawnFn: options.spawnFn,
    existsFn: () => options.binaryExists ?? true
  })
  return { engine, states, log }
}

describe('audio engine lifecycle (Feature 2026-11e)', () => {
  afterEach(() => vi.restoreAllMocks())

  it('a missing binary is a logged no-op — the worklet path stands', () => {
    const { engine, log } = makeEngine({ binaryExists: false })
    expect(engine.start()).toBe(false)
    expect(engine.isRunning()).toBe(false)
    expect(log).toHaveBeenCalledWith('debug', expect.stringContaining('not built'), expect.anything())
  })

  it('start writes the format header once, before any sample byte', () => {
    const child = new FakeChild()
    const { engine } = makeEngine({ spawnFn: (() => child) as unknown as typeof spawn })
    expect(engine.start()).toBe(true)
    expect(child.stdin.write).toHaveBeenNthCalledWith(1, 'zfaudio1 rate=48000 ch=2 fmt=pcm16le\n')

    engine.writePcm(new ArrayBuffer(8))
    expect(child.stdin.write).toHaveBeenCalledTimes(2)
    expect((child.stdin.write.mock.calls[1][0] as Buffer).length).toBe(8)
  })

  it('depth lines from stdout become state pushes', async () => {
    const child = new FakeChild()
    const { engine, states } = makeEngine({ spawnFn: (() => child) as unknown as typeof spawn })
    engine.start()
    child.stdout.emit('data', Buffer.from('depth_ms=12 underruns=2\n'))
    await Promise.resolve()
    const last = states.at(-1) as { depthMs: number; underruns: number; running: boolean }
    expect(last.depthMs).toBe(12)
    expect(last.underruns).toBe(2)
    expect(last.running).toBe(true)
  })

  it('a dead child hands the ear path back — running false, depth cleared', async () => {
    const child = new FakeChild()
    const { engine, states } = makeEngine({ spawnFn: (() => child) as unknown as typeof spawn })
    engine.start()
    child.stdout.emit('data', Buffer.from('depth_ms=12 underruns=0\n'))
    child.emit('exit', 0)
    await Promise.resolve()
    const last = states.at(-1) as { running: boolean; depthMs: number | null }
    expect(last.running).toBe(false)
    expect(last.depthMs).toBeNull()
    expect(engine.isRunning()).toBe(false)
    expect(engine.getDepthMs()).toBeNull()
  })

  it('stop kills the child; stop without a child is a no-op', () => {
    const child = new FakeChild()
    const { engine } = makeEngine({ spawnFn: (() => child) as unknown as typeof spawn })
    expect(() => engine.stop()).not.toThrow()
    engine.start()
    engine.stop()
    expect(child.kill).toHaveBeenCalled()
  })
})
