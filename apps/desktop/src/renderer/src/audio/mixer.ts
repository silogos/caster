/**
 * The receiver's audio graph (docs/architecture/audio.md, Phase9):
 * each remote stream becomes a MediaStreamAudioSourceNode → its own GainNode
 * → AudioContext.destination — independent volume and mute per stream, no
 * further processing (no EQ, no receiver-side AEC). Game audio and mic are
 * separate channels end to end (ADR-003): turning one down or muting it never
 * touches the other.
 *
 * Audio output routing (issue #54, ADR-005 Phase D): the mix can leave the
 * speakers. `speakers` is today's behavior; `cable` disconnects the gains
 * from the speakers and plays the mix only into a second AudioContext whose
 * sink is the virtual cable device (BlackHole); `both` does both. Gains stay
 * shared, so volume/mute apply identically on every route. With no cable
 * device known, a cable/both request is refused (logged) — the send branch
 * is never built against a missing device, so "cable" can never degrade into
 * an accidental return to the speakers.
 *
 * The Web Audio and storage surfaces are injected, so the graph wiring, the
 * level math, and the persistence are unit-testable in plain Node (vitest
 * runs without Chromium; production wires Chromium's AudioContext and
 * localStorage).
 */

import {
  DEFAULT_ROUTING_MODE,
  loadRoutingMode,
  persistRoutingMode,
  type RoutingMode
} from './routing'

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

export interface MediaStreamAudioDestinationNodeLike {
  readonly stream: unknown
}

export interface AudioContextLike {
  readonly destination: object
  readonly state: string
  createGain(): GainNodeLike
  createMediaStreamSource(stream: unknown): MediaStreamAudioSourceNodeLike
  createMediaStreamDestination(): MediaStreamAudioDestinationNodeLike
  resume(): Promise<void>
  setSinkId?(deviceId: string): Promise<void>
  close?(): Promise<void>
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
  /** Production passes `AudioContext`; the cable context is built with a sinkId. */
  createAudioContext: (options?: { sinkId?: string }) => AudioContextLike
  /** Persisted levels and routing mode, restored on launch; omit for an in-memory mixer. */
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
  private routingMode: RoutingMode = DEFAULT_ROUTING_MODE
  private cableDeviceId: string | null = null
  /** The mix tap in the main context; built once, fed by the gains on cable routes. */
  private sendDestination: MediaStreamAudioDestinationNodeLike | null = null
  private cableContext: AudioContextLike | null = null
  private cableSource: MediaStreamAudioSourceNodeLike | null = null

  constructor(options: AudioMixerOptions) {
    this.options = options
    this.levels = AudioMixer.loadLevels(options)
    this.routingMode = loadRoutingMode(options.storage, options.log)
  }

  /** Current levels (UI init); a fresh copy — callers can't bypass the API. */
  getLevels(): Record<MixerChannel, MixerLevel> {
    return {
      game: { ...this.levels.game },
      mic: { ...this.levels.mic }
    }
  }

  getRoutingMode(): RoutingMode {
    return this.routingMode
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
   * Switch where the mix goes. `cableDeviceId` is resolved by the caller
   * (audio/routing.ts device detection); omitting it keeps the last known
   * device. A cable route without a device is refused with a log line —
   * refusing beats silence and beats an accidental speaker return.
   */
  setRoutingMode(mode: RoutingMode, cableDeviceId?: string): void {
    if (cableDeviceId !== undefined) this.cableDeviceId = cableDeviceId
    const wantsCable = mode !== 'speakers'
    if (wantsCable && this.cableDeviceId === null) {
      this.options.log('warn', 'cable routing refused: no virtual cable device is known', { mode })
      return
    }
    const previous = this.routingMode
    this.routingMode = mode
    try {
      this.applyRouting()
    } catch (error) {
      this.routingMode = previous
      this.options.log('error', 'routing change failed — staying on the previous route', {
        mode,
        error: String(error)
      })
      return
    }
    this.options.log('info', 'audio routing changed', { mode, cableDeviceId: this.cableDeviceId })
    persistRoutingMode(this.options.storage, mode)
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
    this.applyRouting()
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

  /**
   * Rewire the gains for the current mode. disconnect() drops a gain's
   * outgoing edges (its inbound source stays), so each route change is a
   * full reconnection of the outgoing side only.
   */
  private applyRouting(): void {
    const context = this.context
    if (context === null) return
    const wantsCable = this.routingMode !== 'speakers'
    if (wantsCable && this.sendDestination === null) {
      this.sendDestination = context.createMediaStreamDestination()
    }
    for (const channel of CHANNELS) {
      const gain = this.wiring[channel].gain
      if (gain === null) continue
      gain.disconnect()
      if (this.routingMode !== 'cable') gain.connect(context.destination)
      if (wantsCable && this.sendDestination !== null) gain.connect(this.sendDestination)
    }
    this.applyCableBridge(wantsCable)
  }

  /**
   * The cable side: a second AudioContext whose sink is the cable device,
   * playing the main context's send tap. Built only when a device is known —
   * a missing sinkId would fall back to the system default, which is the
   * speakers (the exact collision this feature exists to avoid).
   */
  private applyCableBridge(wantsCable: boolean): void {
    if (!wantsCable || this.cableDeviceId === null || this.sendDestination === null) {
      if (this.cableContext !== null) {
        this.cableContext.close?.().catch((error: unknown) =>
          this.options.log('warn', 'closing the cable AudioContext failed', { error: String(error) })
        )
        this.cableContext = null
        this.cableSource = null
      }
      return
    }
    if (this.cableContext === null) {
      try {
        this.cableContext = this.options.createAudioContext({ sinkId: this.cableDeviceId })
      } catch (error) {
        this.options.log('error', 'creating the cable AudioContext failed — no cable audio', {
          error: String(error)
        })
        this.cableContext = null
        return
      }
    }
    if (this.cableContext.state !== 'running') {
      this.cableContext.resume().catch((error: unknown) =>
        this.options.log('warn', 'cable AudioContext resume failed', { error: String(error) })
      )
    }
    if (this.cableSource === null) {
      this.cableSource = this.cableContext.createMediaStreamSource(this.sendDestination.stream)
      this.cableSource.connect(this.cableContext.destination)
    }
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
