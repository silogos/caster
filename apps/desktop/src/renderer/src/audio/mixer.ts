/**
 * The receiver's audio graph (docs/architecture/audio.md, Phase9):
 * each remote stream becomes a MediaStreamAudioSourceNode → its own GainNode
 * → AudioContext.destination — independent volume and mute per stream, no
 * further processing (no EQ, no receiver-side AEC). Game audio and mic are
 * separate channels end to end (ADR-003): turning one down or muting it never
 * touches the other.
 *
 * The Web Audio and storage surfaces are injected, so the graph wiring, the
 * level math, and the persistence are unit-testable in plain Node (vitest
 * runs without Chromium; production wires Chromium's AudioContext and
 * localStorage).
 */

export type MixerChannel = 'game' | 'mic'

export interface MixerLevel {
  /** Linear gain 0..1; 1 = unity — the no-distortion default (audio.md). */
  volume: number
  muted: boolean
}

/** The Web Audio surface AudioMixer uses — satisfied by Chromium's, faked in tests. */
export interface GainNodeLike {
  gain: { value: number }
  connect(node: unknown): unknown
  disconnect(): void
}

export interface MediaStreamAudioSourceNodeLike {
  connect(node: unknown): unknown
  disconnect(): void
}

export interface AudioContextLike {
  readonly destination: object
  readonly state: string
  createGain(): GainNodeLike
  createMediaStreamSource(stream: unknown): MediaStreamAudioSourceNodeLike
  resume(): Promise<void>
}

/** localStorage satisfies this; injected so tests run in plain Node. */
export interface MixerStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export type MixerLogger = (
  level: 'debug' | 'info' | 'warn' | 'error',
  message: string,
  details?: Record<string, unknown>
) => void

export interface AudioMixerOptions {
  createAudioContext: () => AudioContextLike
  /** Persisted levels, restored on launch; omit for an in-memory mixer. */
  storage?: MixerStorage
  log: MixerLogger
}

const STORAGE_KEY = 'zfc.audio-mixer.v1'
const CHANNELS: readonly MixerChannel[] = ['game', 'mic']
const DEFAULT_LEVEL: MixerLevel = { volume: 1, muted: false }

/** Muting silences the channel without losing the stored volume. */
const effectiveGain = (level: MixerLevel): number => (level.muted ? 0 : level.volume)

const clampVolume = (volume: number): number => Math.min(1, Math.max(0, volume))

const isValidLevel = (value: unknown): value is MixerLevel => {
  if (typeof value !== 'object' || value === null) return false
  const level = value as Partial<MixerLevel>
  return typeof level.volume === 'number' && typeof level.muted === 'boolean'
}

interface ChannelWiring {
  gain: GainNodeLike | null
  source: MediaStreamAudioSourceNodeLike | null
}

export class AudioMixer {
  private readonly options: AudioMixerOptions
  private readonly levels: Record<MixerChannel, MixerLevel>
  private readonly wiring: Record<MixerChannel, ChannelWiring> = {
    game: { gain: null, source: null },
    mic: { gain: null, source: null }
  }
  private context: AudioContextLike | null = null

  constructor(options: AudioMixerOptions) {
    this.options = options
    this.levels = AudioMixer.loadLevels(options)
  }

  /** Current levels (UI init); a fresh copy — callers can't bypass the API. */
  getLevels(): Record<MixerChannel, MixerLevel> {
    return {
      game: { ...this.levels.game },
      mic: { ...this.levels.mic }
    }
  }

  setVolume(channel: MixerChannel, volume: number): void {
    this.levels[channel] = { ...this.levels[channel], volume: clampVolume(volume) }
    this.applyGain(channel)
    this.persist()
  }

  setMuted(channel: MixerChannel, muted: boolean): void {
    this.levels[channel] = { ...this.levels[channel], muted }
    this.applyGain(channel)
    this.persist()
  }

  /**
   * Route one remote stream through its channel's GainNode. Safe to call again
   * for a fresh stream (a rebuilt pc produces a new MediaStream) — the previous
   * source is disconnected first. The AudioContext is created lazily here: no
   * context (and no audio thread) exists until the first audio arrives.
   */
  attachStream(channel: MixerChannel, stream: unknown): void {
    const context = this.ensureContext()
    if (context === null) return
    const wiring = this.wiring[channel]
    const gain = this.ensureGain(channel, context)
    gain.gain.value = effectiveGain(this.levels[channel])
    wiring.source?.disconnect()
    const source = context.createMediaStreamSource(stream)
    source.connect(gain)
    wiring.source = source
  }

  /** Stop a channel's stream (mic toggled off, mobile gone). The stored level survives. */
  detachStream(channel: MixerChannel): void {
    const wiring = this.wiring[channel]
    wiring.source?.disconnect()
    wiring.source = null
  }

  private applyGain(channel: MixerChannel): void {
    const gain = this.wiring[channel].gain
    if (gain !== null) gain.gain.value = effectiveGain(this.levels[channel])
  }

  private ensureContext(): AudioContextLike | null {
    if (this.context === null) {
      try {
        this.context = this.options.createAudioContext()
      } catch (error) {
        // Defensive by design: a mixer that cannot start degrades the cast to
        // silent audio (video keeps rendering), never crashes the receiver.
        this.options.log('error', 'creating the AudioContext failed — no mixer audio', {
          error: String(error)
        })
        return null
      }
    }
    // Re-checked per attach: a context whose earlier resume failed (or never
    // happened) gets another chance with the next stream.
    if (this.context.state !== 'running') {
      // Chromium starts contexts suspended when a gesture is required; Electron
      // does not by default, but resume() is harmless when already running.
      this.context.resume().catch((error: unknown) =>
        this.options.log('warn', 'AudioContext resume failed', { error: String(error) })
      )
    }
    return this.context
  }

  private ensureGain(channel: MixerChannel, context: AudioContextLike): GainNodeLike {
    const wiring = this.wiring[channel]
    if (wiring.gain === null) {
      const gain = context.createGain()
      gain.connect(context.destination)
      wiring.gain = gain
    }
    return wiring.gain
  }

  private persist(): void {
    if (this.options.storage === undefined) return
    this.options.storage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, ...this.levels }))
  }

  private static loadLevels(options: AudioMixerOptions): Record<MixerChannel, MixerLevel> {
    const defaults: Record<MixerChannel, MixerLevel> = {
      game: { ...DEFAULT_LEVEL },
      mic: { ...DEFAULT_LEVEL }
    }
    if (options.storage === undefined) return defaults
    const raw = options.storage.getItem(STORAGE_KEY)
    if (raw === null) return defaults
    try {
      const parsed = JSON.parse(raw) as Record<string, unknown>
      for (const channel of CHANNELS) {
        const level = parsed[channel]
        if (isValidLevel(level)) {
          defaults[channel] = { volume: clampVolume(level.volume), muted: level.muted }
        }
      }
    } catch (error) {
      // Corrupt/out-of-schema storage is a fresh start, never a crash.
      options.log('warn', 'stored mixer levels were unreadable — using defaults', {
        error: String(error)
      })
    }
    return defaults
  }
}
