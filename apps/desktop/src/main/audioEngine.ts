import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { createInterface } from 'node:readline'
import type { AudioEngineState } from '../shared/types'

/**
 * Feature 2026-11e (ADR-014 Fase A): the audio engine sidecar's main-process
 * owner. Spawns `caster-audio-engine` (the Rust crate under
 * `audio-engine/`), writes the raw PCM16LE chunks the renderer forwards,
 * parses the engine's ~1 Hz `depth_ms`/`underruns` stdout lines, and pushes
 * availability/depth state to the renderer — which routes game-audio chunks
 * to this engine when it is running (the PCM path's ear output skips
 * Chromium's audio graph entirely) and falls back to the worklet the moment
 * the engine dies.
 *
 * Boundaries kept honest:
 * - the engine owns the real-time output; this module owns lifecycle only
 *   (spawn, write, parse) — no audio decisions live here;
 * - volume/mute are applied by the renderer (the mixer's levels are the one
 *   truth) — chunks arrive pre-scaled, ready to write;
 * - the binary may be absent (no Rust toolchain / not built) — the state
 *   says so once and everything falls back, never a crash.
 */

export type EngineLogLevel = 'debug' | 'info' | 'warn' | 'error'

export interface AudioEngineOptions {
  /** Absolute path to the built `caster-audio-engine` binary. */
  binaryPath: string
  log: (level: EngineLogLevel, message: string, details?: Record<string, unknown>) => void
  /** Pushed on every state change (availability, running, depth, underruns). */
  onState: (state: AudioEngineState) => void
  /**
   * Injectable spawner (tests pass a fake child); defaults to node's spawn.
   * The fake must provide stdin/stdout/stderr streams and an `on` method.
   */
  spawnFn?: typeof spawn
  /** Injectable existence check (tests); defaults to fs.existsSync. */
  existsFn?: (path: string) => boolean
}

/** A minimal child surface this module actually touches. */
interface EngineChild {
  stdin: { write: (data: string | Buffer) => unknown } | null
  stdout: NodeJS.ReadableStream | null
  stderr: NodeJS.ReadableStream | null
  on: (event: 'exit', listener: (code: number | null) => void) => unknown
  kill: () => void
  exited?: boolean
}

export class AudioEngine {
  private readonly options: AudioEngineOptions
  private child: EngineChild | null = null
  private binaryMissingLogged = false
  private depthMs: number | null = null
  private underruns: number | null = null

  constructor(options: AudioEngineOptions) {
    this.options = options
  }

  /** Whether the sidecar currently owns the PCM chunks. */
  isRunning(): boolean {
    return this.child !== null && !this.child.exited
  }

  getDepthMs(): number | null {
    return this.depthMs
  }

  getUnderruns(): number | null {
    return this.underruns
  }

  /**
   * Spawns the engine if it is not already running. Returns whether chunks
   * may be written afterwards — a missing binary is a logged no-op (the
   * renderer keeps the worklet path), never an error.
   */
  start(): boolean {
    if (this.isRunning()) return true
    const exists = this.options.existsFn ?? existsSync
    if (!exists(this.options.binaryPath)) {
      if (!this.binaryMissingLogged) {
        this.binaryMissingLogged = true
        this.options.log('debug', 'audio engine binary not built — the worklet path stands', {
          binaryPath: this.options.binaryPath
        })
      }
      return false
    }
    const spawnFn = this.options.spawnFn ?? spawn
    const child = spawnFn(this.options.binaryPath, [], { stdio: ['pipe', 'pipe', 'pipe'] }) as unknown as EngineChild
    this.child = child
    this.depthMs = null
    this.underruns = null
    this.options.log('info', 'audio engine started — the pcm ear path bypasses Chromium audio')
    this.pushState(true)
    // The engine's format contract line, once, before any sample byte.
    child.stdin?.write('zfaudio1 rate=48000 ch=2 fmt=pcm16le\n')
    if (child.stdout !== null) {
      const lines = createInterface({ input: child.stdout })
      lines.on('line', (line) => {
        const match = /depth_ms=(\d+) underruns=(\d+)/.exec(line)
        if (match === null) return
        this.depthMs = Number(match[1])
        this.underruns = Number(match[2])
        this.pushState(true)
      })
    }
    if (child.stderr !== null) {
      const errors = createInterface({ input: child.stderr })
      errors.on('line', (line) => this.options.log('warn', 'audio engine stderr', { line }))
    }
    child.on('exit', (code) => {
      child.exited = true
      this.child = null
      this.depthMs = null
      this.underruns = null
      this.options.log('info', 'audio engine exited — the worklet path stands', { code })
      this.pushState(false)
    })
    return true
  }

  /**
   * One raw PCM16LE chunk, gain pre-applied by the renderer. Writes the
   * little-endian sample bytes verbatim to the engine's stdin.
   */
  writePcm(data: ArrayBuffer): void {
    if (!this.isRunning()) return
    try {
      this.child?.stdin?.write(Buffer.from(data))
    } catch (error) {
      this.options.log('warn', 'audio engine write failed', { error: String(error) })
    }
  }

  /** Stops the sidecar (cast ended / channel detached). Idempotent. */
  stop(): void {
    if (this.child === null) return
    try {
      this.child.kill()
    } catch {
      // A dead child may refuse kill — the exit handler cleans the state.
    }
  }

  private pushState(available: boolean): void {
    this.options.onState({
      available,
      running: this.isRunning(),
      depthMs: this.depthMs,
      underruns: this.underruns
    })
  }
}
