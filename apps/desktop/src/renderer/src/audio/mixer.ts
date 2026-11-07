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
  /** Chromium exposes it; undefined on fakes — the probe logs what it has. */
  readonly channelCount?: number
}

/** Receiver-side channel diagnostics (issue #101): what actually arrives. */
export interface AnalyserLike {
  getFloatTimeDomainData(array: Float32Array): void
}

export interface ChannelSplitterLike {
  connect(node: unknown, output?: number, input?: number): unknown
  disconnect(): void
}

export interface MediaStreamAudioDestinationNodeLike {
  readonly stream: unknown
}

export interface AudioWorkletNodeLike {
  port: {
    postMessage(message: unknown): void
    /** The worklet→main direction: the game ring's depth telemetry. */
    onmessage: ((event: { data: unknown }) => void) | null
  }
  connect(node: unknown): unknown
  disconnect(): void
  /** Chromium exposes it; undefined on fakes — the probe logs what it has. */
  readonly channelCount?: number
}

export interface AudioWorkletLike {
  addModule(url: string): Promise<void>
}

export interface AudioContextLike {
  readonly destination: object
  readonly state: string
  /**
   * The context's own latency figures, in seconds (present on Chromium's
   * AudioContext; absent on fakes) — the receiver's audio-output latency
   * telemetry, read via [AudioMixer.audioOutputLatencyMs].
   */
  readonly baseLatency?: number
  readonly outputLatency?: number
  createGain(): GainNodeLike
  createMediaStreamSource(stream: unknown): MediaStreamAudioSourceNodeLike
  createMediaStreamDestination(): MediaStreamAudioDestinationNodeLike
  /** Present on Chromium's context; absent on fakes — the probe is optional. */
  createChannelSplitter?(numberOfOutputs: number): ChannelSplitterLike
  createAnalyser?(): AnalyserLike
  /** ADR-012: present on Chromium; the PCM game-audio path is optional like the probe. */
  audioWorklet?: AudioWorkletLike
  resume(): Promise<void>
  setSinkId?(deviceId: string): Promise<void>
  close?(): Promise<void>
}

export interface WorkletNodeOptions {
  numberOfInputs?: number
  numberOfOutputs?: number
  outputChannelCount?: number[]
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
  /** ADR-012: production passes `new AudioWorkletNode` — no method exists on the context. */
  createWorkletNode: (
    context: AudioContextLike,
    name: string,
    options?: WorkletNodeOptions
  ) => AudioWorkletNodeLike
  /** Persisted levels and routing mode, restored on launch; omit for an in-memory mixer. */
  storage?: MixerStorage
  log: MixerLogger
}

const STORAGE_KEY = 'zfc.audio-mixer.v1'
const CHANNELS: readonly MixerChannel[] = ['game', 'mic']
const DEFAULT_LEVEL: MixerLevel = { volume: 1, muted: false }

/** Silence the PCM fallback waits before handing the path back to the track. */
const GAME_PCM_FALLBACK_MS = 3_000

/** The worklet as a real asset — Blob URLs fail in file:// renderers (found live). */
const GAME_PCM_WORKLET_URL = new URL('./game-pcm-worklet.js', import.meta.url)

/** Muting silences the channel without losing the stored volume. */
const effectiveGain = (level: MixerLevel): number => (level.muted ? 0 : level.volume)

const clampVolume = (volume: number): number => Math.min(1, Math.max(0, volume))

/** Mean absolute sample level of an analyser window (0..1 range diagnostics). */
const meanAbsLevel = (window: Float32Array): number => {
  let sum = 0
  for (const sample of window) sum += Math.abs(sample)
  return sum / window.length
}

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
  /**
   * Per-track cable routing (batch A, ADR-008): caster's own devices (from
   * the ZeroFrictionAudio HAL plugin) — each channel gets its own cable
   * context, so game audio and mic arrive as separate input devices. Null
   * entries mean that channel falls back to the shared mix cable.
   */
  private perTrackDeviceIds: Partial<Record<MixerChannel, string>> = {}
  private perTrackSend: Partial<Record<MixerChannel, MediaStreamAudioDestinationNodeLike>> = {}
  private perTrackCableContexts: Partial<Record<MixerChannel, AudioContextLike>> = {}
  private perTrackCableSources: Partial<Record<MixerChannel, MediaStreamAudioSourceNodeLike>> = {}
  /** The sink each per-track context was built with — a changed device id means rebuild. */
  private perTrackSinkIds: Partial<Record<MixerChannel, string>> = {}
  /** The receiver-side channel probe (issue #101) — parallel, never audible. */
  private channelProbe: { splitter: ChannelSplitterLike; left: AnalyserLike; right: AnalyserLike } | null = null
  private channelProbeTimer: ReturnType<typeof setInterval> | null = null
  /** ADR-012: the PCM game-audio path — the worklet feeding the game gain. */
  private gamePcm: AudioWorkletNodeLike | null = null
  private gamePcmModuleLoading: Promise<void> | null = null
  private gamePcmActive = false
  private gamePcmLastAt = 0
  private gamePcmFallbackTimer: ReturnType<typeof setInterval> | null = null
  private gamePcmTrackStash: MediaStreamAudioSourceNodeLike | null = null
  /** Last ring-depth report from the worklet (ADR-013 telemetry); null until one arrives. */
  private gamePcmDepthMs: number | null = null

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
   * Per-track cable routing (batch A, ADR-008): assign each channel its own
   * virtual cable device — "Zero-Friction Game Audio" and "Zero-Friction
   * Mic" — so game audio and mic arrive as two independent input devices
   * other apps can pick separately. Channels without a device fall back to
   * the shared mix cable. Call with nulls to clear.
   */
  setPerTrackCableDevices(devices: Partial<Record<MixerChannel, string>>): void {
    this.perTrackDeviceIds = devices
    this.applyRouting() // rewire with the new per-track assignments
    this.options.log('info', 'per-track cable devices updated', {
      game: devices.game ?? '(shared)',
      mic: devices.mic ?? '(shared)'
    })
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
    if (channel === 'game' && this.gamePcmActive) {
      // ADR-012: the stereo PCM owns the audible path — hold the arriving
      // dual-mono track for the fallback instead of wiring it in.
      source.disconnect()
      this.gamePcmTrackStash = source
      return
    }
    source.connect(gain)
    wiring.source = source
    this.attachChannelProbe(channel, source)
    this.applyRouting()
  }

  /** Stop a channel's stream (mic toggled off, mobile gone). The stored level survives. */
  detachStream(channel: MixerChannel): void {
    const wiring = this.wiring[channel]
    this.detachChannelProbe()
    wiring.source?.disconnect()
    wiring.source = null
    if (channel === 'game') this.detachGamePcmSource()
  }

  /**
   * ADR-012: game audio as raw stereo PCM pushed from the mobile's
   * `"game-audio"` DataChannel. The pinned libwebrtc build folds the ADM's
   * stereo input to mono before its encoder (audio.md) — the received track
   * is dual-mono — so the true stereo rides this path and this mixer renders
   * it: Int16LE frames → Float32 → an AudioWorklet ring → the existing game
   * gain node (volume/mute/routing unchanged). While PCM flows, the track
   * source is stashed (it plays dual-mono — an echo under the stereo); if
   * the channel goes quiet for [GAME_PCM_FALLBACK_MS] (older mobile build),
   * the track returns automatically, and PCM re-claims the path on arrival.
   *
   * ADR-013: the channel's default payload is now ADTS-AAC — the caller
   * (main.ts's handshake) decodes it and delivers the same `{left, right}`
   * Float32 pairs through [pushFrames]; `push` stays the raw-PCM entry.
   */
  async attachGamePcmSource(): Promise<{
    push(chunk: ArrayBuffer): void
    pushFrames(left: Float32Array, right: Float32Array): void
    detach(): void
  } | null> {
    const worklet = await this.ensureGamePcmWorklet()
    if (worklet === null) return null
    this.stashGameTrack()
    this.gamePcmActive = true
    this.gamePcmLastAt = Date.now()
    this.attachChannelProbe('game', worklet)
    if (this.gamePcmFallbackTimer === null) {
      this.gamePcmFallbackTimer = setInterval(() => this.reconcileGamePcmFallback(), 1_000)
    }
    this.options.log('info', 'game audio switched to the stereo PCM path')
    return {
      push: (chunk) => this.pushGamePcm(chunk),
      pushFrames: (left, right) => this.pushGameFrames(left, right),
      detach: () => this.detachGamePcmSource()
    }
  }

  /**
   * Builds the worklet once. `audioWorklet.addModule` is asynchronous — the
   * node may only be constructed after the module's promise resolves (found
   * live: a sync build threw `InvalidStateError`, no valid global scope).
   */
  private async ensureGamePcmWorklet(): Promise<AudioWorkletNodeLike | null> {
    if (this.gamePcm !== null) return this.gamePcm
    const context = this.ensureContext()
    if (context === null) return null
    if (context.audioWorklet === undefined) {
      this.options.log('warn', 'game pcm unavailable: AudioWorklet missing in this context')
      return null
    }
    try {
      if (this.gamePcmModuleLoading === null) {
        this.gamePcmModuleLoading = context.audioWorklet.addModule(GAME_PCM_WORKLET_URL.href)
      }
      await this.gamePcmModuleLoading
      const node = this.options.createWorkletNode(context, 'game-pcm', {
        numberOfInputs: 0,
        numberOfOutputs: 1,
        outputChannelCount: [2]
      })
      node.port.onmessage = (event) => {
        const depth = (event.data as { depthMs?: unknown } | null)?.depthMs
        if (typeof depth === 'number') this.gamePcmDepthMs = depth
      }
      const gain = this.ensureGain('game', context)
      gain.gain.value = effectiveGain(this.levels.game)
      node.connect(gain)
      this.applyRouting()
      this.gamePcm = node
      return node
    } catch (error) {
      this.gamePcmModuleLoading = null // a failed load must be retryable
      this.options.log('warn', 'game pcm worklet build failed', { error: String(error) })
      return null
    }
  }

  /** Takes the dual-mono track out of the audible path but keeps it for the fallback. */
  private stashGameTrack(): void {
    const source = this.wiring.game.source
    if (source !== null) {
      source.disconnect()
      this.wiring.game.source = null
      this.gamePcmTrackStash = source
    }
  }

  /** One DataChannel message: Int16LE interleaved stereo PCM16 frames. */
  private pushGamePcm(chunk: ArrayBuffer): void {
    const frames = new DataView(chunk)
    const sampleFrames = Math.floor(chunk.byteLength / 4)
    const left = new Float32Array(sampleFrames)
    const right = new Float32Array(sampleFrames)
    for (let i = 0; i < sampleFrames; i++) {
      left[i] = frames.getInt16(i * 4, true) / 32768
      right[i] = frames.getInt16(i * 4 + 2, true) / 32768
    }
    this.pushGameFrames(left, right)
  }

  /**
   * One decoded (ADR-013 AAC) or converted (ADR-012 PCM) stereo frame pair
   * into the worklet ring — the fallback timer's liveness stamp sits here so
   * both formats keep the channel "alive" the same way.
   */
  private pushGameFrames(left: Float32Array, right: Float32Array): void {
    this.gamePcmLastAt = Date.now()
    const worklet = this.gamePcm
    if (worklet === null) return
    if (!this.gamePcmActive) this.activateGamePcm()
    worklet.port.postMessage({ left, right })
  }

  private activateGamePcm(): void {
    const gain = this.wiring.game.gain
    const worklet = this.gamePcm
    if (gain === null || worklet === null) return
    this.stashGameTrack()
    worklet.connect(gain)
    this.gamePcmActive = true
    this.attachChannelProbe('game', worklet)
  }

  /** The 1 s tick: a quiet channel hands the audible path back to the track. */
  private reconcileGamePcmFallback(): void {
    if (!this.gamePcmActive) return
    if (Date.now() - this.gamePcmLastAt <= GAME_PCM_FALLBACK_MS) return
    const gain = this.wiring.game.gain
    if (gain !== null) this.gamePcm?.disconnect()
    this.gamePcmActive = false
    const stash = this.gamePcmTrackStash
    this.gamePcmTrackStash = null
    if (stash !== null && gain !== null) {
      stash.connect(gain)
      this.wiring.game.source = stash
      this.attachChannelProbe('game', stash)
    }
    this.options.log('warn', 'game pcm quiet — fell back to the audio track (dual-mono)')
  }

  detachGamePcmSource(): void {
    if (this.gamePcmFallbackTimer !== null) {
      clearInterval(this.gamePcmFallbackTimer)
      this.gamePcmFallbackTimer = null
    }
    const gain = this.wiring.game.gain
    if (gain !== null) this.gamePcm?.disconnect()
    this.gamePcmActive = false
    this.gamePcmDepthMs = null
  }

  /**
   * The game ring's depth in ms — the ADR-013 playout-tuning telemetry. The
   * worklet reports ~1 Hz; null until the first report (or after detach).
   */
  gameRingDepthMs(): number | null {
    return this.gamePcmDepthMs
  }

  /**
   * The main context's output-latency figures in ms (Feature 2026-11d) —
   * the receiver-side term the ear-path budget could only estimate until
   * now. Null while no context exists or the context is a fake without the
   * members (fakes stay optional, like `setSinkId`).
   */
  audioOutputLatencyMs(): { baseMs: number | null; outputMs: number | null } {
    const context = this.context
    return {
      baseMs: typeof context?.baseLatency === 'number' ? Math.round(context.baseLatency * 1000) : null,
      outputMs: typeof context?.outputLatency === 'number' ? Math.round(context.outputLatency * 1000) : null
    }
  }

  /**
   * Receiver-side channel diagnostics (issue #101): every sender-side probe
   * ends at the ADM — this one measures what actually ARRIVES at the
   * receiver, as per-channel levels of the remote stream through a parallel
   * splitter (never in the audible path), logged every 2 s. Numbers only.
   */
  private attachChannelProbe(channel: MixerChannel, source: MediaStreamAudioSourceNodeLike): void {
    const context = this.context
    if (context === null || context.createChannelSplitter === undefined || context.createAnalyser === undefined) {
      return
    }
    this.detachChannelProbe()
    try {
      const splitter = context.createChannelSplitter(2)
      const left = context.createAnalyser()
      const right = context.createAnalyser()
      source.connect(splitter)
      splitter.connect(left, 0)
      splitter.connect(right, 1)
      this.channelProbe = { splitter, left, right }
      const window = new Float32Array(2048)
      this.channelProbeTimer = setInterval(() => {
        const probe = this.channelProbe
        if (probe === null) return
        probe.left.getFloatTimeDomainData(window)
        const leftLevel = meanAbsLevel(window)
        probe.right.getFloatTimeDomainData(window)
        const rightLevel = meanAbsLevel(window)
        this.options.log('info', 'received stream channels', {
          channel,
          channelCount: source.channelCount,
          left: leftLevel,
          right: rightLevel
        })
      }, 2_000)
    } catch (error) {
      this.options.log('warn', 'channel probe unavailable', { error: String(error) })
    }
  }

  private detachChannelProbe(): void {
    if (this.channelProbeTimer !== null) {
      clearInterval(this.channelProbeTimer)
      this.channelProbeTimer = null
    }
    if (this.channelProbe !== null) {
      try {
        this.channelProbe.splitter.disconnect()
      } catch {
        // The splitter's source already disconnected — nothing to save.
      }
      this.channelProbe = null
    }
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

    // Retire per-track contexts whose channel no longer routes to one:
    // the device vanished, OR the route left cable mode entirely (review
    // finding: speakers-mode kept the contexts alive forever — the shared
    // cable context closes on the same switch, the per-track ones must too),
    // OR the assigned device id changed (the context is pinned to its sink).
    // Runs BEFORE the rewire so a stale context is never reused for the new
    // assignment.
    for (const channel of CHANNELS) {
      const deviceId = this.perTrackDeviceIds[channel]
      const staleSink = deviceId !== undefined && this.perTrackSinkIds[channel] !== deviceId
      if (
        this.perTrackCableContexts[channel] !== undefined &&
        (!wantsCable || deviceId === undefined || staleSink)
      ) {
        this.perTrackCableContexts[channel]?.close?.()
        delete this.perTrackCableContexts[channel]
        delete this.perTrackCableSources[channel]
        delete this.perTrackSend[channel]
        delete this.perTrackSinkIds[channel]
      }
    }

    // Per-track path (batch A): each channel with its own device gets a
    // dedicated send + cable context — its audio arrives at its own input
    // device, not the shared mix cable.
    for (const channel of CHANNELS) {
      const gain = this.wiring[channel].gain
      if (gain === null) continue
      const deviceId = this.perTrackDeviceIds[channel]
      gain.disconnect()
      if (this.routingMode !== 'cable') gain.connect(context.destination)

      if (wantsCable && deviceId !== undefined) {
        // Per-track cable: send → own context with its device as sink
        if (this.perTrackSend[channel] === undefined) {
          this.perTrackSend[channel] = context.createMediaStreamDestination()
        }
        gain.connect(this.perTrackSend[channel]!)
        this.applyPerTrackCableBridge(channel, deviceId)
      } else if (wantsCable) {
        // Shared cable (fallback / no per-track device for this channel)
        if (this.sendDestination === null) {
          this.sendDestination = context.createMediaStreamDestination()
        }
        gain.connect(this.sendDestination)
      }
    }

    this.applyCableBridge(wantsCable)
  }

  /** Per-channel cable context: its own AudioContext with the track device as sink. */
  private applyPerTrackCableBridge(channel: MixerChannel, deviceId: string): void {
    const send = this.perTrackSend[channel]
    if (send === undefined) return

    if (this.perTrackCableContexts[channel] === undefined) {
      try {
        this.perTrackCableContexts[channel] = this.options.createAudioContext({ sinkId: deviceId })
        this.perTrackSinkIds[channel] = deviceId
      } catch (error) {
        this.options.log('error', `creating the ${channel} cable AudioContext failed`, {
          error: String(error)
        })
        return
      }
    }
    const cableContext = this.perTrackCableContexts[channel]!
    if (cableContext.state !== 'running') {
      cableContext.resume().catch((error: unknown) =>
        this.options.log('warn', `${channel} cable AudioContext resume failed`, { error: String(error) })
      )
    }
    if (this.perTrackCableSources[channel] === undefined) {
      this.perTrackCableSources[channel] = cableContext.createMediaStreamSource(send.stream)
      this.perTrackCableSources[channel]!.connect(cableContext.destination)
    }
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
