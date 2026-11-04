import { describe, expect, it, vi } from 'vitest'
import {
  AudioMixer,
  type AnalyserLike,
  type AudioContextLike,
  type AudioWorkletLike,
  type AudioWorkletNodeLike,
  type ChannelSplitterLike,
  type GainNodeLike,
  type MediaStreamAudioDestinationNodeLike,
  type MediaStreamAudioSourceNodeLike,
  type MixerLogger,
  type MixerStorage
} from './mixer'

/**
 * Behavior tests for the Phase9 audio graph (docs/architecture/audio.md) with
 * a fake Web Audio surface — the wiring the spec demands (source → own
 * GainNode → destination, per stream), independent volume/mute, and levels
 * that survive a relaunch. No real AudioContext exists in plain Node;
 * production wires Chromium's.
 */

class FakeGain implements GainNodeLike {
  gain = { value: Number.NaN }
  connectedTo: unknown[] = []
  disconnectCount = 0

  connect(node: unknown): unknown {
    this.connectedTo.push(node)
    return node
  }

  disconnect(): void {
    this.disconnectCount +=1
    // Real WebAudio semantics: disconnect() drops ALL outgoing connections,
    // and the mixer re-asserts the current route after every disconnect.
    this.connectedTo = []
  }
}

class FakeSource implements MediaStreamAudioSourceNodeLike {
  connectedTo: unknown[] = []
  disconnectCount = 0

  constructor(readonly stream: unknown) {}

  connect(node: unknown): unknown {
    this.connectedTo.push(node)
    return node
  }

  disconnect(): void {
    this.disconnectCount += 1
  }
}

class FakeSendDestination implements MediaStreamAudioDestinationNodeLike {
  readonly stream = { id: 'mixer-send-tap' }
}

class FakeContext implements AudioContextLike {
  readonly destination = { name: 'destination' }
  gains: FakeGain[] = []
  sources: FakeSource[] = []
  destinations: FakeSendDestination[] = []
  resume = vi.fn((): Promise<void> => Promise.resolve())
  state = 'running'
  sinkId: string | null = null
  closed = false
  setSinkId = vi.fn((deviceId: string): Promise<void> => {
    this.sinkId = deviceId
    return Promise.resolve()
  })
  close = vi.fn((): Promise<void> => {
    this.closed = true
    return Promise.resolve()
  })

  createGain(): FakeGain {
    const gain = new FakeGain()
    this.gains.push(gain)
    return gain
  }

  createMediaStreamSource(stream: unknown): FakeSource {
    const source = new FakeSource(stream)
    this.sources.push(source)
    return source
  }

  createMediaStreamDestination(): FakeSendDestination {
    const destination = new FakeSendDestination()
    this.destinations.push(destination)
    return destination
  }

  /** Opt-in per test: the PCM game-audio path (ADR-012) needs both surfaces. */
  audioWorklet: AudioWorkletLike | undefined = undefined

  enableWorkletHost(): FakeWorkletHost {
    const host = new FakeWorkletHost()
    this.audioWorklet = host
    return host
  }
}

class FakeWorkletNode implements AudioWorkletNodeLike {
  port = { postMessage: vi.fn(), onmessage: null as ((event: { data: unknown }) => void) | null }
  connectedTo: unknown[] = []
  disconnectCount = 0
  channelCount = 2

  connect(node: unknown): unknown {
    this.connectedTo.push(node)
    return node
  }

  disconnect(): void {
    this.disconnectCount += 1
  }
}

class FakeWorkletHost implements AudioWorkletLike {
  readonly node = new FakeWorkletNode()
  addedModules: string[] = []

  addModule(url: string): Promise<void> {
    this.addedModules.push(url)
    return Promise.resolve()
  }
}

/** In-memory localStorage stand-in with the same stringly surface. */
class FakeStorage implements MixerStorage {
  private readonly map = new Map<string, string>()
  private readonly seeded: Record<string, string>

  constructor(seeded: Record<string, string> = {}) {
    this.seeded = seeded
  }

  getItem(key: string): string | null {
    return this.map.get(key) ?? this.seeded[key] ?? null
  }

  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }
}

const makeMixer = (
  options: Partial<ConstructorParameters<typeof AudioMixer>[0]> & { storage?: MixerStorage } = {}
) => {
  const createAudioContext = options.createAudioContext ?? (() => new FakeContext())
  const createWorkletNode =
    options.createWorkletNode ??
    ((context: AudioContextLike) => {
      const host = (context as FakeContext).audioWorklet as FakeWorkletHost
      return host.node
    })
  const log = options.log ?? (vi.fn() as unknown as MixerLogger)
  const mixer = new AudioMixer({ createAudioContext, createWorkletNode, storage: options.storage, log })
  return { mixer, createAudioContext, log: log as unknown as ReturnType<typeof vi.fn> }
}


describe('AudioMixer — defaults and the graph', () => {
  it('defaults to unity, unmuted levels per channel', () => {
    const { mixer } = makeMixer()
    expect(mixer.getLevels()).toEqual({
      game: { volume: 1, muted: false },
      mic: { volume: 1, muted: false }
    })
  })

  it('wires each stream through its own gain node to the destination', () => {
    const context = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    mixer.attachStream('game', { id: 's1' })
    mixer.attachStream('mic', { id: 's2' })
    expect(context.sources).toHaveLength(2)
    expect(context.gains).toHaveLength(2)
    const [gameSource, micSource] = context.sources
    const [gameGain, micGain] = context.gains
    // One gain per channel (not shared), each into the one destination.
    expect(gameSource.connectedTo).toEqual([gameGain])
    expect(micSource.connectedTo).toEqual([micGain])
    expect(gameGain.connectedTo).toEqual([context.destination])
    expect(micGain.connectedTo).toEqual([context.destination])
  })

  it('creates the AudioContext lazily — on the first attached stream', () => {
    const createAudioContext = vi.fn(() => new FakeContext())
    const { mixer } = makeMixer({ createAudioContext })
    mixer.setVolume('game', 0.4)
    expect(createAudioContext).not.toHaveBeenCalled()
    mixer.attachStream('game', { id: 's1' })
    expect(createAudioContext).toHaveBeenCalledTimes(1)
  })

  it('resumes a suspended context (Chromium autoplay policy) and reuses it, not recreating per attach', () => {
    const context = new FakeContext()
    context.state = 'suspended'
    let creations = 0
    const { mixer } = makeMixer({
      createAudioContext: () => {
        creations += 1
        return context
      }
    })
    mixer.attachStream('game', { id: 's1' })
    expect(context.resume).toHaveBeenCalledTimes(1)
    mixer.attachStream('mic', { id: 's2' })
    expect(creations).toBe(1)
    expect(context.resume).toHaveBeenCalledTimes(2)
  })

  it('a failed resume is logged, not thrown', async () => {
    const context = new FakeContext()
    context.state = 'suspended'
    context.resume.mockRejectedValue(new Error('no gesture'))
    const { mixer, log } = makeMixer({ createAudioContext: () => context })
    mixer.attachStream('game', { id: 's1' })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(log).toHaveBeenCalledWith('warn', 'AudioContext resume failed', expect.anything())
  })

  it('degrades to silent audio (never a crash) when the context cannot be created', () => {
    const { mixer, log } = makeMixer({
      createAudioContext: () => {
        throw new Error('no audio hardware')
      }
    })
    expect(() => mixer.attachStream('game', { id: 's1' })).not.toThrow()
    expect(log).toHaveBeenCalledWith('error', 'creating the AudioContext failed — no mixer audio', expect.anything())
  })

  it('replacing a stream disconnects the previous source and applies the level to the new one', () => {
    const context = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    mixer.setVolume('mic', 0.3)
    mixer.attachStream('mic', { id: 'old' })
    mixer.attachStream('mic', { id: 'new' })
    const [oldSource, newSource] = context.sources
    expect(oldSource.disconnectCount).toBe(1)
    expect(newSource.disconnectCount).toBe(0)
    expect(newSource.connectedTo).toEqual([context.gains[0]])
    expect(context.gains[0].gain.value).toBe(0.3)
  })

  it('detaching a stream disconnects its source but keeps the channel gain for the next stream', () => {
    const context = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    mixer.attachStream('mic', { id: 's1' })
    mixer.detachStream('mic')
    expect(context.sources[0].disconnectCount).toBe(1)
    mixer.attachStream('mic', { id: 's2' })
    expect(context.gains).toHaveLength(1) // the same gain node is reused
    expect(context.sources[1].connectedTo).toEqual([context.gains[0]])
  })
})

describe('AudioMixer — independent volume and mute', () => {
  it('changes only the addressed channel', () => {
    const context = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    mixer.attachStream('game', { id: 's1' })
    mixer.attachStream('mic', { id: 's2' })
    mixer.setVolume('game', 0.5)
    expect(context.gains[0].gain.value).toBe(0.5)
    expect(context.gains[1].gain.value).toBe(1)
    mixer.setMuted('mic', true)
    expect(context.gains[0].gain.value).toBe(0.5)
    expect(context.gains[1].gain.value).toBe(0)
    mixer.setMuted('mic', false)
    expect(context.gains[1].gain.value).toBe(1) // volume survived the mute
  })

  it('a level set before the stream arrives is applied when it attaches', () => {
    const context = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    mixer.setVolume('game',0.25)
    mixer.setMuted('mic', true)
    mixer.attachStream('game', { id: 's1' })
    mixer.attachStream('mic', { id: 's2' })
    expect(context.gains[0].gain.value).toBe(0.25)
    expect(context.gains[1].gain.value).toBe(0)
  })

  it('clamps out-of-range volumes', () => {
    const context = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    mixer.setVolume('game', 2)
    expect(mixer.getLevels().game.volume).toBe(1)
    mixer.setVolume('game', -1)
    expect(mixer.getLevels().game.volume).toBe(0)
  })
})

describe('AudioMixer — persistence', () => {
  it('persists level changes and restores them on the next launch', () => {
    const storage = new FakeStorage()
    const first = makeMixer({ storage })
    first.mixer.setVolume('game', 0.7)
    first.mixer.setMuted('mic', true)
    // A relaunch with the same storage (same key, same schema).
    const second = makeMixer({ storage })
    expect(second.mixer.getLevels()).toEqual({
      game: { volume: 0.7, muted: false },
      mic: { volume: 1, muted: true }
    })
    const context = new FakeContext()
    const third = makeMixer({ storage, createAudioContext: () => context })
    third.mixer.attachStream('game', { id: 's1' })
    third.mixer.attachStream('mic', { id: 's2' })
    expect(context.gains[0].gain.value).toBe(0.7)
    expect(context.gains[1].gain.value).toBe(0)
  })

  it('ignores corrupt or out-of-schema storage — fresh defaults, no crash', () => {
    const corrupt = makeMixer({
      storage: new FakeStorage({ 'zfc.audio-mixer.v1': '{not json' }),
      log: vi.fn() as unknown as MixerLogger
    })
    expect(corrupt.mixer.getLevels()).toEqual({
      game: { volume: 1, muted: false },
      mic: { volume: 1, muted: false }
    })
    const wrongShape = makeMixer({
      storage: new FakeStorage({ 'zfc.audio-mixer.v1': JSON.stringify({ game: 'loud' }) })
    })
    expect(wrongShape.mixer.getLevels().game).toEqual({ volume: 1, muted: false })
  })

  it('clamps a stored volume that is out of range on restore', () => {
    const seeded = new FakeStorage({
      'zfc.audio-mixer.v1': JSON.stringify({ game: { volume: 5, muted: false } })
    })
    const { mixer } = makeMixer({ storage: seeded })
    expect(mixer.getLevels().game.volume).toBe(1)
  })
})

describe('AudioMixer — audio output routing (issue #54)', () => {
  it('defaults to the speakers route: gains feed the destination only', () => {
    const context = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    expect(mixer.getRoutingMode()).toBe('speakers')
    mixer.attachStream('game', { id: 's1' })
    expect(context.destinations).toHaveLength(0) // no send tap exists
    expect(context.gains[0].connectedTo).toEqual([context.destination])
  })

  it('cable mode: gains leave the speakers and feed the send tap; the cable context is built with the device as its sink', () => {
    const contexts: Array<{ context: FakeContext; sinkId: string | undefined }> = []
    const { mixer, log } = makeMixer({
      createAudioContext: (options) => {
        const context = new FakeContext()
        contexts.push({ context, sinkId: options?.sinkId })
        return context
      }
    })
    mixer.attachStream('game', { id: 's1' })
    mixer.attachStream('mic', { id: 's2' })
    mixer.setRoutingMode('cable', 'BH-DEVICE-ID')
    expect(log).toHaveBeenCalledWith('info', 'audio routing changed', expect.anything())
    // contexts[0] is the main context (created by the first attach), contexts[1]
    // the cable context (created on demand for the route).
    const [mainContext, cableContext] = contexts.map((entry) => entry.context)
    expect(contexts[1].sinkId).toBe('BH-DEVICE-ID')
    // The send tap exists exactly once and both gains feed it.
    expect(mainContext.destinations).toHaveLength(1)
    const sendTap = mainContext.destinations[0]
    expect(mainContext.gains[0].connectedTo).toEqual([sendTap])
    expect(mainContext.gains[1].connectedTo).toEqual([sendTap])
    // The cable side: one context, sink set, playing the tap's stream.
    expect(cableContext.sources).toHaveLength(1)
    expect(cableContext.sources[0].stream).toBe(sendTap.stream)
    expect(cableContext.sources[0].connectedTo).toEqual([cableContext.destination])
  })

  it('both mode: gains feed the speakers and the send tap at the same time', () => {
    const mainContext = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => mainContext })
    mixer.attachStream('game', { id: 's1' })
    mixer.setRoutingMode('both', 'BH-DEVICE-ID')
    expect(mainContext.gains[0].connectedTo).toEqual([
      mainContext.destination,
      mainContext.destinations[0]
    ])
  })

  it('a cable route without a device is refused — mode unchanged, no silent cast, warn logged', () => {
    const context = new FakeContext()
    const { mixer, log } = makeMixer({ createAudioContext: () => context })
    mixer.attachStream('game', { id: 's1' })
    mixer.setRoutingMode('cable')
    expect(mixer.getRoutingMode()).toBe('speakers')
    expect(log).toHaveBeenCalledWith(
      'warn',
      'cable routing refused: no virtual cable device is known',
      expect.anything()
    )
    expect(context.destinations).toHaveLength(0) // no send tap ever built
    expect(context.gains[0].connectedTo).toEqual([context.destination])
  })

  it('returning to speakers closes the cable context and rewires the gains back', () => {
    const contexts: FakeContext[] = []
    const { mixer } = makeMixer({
      createAudioContext: () => {
        const context = new FakeContext()
        contexts.push(context)
        return context
      }
    })
    mixer.attachStream('game', { id: 's1' })
    mixer.setRoutingMode('cable', 'BH-DEVICE-ID')
    mixer.setRoutingMode('speakers')
    // contexts[0] is the main context, contexts[1] the cable context: only
    // the cable side is torn down; the mixer itself must never close.
    expect(contexts[1].closed).toBe(true)
    expect(contexts[0].closed).toBe(false)
    expect(contexts[0].gains[0].connectedTo).toEqual([contexts[0].destination])
    expect(contexts[0].destinations).toHaveLength(1) // the tap persists, unused
  })

  it('a stream attached while in cable mode is routed straight to the tap, never to the speakers', () => {
    const mainContext = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => mainContext })
    mixer.setRoutingMode('cable', 'BH-DEVICE-ID')
    mixer.attachStream('game', { id: 's1' })
    expect(mainContext.gains[0].connectedTo).toEqual([mainContext.destinations[0]])
  })

  it('volume and mute keep working on the cable route (shared gains, shared levels)', () => {
    const mainContext = new FakeContext()
    const { mixer } = makeMixer({ createAudioContext: () => mainContext })
    mixer.setRoutingMode('cable', 'BH-DEVICE-ID')
    mixer.attachStream('game', { id: 's1' })
    mixer.setVolume('game', 0.4)
    mixer.setMuted('game', true)
    expect(mainContext.gains[0].gain.value).toBe(0)
    mixer.setMuted('game', false)
    expect(mainContext.gains[0].gain.value).toBe(0.4)
  })

  it('the routing mode persists across a relaunch and restores the cable route on the first stream', () => {
    const storage = new FakeStorage()
    const first = makeMixer({ storage })
    first.mixer.setRoutingMode('both', 'BH-DEVICE-ID')
    const stored = JSON.parse(storage.getItem('zfc.audio-routing.v1') ?? '{}')
    expect(stored).toEqual({ mode: 'both' })
    const second = makeMixer({ storage })
    expect(second.mixer.getRoutingMode()).toBe('both')
    const context = new FakeContext()
    const relaunched = makeMixer({ storage, createAudioContext: () => context })
    relaunched.mixer.attachStream('game', { id: 's1' })
    expect(context.gains[0].connectedTo).toEqual([context.destination, context.destinations[0]])
  })

  it('an invalid stored routing mode falls back to speakers with a warn', () => {
    const seeded = new FakeStorage({ 'zfc.audio-routing.v1': JSON.stringify({ mode: 'bluetooth' }) })
    const { mixer, log } = makeMixer({ storage: seeded })
    expect(mixer.getRoutingMode()).toBe('speakers')
    expect(log).toHaveBeenCalledWith('warn', 'stored routing mode was invalid — using speakers', expect.anything())
  })

  it('a cable AudioContext that cannot be created logs an error and keeps the mixer alive', () => {
    const mainContext = new FakeContext()
    let cableCreations = 0
    const { mixer, log } = makeMixer({
      createAudioContext: (options) => {
        if (options?.sinkId !== undefined) {
          cableCreations += 1
          throw new Error('device gone')
        }
        return mainContext
      }
    })
    mixer.attachStream('game', { id: 's1' })
    mixer.setRoutingMode('cable', 'BH-DEVICE-ID')
    expect(cableCreations).toBe(1)
    expect(log).toHaveBeenCalledWith('error', 'creating the cable AudioContext failed — no cable audio', expect.anything())
    // The route still left the speakers (that was requested); the mix flows
    // into the tap but the dead cable side stays silent.
    expect(mixer.getRoutingMode()).toBe('cable')
    expect(mainContext.gains[0].connectedTo).toEqual([mainContext.destinations[0]])
  })
})

describe('AudioMixer — per-track cable routing (ADR-008 batch A + review fixes)', () => {
  const attachBoth = (mixer: AudioMixer): void => {
    mixer.attachStream('game', { id: 'game-stream' })
    mixer.attachStream('mic', { id: 'mic-stream' })
  }

  it('builds one cable context per track, sinked to its own device', () => {
    const contexts: FakeContext[] = []
    const { mixer } = makeMixer({
      createAudioContext: (options) => {
        const context = new FakeContext()
        contexts.push(context)
        if (options?.sinkId !== undefined) context.sinkId = options.sinkId
        return context
      }
    })
    attachBoth(mixer)
    mixer.setPerTrackCableDevices({ game: 'zf-game', mic: 'zf-mic' })
    mixer.setRoutingMode('cable', 'zf-game')
    // main + one cable context per track
    expect(contexts).toHaveLength(3)
    expect(contexts.filter((context) => context.sinkId === 'zf-game')).toHaveLength(1)
    expect(contexts.filter((context) => context.sinkId === 'zf-mic')).toHaveLength(1)
  })

  it('switching back to speakers closes the per-track contexts (the review leak)', () => {
    const contexts: FakeContext[] = []
    const { mixer } = makeMixer({
      createAudioContext: (options) => {
        const context = new FakeContext()
        contexts.push(context)
        if (options?.sinkId !== undefined) context.sinkId = options.sinkId
        return context
      }
    })
    attachBoth(mixer)
    mixer.setPerTrackCableDevices({ game: 'zf-game', mic: 'zf-mic' })
    mixer.setRoutingMode('cable', 'zf-game')
    mixer.setRoutingMode('speakers')
    const cableContexts = contexts.slice(1)
    for (const context of cableContexts) {
      expect(context.closed).toBe(true)
    }
  })

  it('a changed device id rebuilds that track context on the new sink', () => {
    const contexts: FakeContext[] = []
    const { mixer } = makeMixer({
      createAudioContext: (options) => {
        const context = new FakeContext()
        contexts.push(context)
        if (options?.sinkId !== undefined) context.sinkId = options.sinkId
        return context
      }
    })
    attachBoth(mixer)
    mixer.setPerTrackCableDevices({ game: 'zf-game', mic: 'zf-mic' })
    mixer.setRoutingMode('cable', 'zf-game')
    mixer.setPerTrackCableDevices({ game: 'zf-game-2', mic: 'zf-mic' })
    const gameContexts = contexts.filter((context) => context.sinkId === 'zf-game' || context.sinkId === 'zf-game-2')
    expect(gameContexts).toHaveLength(2)
    expect(gameContexts[0].closed).toBe(true) // the old sink is gone
    expect(gameContexts[1].sinkId).toBe('zf-game-2')
  })
})

describe('receiver-side channel probe (issue #101)', () => {
  class FakeAnalyser implements AnalyserLike {
    constructor(readonly level: number) {}
    getFloatTimeDomainData(array: Float32Array): void {
      for (let i = 0; i < array.length; i++) array[i] = i % 2 === 0 ? this.level : -this.level
    }
  }

  class FakeSplitter implements ChannelSplitterLike {
    connected: Array<[unknown, number | undefined]> = []
    disconnectCount = 0
    connect(node: unknown, output?: number): unknown {
      this.connected.push([node, output])
      return node
    }
    disconnect(): void {
      this.disconnectCount += 1
    }
  }

  class ProbingContext extends FakeContext {
    readonly splitter = new FakeSplitter()
    createChannelSplitter(): ChannelSplitterLike {
      return this.splitter
    }

    createAnalyser(): AnalyserLike {
      // Left channel loud, right channel quiet — the shape the probe must report.
      this.analysers.push(new FakeAnalyser(this.analysers.length === 0 ? 0.5 : 0.01))
      return this.analysers[this.analysers.length - 1]
    }

    analysers: FakeAnalyser[] = []
  }

  it('logs per-channel levels of the attached stream every 2 s', () => {
    vi.useFakeTimers()
    const context = new ProbingContext()
    const logs: Array<{ level: string; message: string; details?: Record<string, unknown> }> = []
    const mixer = new AudioMixer({
      createAudioContext: () => context,
      createWorkletNode: () => new FakeWorkletNode(),
      log: (level, message, details) => logs.push({ level, message, details })
    })
    mixer.attachStream('game', { id: 'stream' })
    expect(context.splitter.connected).toHaveLength(2) // splitter taps both channels

    vi.advanceTimersByTime(2_000)
    const report = logs.filter((entry) => entry.message === 'received stream channels')
    expect(report).toHaveLength(1)
    expect(report[0].details?.left).toBeGreaterThan(0.4)
    expect(report[0].details?.right).toBeLessThan(0.05)

    vi.advanceTimersByTime(2_000)
    expect(logs.filter((entry) => entry.message === 'received stream channels')).toHaveLength(2)

    mixer.detachStream('game')
    vi.advanceTimersByTime(6_000)
    expect(logs.filter((entry) => entry.message === 'received stream channels')).toHaveLength(2) // stopped
    vi.useRealTimers()
  })

  it('skips the probe silently on a context without analysers', () => {
    const logs: Array<{ level: string; message: string }> = []
    const mixer = new AudioMixer({
      createAudioContext: () => new FakeContext(),
      createWorkletNode: () => new FakeWorkletNode(),
      log: (level, message) => logs.push({ level, message })
    })
    mixer.attachStream('game', { id: 'stream' })
    expect(logs.some((entry) => entry.message === 'channel probe unavailable')).toBe(false)
  })
})

/**
 * ADR-012's PCM game-audio path: the DataChannel chunks arrive as Int16LE
 * stereo frames; the mixer converts them to Float32, feeds the worklet ring
 * behind the game gain, keeps the dual-mono track stashed (echo guard), and
 * hands the path back to the track when the channel goes quiet.
 */
describe('game audio PCM path (ADR-012)', () => {
  /** Two stereo Int16LE frames: L=16384/8192, R=-16384/-8192 (convert to 0.5/0.25 and −0.5/−0.25). */
  const chunk = (() => {
    const view = new DataView(new ArrayBuffer(8))
    view.setInt16(0, 16384, true)
    view.setInt16(2, -16384, true)
    view.setInt16(4, 8192, true)
    view.setInt16(6, -8192, true)
    return view.buffer
  })()

  it('returns null with a log line when the context has no AudioWorklet', async () => {
    const context = new FakeContext()
    const { mixer, log } = makeMixer({ createAudioContext: () => context })
    expect(await mixer.attachGamePcmSource()).toBeNull()
    expect(log).toHaveBeenCalledWith('warn', 'game pcm unavailable: AudioWorklet missing in this context')
  })

  it('attaches the worklet to the game gain and stashes an attached track source', async () => {
    const context = new FakeContext()
    const host = context.enableWorkletHost()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    mixer.attachStream('game', { id: 'stream' })
    const trackSource = context.sources[0]

    const feed = await mixer.attachGamePcmSource()
    expect(feed).not.toBeNull()
    expect(host.addedModules).toHaveLength(1)
    expect(host.node.connectedTo).toContainEqual(context.gains[0])
    // Echo guard: the dual-mono track leaves the audible path.
    expect(trackSource.disconnectCount).toBeGreaterThanOrEqual(1)
  })

  it('push converts Int16LE stereo frames to Float32 and feeds the worklet port', async () => {
    const context = new FakeContext()
    const host = context.enableWorkletHost()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    const feed = (await mixer.attachGamePcmSource())!
    feed.push(chunk)

    expect(host.node.port.postMessage).toHaveBeenCalledTimes(1)
    const message = host.node.port.postMessage.mock.calls[0][0] as { left: Float32Array; right: Float32Array }
    expect(Array.from(message.left)).toEqual([0.5, 0.25])
    expect(Array.from(message.right)).toEqual([-0.5, -0.25])
  })

  it('a stream arriving while PCM is active is stashed, not wired to the gain', async () => {
    const context = new FakeContext()
    context.enableWorkletHost()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    await mixer.attachGamePcmSource()
    const gain = context.gains[0]
    const connectedBefore = gain.connectedTo.length

    mixer.attachStream('game', { id: 'late-stream' })
    expect(context.sources[0].connectedTo).toHaveLength(0) // stashed, silent
    expect(gain.connectedTo.slice(connectedBefore)).not.toContainEqual(context.sources[0])
  })

  it('a quiet channel falls back to the stashed track, and PCM re-claims on arrival', async () => {
    vi.useFakeTimers()
    try {
      const context = new FakeContext()
      const host = context.enableWorkletHost()
      const { mixer } = makeMixer({ createAudioContext: () => context })
      mixer.attachStream('game', { id: 'stream' })
      const trackSource = context.sources[0]
      const gain = context.gains[0]
      const feed = (await mixer.attachGamePcmSource())!
      feed.push(chunk)

      vi.advanceTimersByTime(4_000) // quiet past the 3 s grace window (1 s ticks)
      expect(trackSource.connectedTo).toContainEqual(gain) // track back on the path
      expect(host.node.disconnectCount).toBeGreaterThanOrEqual(1)

      feed.push(chunk) // PCM resumes — re-claims the path
      expect(trackSource.disconnectCount).toBeGreaterThanOrEqual(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('detach stops the fallback timer and disconnects the worklet', async () => {
    vi.useFakeTimers()
    try {
      const context = new FakeContext()
      const host = context.enableWorkletHost()
      const { mixer } = makeMixer({ createAudioContext: () => context })
      const feed = (await mixer.attachGamePcmSource())!
      feed.detach()

      vi.advanceTimersByTime(10_000)
      expect(host.node.disconnectCount).toBeGreaterThanOrEqual(1)
      // No fallback ticks fired after detach — the log stays silent.
    } finally {
      vi.useRealTimers()
    }
  })
})

/**
 * ADR-013: the AAC path's decoded frames enter the same worklet through
 * pushFrames — the identical message shape, and the same liveness stamp the
 * quiet-fallback timer reads (a decoded stereo stream keeps the track
 * stashed exactly like PCM does).
 */
describe('game audio decoded frames (ADR-013 pushFrames)', () => {
  it('posts the decoded pair to the worklet port unchanged', async () => {
    const context = new FakeContext()
    const host = context.enableWorkletHost()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    const feed = (await mixer.attachGamePcmSource())!

    feed.pushFrames(new Float32Array([0.5, 0.25]), new Float32Array([-0.5, -0.25]))

    expect(host.node.port.postMessage).toHaveBeenCalledTimes(1)
    const message = host.node.port.postMessage.mock.calls[0][0] as { left: Float32Array; right: Float32Array }
    expect(Array.from(message.left)).toEqual([0.5, 0.25])
    expect(Array.from(message.right)).toEqual([-0.5, -0.25])
  })

  it('decoded frames keep the path alive and re-claim it after a fallback', async () => {
    vi.useFakeTimers()
    try {
      const context = new FakeContext()
      context.enableWorkletHost()
      const { mixer } = makeMixer({ createAudioContext: () => context })
      mixer.attachStream('game', { id: 'stream' })
      const trackSource = context.sources[0]
      const gain = context.gains[0]
      const feed = (await mixer.attachGamePcmSource())!
      feed.pushFrames(new Float32Array([0]), new Float32Array([0]))

      vi.advanceTimersByTime(4_000) // quiet past the 3 s grace window
      expect(trackSource.connectedTo).toContainEqual(gain)

      feed.pushFrames(new Float32Array([0]), new Float32Array([0])) // AAC resumes
      expect(trackSource.disconnectCount).toBeGreaterThanOrEqual(2)
    } finally {
      vi.useRealTimers()
    }
  })
})

/**
 * ADR-013 follow-up: the worklet's ring-depth telemetry (~1 Hz) lands on the
 * mixer — the playout-tuning input. Depth is null until the first report and
 * resets on detach, so a dead path never reports a stale value.
 */
describe('game ring depth telemetry', () => {
  it('is null before the first worklet report', async () => {
    const context = new FakeContext()
    context.enableWorkletHost()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    await mixer.attachGamePcmSource()
    expect(mixer.gameRingDepthMs()).toBeNull()
  })

  it('follows the worklet report and resets on detach', async () => {
    const context = new FakeContext()
    const host = context.enableWorkletHost()
    const { mixer } = makeMixer({ createAudioContext: () => context })
    const feed = (await mixer.attachGamePcmSource())!

    host.node.port.onmessage?.({ data: { depthMs: 42 } })
    expect(mixer.gameRingDepthMs()).toBe(42)

    host.node.port.onmessage?.({ data: { depthMs: 7 } })
    expect(mixer.gameRingDepthMs()).toBe(7)

    host.node.port.onmessage?.({ data: { notDepth: true } }) // malformed — ignored
    expect(mixer.gameRingDepthMs()).toBe(7)

    feed.detach()
    expect(mixer.gameRingDepthMs()).toBeNull()
  })
})
