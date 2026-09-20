import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ReceiverSession, type Logger, type PeerConnectionLike, type SignalingOut, type VideoSink } from './receiverSession'

/**
 * Behavior tests for the desktop's answerer with a scripted fake pc — the
 * offer→answer flow, ICE relay/queueing, rendering, stats, and teardown. No
 * real RTCPeerConnection exists in plain Node; production wires Chromium's.
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

interface TestSink extends VideoSink {
  shown: unknown[]
  cleared: number
}

interface Harness {
  session: ReceiverSession
  pcs: FakePc[]
  answers: Array<{ pc: string; sdp: string }>
  candidates: Array<{ pc: string; candidate: unknown }>
  sink: TestSink
  /** The session logger (a vi.fn() at runtime — cast back in assertions). */
  log: Logger
}

function makeHarness(remoteGate: Gate | null = null, configurePc?: (pc: FakePc, index: number) => void): Harness {
  const pcs: FakePc[] = []
  const answers: Harness['answers'] = []
  const candidates: Harness['candidates'] =[]
  const signaling: SignalingOut = {
    sendSdpAnswer: (pc, sdp) => answers.push({ pc, sdp }),
    sendIceCandidate: (pc, candidate) => candidates.push({ pc, candidate })
  }
  const sink: TestSink = {
    shown: [],
    cleared: 0,
    show: (stream) => sink.shown.push(stream),
    clear: () => {
      sink.cleared += 1
    }
  }
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
    log
  })
  return { session, pcs, answers, candidates, sink, log }
}

describe('ReceiverSession — answering', () => {
  it('answers a media offer and sends the answer over signaling', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    expect(h.pcs).toHaveLength(1)
    expect(h.answers).toEqual([{ pc: 'media', sdp: ANSWER_SDP }])
  })

  it('ignores mic offers — no audio pipeline exists yet', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'mic', sdp: OFFER_SDP })
    expect(h.pcs).toHaveLength(0)
    expect(h.answers).toHaveLength(0)
  })

  it('closes the previous pc when a new offer arrives (mobile rebuilt its pc)', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    expect(h.pcs).toHaveLength(2)
    expect(h.pcs[0].closedCount).toBe(1)
    expect(h.answers).toHaveLength(2)
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

  it('relays local candidates to the mobile verbatim (incl. mDNS shapes)', async () => {
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    const mDnsCandidate = { candidate: 'candidate:2 1 UDP 1 abcdef.local9 typ host', sdpMid: '0', sdpMLineIndex: 0 }
    h.pcs[0].onicecandidate?.({ candidate: mDnsCandidate })
    expect(h.candidates).toEqual([{ pc: 'media', candidate: mDnsCandidate }])
  })
})

describe('ReceiverSession — rendering and teardown', () => {
  it('renders the stream when the remote track arrives', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    const stream = { id: 's1' }
    h.pcs[0].ontrack?.({ track: {}, streams: [stream] })
    expect(h.sink.shown).toEqual([stream])
  })

  it('clears the view and closes the pc when the mobile is gone', async () => {
    const h = makeHarness()
    await h.session.handleSdpOffer({ pc: 'media', sdp: OFFER_SDP })
    h.session.handleMobileGone()
    expect(h.pcs[0].closedCount).toBe(1)
    expect(h.sink.cleared).toBe(1)
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
        { type: 'inbound-rtp', kind: 'video', bytesReceived: 1000, packetsLost: 0, jitter: 0, framesDecoded: 10, framesDropped: 0 },
        { type: 'candidate-pair', nominated: true, state: 'succeeded', currentRoundTripTime: 0.002 }
      ]
      pc.connectionState = 'connected'
      pc.onconnectionstatechange?.()
      await vi.advanceTimersByTimeAsync(1_000)
      expect(logMock).toHaveBeenCalledWith('info', 'stats', expect.objectContaining({ bitrateBps: 8000, rttMs: 2 }))
      pc.connectionState = 'failed'
      pc.onconnectionstatechange?.()
      await vi.advanceTimersByTimeAsync(5_000)
      expect(logMock).toHaveBeenCalledWith('info', 'connection failed')
    } finally {
      vi.useRealTimers()
    }
  })
})
