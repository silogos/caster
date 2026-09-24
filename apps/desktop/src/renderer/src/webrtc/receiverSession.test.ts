import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ReceiverSession, type AudioSink, type Logger, type PeerConnectionLike, type SignalingOut, type VideoSink } from './receiverSession'

/**
 * Behavior tests for the desktop's answerer with a scripted fake pc — the
 * offer→answer flow, ICE relay/queueing, rendering, stats, and teardown, for
 * both pcs (media + mic, Phase8). No real RTCPeerConnection exists in plain
 * Node; production wires Chromium's.
 */

const OFFER_SDP = 'v=0\r\n…offer…'
const ANSWER_SDP = 'v=0\r\n…answer…'
const ICE_INIT = { candidate: 'candidate:1 1 UDP192.168.1.2050000 typ host', sdpMid: '0', sdpMLineIndex: 0 }

/** Deferred control point so a test can hold setRemoteDescription mid-flight. */
class Gate {
  private readonly promise: Promise<void>
  private openFn: (() => void) | null = null

  constructor() {
    this.promise = new Promise((resolve) => {
      this.openFn = resolve
    })
  }

  wait(): Promise<void> {
    return this.promise
  }

  open(): void {
    this.openFn?.()
  }
}

class FakePc implements PeerConnectionLike {
  addedRemoteCandidates: unknown[] = []
  closedCount = 0
  connectionState = 'new'
  statsEntries: Array<Record<string, unknown>> = []
  /** Chromium's maplike report yields [id, stats] tuples — tests cover both shapes. */
  statsAsTuples = false
  /** Make the pc fail at a chosen step to test teardown paths. */
  failAt: 'setRemoteDescription' | 'createAnswer' | null = null

  onicecandidate: ((event: { candidate: unknown }) => void) | null = null
  onconnectionstatechange: (() => void) | null = null
  ontrack: ((event: { track: unknown; streams: unknown[] }) => void) | null = null

  constructor(private readonly remoteGate: Gate | null = null) {}

  async setRemoteDescription(): Promise<void> {
    if (this.failAt === 'setRemoteDescription') throw new Error('bad offer')
    if (this.remoteGate !== null) await this.remoteGate.wait()
  }

  async createAnswer(): Promise<{ type: string; sdp: string }> {
    if (this.failAt === 'createAnswer') throw new Error('cannot answer')
    return { type: 'answer', sdp: ANSWER_SDP }
  }

  async setLocalDescription(): Promise<void> {}

  async addIceCandidate(candidate: unknown): Promise<void> {
    this.addedRemoteCandidates.push(candidate)
  }

  async getStats(): Promise<Array<unknown>> {
    return this.statsAsTuples
      ? this.statsEntries.map((entry, index) => [`id-${String(index)}`, entry])
      : this.statsEntries
  }

  close(): void {
    this.closedCount += 1
  }
}

interface TestSink extends VideoSink, AudioSink {
  shown: unknown[]
  cleared: number
}

interface Harness {
  session: ReceiverSession
  pcs: FakePc[]
  answers: Array<{ pc: string; sdp: string }>
  candidates: Array<{ pc: string; candidate: unknown }>
  sink: TestSink
  micSink: TestSink
  /** The session logger (a vi.fn() at runtime — cast back in assertions). */
  log: Logger
}

function makeSink(): TestSink {
  const sink: TestSink = {
    shown: [],
    cleared: 0,
    show: (stream) => sink.shown.push(stream),
    clear: () => {
      sink.cleared += 1
    }
  }
  return sink
}

function makeHarness(remoteGate: Gate | null = null, configurePc?: (pc: FakePc, index: number) => void): Harness {
  const pcs: FakePc[] = []
  const answers: Harness['answers'] = []
  const candidates: Harness['candidates'] = []
  const signaling: SignalingOut = {
    sendSdpAnswer: (pc, sdp) => answers.push({ pc, sdp }),
    sendIceCandidate: (pc, candidate) => candidates.push({ pc, candidate })
  }
  const sink = makeSink()
  const micSink = makeSink()
  const log = vi.fn() as unknown as Logger
  const session = new ReceiverSession({
    createPeerConnection: () => {
      const pc = new FakePc(remoteGate)
      configurePc?.(pc, pcs.length)
      pcs.push(pc)
      return pc
    },
    signaling,
    sink,
    micSink,
    log
  })
  return { session, pcs, answers, candidates, sink, micSink, log }
}

describe('ReceiverSession — answering', () => {
  it('answers a media offer and sends the answer over signaling', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    expect(h.pcs).toHaveLength(1)
    expect(h.answers).toEqual([{ pc: 'media', sdp: ANSWER_SDP }])
  })

  it('answers a mic offer on its own pc (Phase8)', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    expect(h.pcs).toHaveLength(1)
    expect(h.answers).toEqual([{ pc: 'mic', sdp: ANSWER_SDP }])
  })

  it('closes the previous pc when a new offer arrives (mobile rebuilt its pc)', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    expect(h.pcs).toHaveLength(2)
    expect(h.pcs[0].closedCount).toBe(1)
    expect(h.answers).toHaveLength(2)
  })

  it('a fresh mic offer replaces only the mic pc — the media pc keeps streaming', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    expect(h.pcs).toHaveLength(3)
    expect(h.pcs[0].closedCount).toBe(0) // media pc untouched by mic churn
    expect(h.pcs[1].closedCount).toBe(1)
    expect(h.answers).toHaveLength(3)
  })

  it('tears down the pc and clears the view when answering fails', async () => {
    const h = makeHarness(null, (pc, index) => {
      if (index === 1) pc.failAt = 'createAnswer'
    })
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    expect(h.pcs[1].closedCount).toBe(1)
    expect(h.sink.cleared).toBe(1)
    expect(h.answers).toHaveLength(1) // only the first, good answer
  })

  it('a failed mic answer clears only the mic sink — the media cast is unaffected', async () => {
    const h = makeHarness(null, (pc, index) => {
      if (index === 1) pc.failAt = 'createAnswer'
    })
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    expect(h.micSink.cleared).toBe(1)
    expect(h.sink.cleared).toBe(0)
    expect(h.pcs[0].closedCount).toBe(0) // media pc untouched by the mic failure
    expect(h.pcs[1].closedCount).toBe(1)
    expect(h.answers).toEqual([{ pc: 'media', sdp: ANSWER_SDP }])
  })
})

describe('ReceiverSession — ICE', () => {
  let h: Harness

  beforeEach(() => {
    h = makeHarness()
  })

  it('applies mobile candidates once the remote description is set', async () => {
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleIceCandidate({ pc: 'media', candidate: ICE_INIT })
    expect(h.pcs[0].addedRemoteCandidates).toEqual([ICE_INIT])
  })

  it('routes mic candidates to the mic pc, not the media pc', async () => {
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    await h.session.handleIceCandidate({ pc: 'mic', candidate: ICE_INIT })
    expect(h.pcs[0].addedRemoteCandidates).toHaveLength(0)
    expect(h.pcs[1].addedRemoteCandidates).toEqual([ICE_INIT])
  })

  it('queues candidates that arrive before the offer is processed', async () => {
    const gate = new Gate()
    const held = makeHarness(gate)
    const answering = held.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await held.session.handleIceCandidate({ pc: 'media', candidate: ICE_INIT })
    expect(held.pcs[0].addedRemoteCandidates).toHaveLength(0)
    gate.open()
    await answering
    expect(held.pcs[0].addedRemoteCandidates).toEqual([ICE_INIT])
  })

  it('treats candidate null as end-of-gathering, not a candidate to add', async () => {
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleIceCandidate({ pc: 'media', candidate: null })
    expect(h.pcs[0].addedRemoteCandidates).toHaveLength(0)
  })

  it('relays local candidates to the mobile verbatim, per pc (incl. mDNS shapes)', async () => {
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    const mDnsCandidate = { candidate: 'candidate:2 1 UDP1 abcdef.local9 typ host', sdpMid: '0', sdpMLineIndex: 0 }
    h.pcs[0].onicecandidate?.({ candidate: mDnsCandidate })
    h.pcs[1].onicecandidate?.({ candidate: mDnsCandidate })
    expect(h.candidates).toEqual([
      { pc: 'media', candidate: mDnsCandidate },
      { pc: 'mic', candidate: mDnsCandidate }
    ])
  })

  it('serializes an RTCIceCandidate-shaped candidate before the IPC relay (Phase15)', async () => {
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    // Chromium's RTCIceCandidate is not structured-cloneable — Electron's
    // IPC delivered {} to the main process, so the mobile never saw our host
    // candidates. toJSON() produces the RTCIceCandidateInit wire shape.
    const init = { candidate: 'candidate:11 UDP192.168.1.13152341 typ host', sdpMid: '0', sdpMLineIndex: 0 }
    const rtcCandidate = {
      toJSON: () => init
    }
    h.pcs[0].onicecandidate?.({ candidate: rtcCandidate })
    expect(h.candidates).toEqual([{ pc: 'media', candidate: init }])
    // End-of-gathering (null) must stay null, not become a serialized object.
    h.pcs[0].onicecandidate?.({ candidate: null })
    expect(h.candidates).toHaveLength(2)
    expect(h.candidates[1]).toEqual({ pc: 'media', candidate: null })
  })
})

describe('ReceiverSession — rendering and teardown', () => {
  it('renders the media stream on the video sink and the mic stream on the audio sink', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    const mediaStream = { id: 's1' }
    const micStream = { id: 's2' }
    h.pcs[0].ontrack?.({ track: { kind: 'video' }, streams: [mediaStream] })
    h.pcs[1].ontrack?.({ track: { kind: 'audio' }, streams: [micStream] })
    expect(h.sink.shown).toEqual([mediaStream])
    expect(h.micSink.shown).toEqual([micStream])
  })

  it('clears the view and closes both pcs when the mobile is gone', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    h.session.handleMobileGone()
    expect(h.pcs[0].closedCount).toBe(1)
    expect(h.pcs[1].closedCount).toBe(1)
    expect(h.sink.cleared).toBe(1)
    expect(h.micSink.cleared).toBe(1)
  })

  it('a dead mic pc clears only the mic sink — mic toggled off mid-cast', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    const mediaPc = h.pcs[0]
    const micPc = h.pcs[1]
    micPc.connectionState = 'closed' // the mobile closed its mic pc (toggle off)
    micPc.onconnectionstatechange?.()
    expect(h.micSink.cleared).toBe(1)
    expect(h.sink.cleared).toBe(0)
    expect(mediaPc.closedCount).toBe(0)
    expect(micPc.closedCount).toBe(1)
  })

  it('polls getStats once per interval while connected and stops on failure', async () => {
    vi.useFakeTimers()
    try {
      const h = makeHarness()
      const logMock = h.log as unknown as ReturnType<typeof vi.fn>
      await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
      const pc = h.pcs[0]
      pc.statsAsTuples = true // production shape: RTCStatsReport is maplike
      pc.statsEntries = [
        {
          type: 'inbound-rtp',
          kind: 'video',
          bytesReceived: 1000,
          packetsLost: 0,
          jitter: 0,
          framesDecoded: 10,
          framesDropped: 0,
          framesPerSecond: 29.97,
          jitterBufferDelay: 0.05,
          jitterBufferEmittedCount: 10,
          freezeCount: 0,
          keyFramesDecoded: 1,
          pliCount: 0,
          nackCount: 2,
          frameWidth: 1280,
          frameHeight: 720
        },
        { type: 'inbound-rtp', kind: 'audio', bytesReceived: 500, packetsLost: 0, jitter: 0.001, concealedSamples: 0, totalSamplesReceived: 48_000 },
        { type: 'candidate-pair', nominated: true, state: 'succeeded', currentRoundTripTime: 0.002 }
      ]
      pc.connectionState = 'connected'
      pc.onconnectionstatechange?.()
      await vi.advanceTimersByTimeAsync(1_000)
      // Phase16: the line now carries the decode/render fields and the
      // game-audio track that rides the media pc.
      expect(logMock).toHaveBeenCalledWith(
        'info',
        'stats',
        expect.objectContaining({
          bitrateBps: 8000,
          rttMs: 2,
          fps: 30,
          jitterBufferMs: 5,
          frame: '1280x720',
          nackCount: 2,
          keyFramesDecoded: 1,
          gameAudio: { bitrateBps: 4000, packetsLost: 0, jitterMs: 1, concealmentPct: 0 }
        })
      )
      pc.connectionState = 'failed'
      pc.onconnectionstatechange?.()
      await vi.advanceTimersByTimeAsync(5_000)
      expect(logMock).toHaveBeenCalledWith('info', 'connection failed (pc=media)')
    } finally {
      vi.useRealTimers()
    }
  })

  it('polls the mic pc as its own audio line, never the video stats line (Phase16)', async () => {
    vi.useFakeTimers()
    try {
      const h = makeHarness()
      const logMock = h.log as unknown as ReturnType<typeof vi.fn>
      await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
      const pc = h.pcs[0]
      pc.statsEntries = [
        { type: 'inbound-rtp', kind: 'audio', bytesReceived: 48_000, packetsLost: 1, jitter: 0.002, concealedSamples: 48, totalSamplesReceived: 96_000 },
        { type: 'candidate-pair', nominated: true, state: 'succeeded', currentRoundTripTime: 0.004 }
      ]
      pc.connectionState = 'connected'
      pc.onconnectionstatechange?.()
      await vi.advanceTimersByTimeAsync(1_000)
      expect(logMock).toHaveBeenCalledWith(
        'info',
        'mic stats',
        expect.objectContaining({ bitrateBps: 384_000, packetsLost: 1, jitterMs: 2, concealmentPct: 0.1 })
      )
      expect(logMock).not.toHaveBeenCalledWith('info', 'stats', expect.anything())
      expect(logMock).toHaveBeenCalledWith('info', 'connection connected (pc=mic)')
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops the mic pc polling when the mic pc goes away (toggle off)', async () => {
    vi.useFakeTimers()
    try {
      const h = makeHarness()
      const logMock = h.log as unknown as ReturnType<typeof vi.fn>
      await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
      const pc = h.pcs[0]
      pc.statsEntries = [{ type: 'inbound-rtp', kind: 'audio', bytesReceived: 1_000 }]
      pc.connectionState = 'connected'
      pc.onconnectionstatechange?.()
      await vi.advanceTimersByTimeAsync(1_000)
      expect(logMock).toHaveBeenCalledWith('info', 'mic stats', expect.anything())
      pc.connectionState = 'closed'
      pc.onconnectionstatechange?.()
      const logged = logMock.mock.calls.length
      await vi.advanceTimersByTimeAsync(5_000)
      expect(logMock.mock.calls.length).toBe(logged) // the poll died with the pc
    } finally {
      vi.useRealTimers()
    }
  })

  it('statsSnapshot returns one normalized report per active pc (Phase15 debug hook)', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    const mediaPc = h.pcs[0]
    mediaPc.statsAsTuples = true // production shape: RTCStatsReport is maplike
    mediaPc.statsEntries = [{ type: 'inbound-rtp', kind: 'video', bytesReceived: 5000 }]
    h.pcs[1].statsEntries = [{ type: 'inbound-rtp', kind: 'audio', bytesReceived: 500 }]

    const snapshot = await h.session.statsSnapshot()
    expect(Object.keys(snapshot).sort()).toEqual(['media', 'mic'])
    expect(snapshot.media).toEqual([{ type: 'inbound-rtp', kind: 'video', bytesReceived: 5000 }])
    expect(snapshot.mic).toEqual([{ type: 'inbound-rtp', kind: 'audio', bytesReceived: 500 }])

    // Torn-down pcs disappear from the snapshot (mobile gone → empty).
    h.session.handleMobileGone()
    expect(await h.session.statsSnapshot()).toEqual({})
  })
})
