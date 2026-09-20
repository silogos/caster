import { describe, expect, it, vi } from 'vitest'
import {
  AudioMixer,
  type AudioContextLike,
  type GainNodeLike,
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

class FakeContext implements AudioContextLike {
  readonly destination = { name: 'destination' }
  gains: FakeGain[] = []
  sources: FakeSource[] = []
  resume = vi.fn((): Promise<void> => Promise.resolve())
  state = 'running'

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
  const log = options.log ?? (vi.fn() as unknown as MixerLogger)
  const mixer = new AudioMixer({ createAudioContext, storage: options.storage, log })
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
