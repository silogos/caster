import type { PcId, SignalingIceMessage, SignalingSdpMessage } from '../../../shared/types'
import { bitrateBps, sampleVideoReceive, type VideoReceiveSample } from './stats'

/**
 * The renderer's media half of the desktop (docs/architecture/desktop.md):
 * answer the mobile's offers, glue ICE, and hand the remote video track to the
 * `<video>` sink. The main process owns the socket — this class only speaks the
 * typed IPC surface below, so it is unit-testable in plain Node.
 *
 * The desktop answers, never offers, and applies no inbound constraints beyond
 * decoding/rendering (webrtc.md) — the mobile is the offerer and configuration
 * owner. Phase5 handles the `media` pc only; a `mic` offer is logged and left
 * unanswered until Phase8 wires an audio pipeline.
 */

export type Logger = (
  level: 'debug' | 'info' | 'warn' | 'error',
  message: string,
  details?: Record<string, unknown>
) => void

/** The RTCPeerConnection surface ReceiverSession uses — satisfied by Chromium's, faked in tests. */
export interface PeerConnectionLike {
  setRemoteDescription(description: { type: string; sdp: string }): Promise<void>
  createAnswer(): Promise<{ type: string; sdp: string }>
  setLocalDescription(description: { type: string; sdp: string }): Promise<void>
  addIceCandidate(candidate: unknown): Promise<void>
  /**
   * Chromium's RTCStatsReport is maplike — iteration yields [id, stats]
   * tuples; tests yield plain objects. Both are normalized before sampling.
   */
  getStats(): Promise<Iterable<unknown>>
  close(): void
  onicecandidate: ((event: { candidate: unknown }) => void) | null
  onconnectionstatechange: (() => void) | null
  ontrack: ((event: { track: unknown; streams: unknown[] }) => void) | null
  connectionState: string
}

/** Where the session's media goes (the VideoView in production). */
export interface VideoSink {
  show(stream: unknown): void
  clear(): void
}

/** Outbound media messages — implemented by the IPC bridge in production. */
export interface SignalingOut {
  sendSdpAnswer(pc: PcId, sdp: string): void
  sendIceCandidate(pc: PcId, candidate: unknown): void
}

export interface ReceiverSessionOptions {
  /** Fresh pc per offer (the mobile rebuilds its pc per session/re-auth). */
  createPeerConnection: () => PeerConnectionLike
  signaling: SignalingOut
  sink: VideoSink
  log: Logger
  /** getStats poll interval (webrtc.md: ~1 Hz); injectable for tests. */
  statsIntervalMs?: number
  now?: () => number
}

const PC_ID: PcId = 'media'
const DEFAULT_STATS_INTERVAL_MS = 1_000

interface ActivePc {
  pc: PeerConnectionLike
  /** Remote candidates that arrived before setRemoteDescription finished. */
  pendingRemoteCandidates: unknown[]
  remoteDescriptionSet: boolean
  statsTimer: ReturnType<typeof setInterval> | null
  lastSample: VideoReceiveSample | null
  lastSampleAtMs: number
}

export class ReceiverSession {
  private readonly options: ReceiverSessionOptions
  private readonly statsIntervalMs: number
  private active: ActivePc | null = null
  /** Set once the mobile's session is over for good (fresh QR → next cast needs a re-scan). */
  private closed = false

  constructor(options: ReceiverSessionOptions) {
    this.options = options
    this.statsIntervalMs = options.statsIntervalMs ?? DEFAULT_STATS_INTERVAL_MS
  }

  /** IPC push: an SDP offer arrived for one pc. The mobile is always the offerer. */
  async handleSdpOffer(message: SignalingSdpMessage): Promise<void> {
    if (message.pc !== 'media') {
      // Honest no-op: the mic pc gets its pipeline in Phase8; answering it now
      // would put an audio track on a connection nothing can play.
      this.options.log('info', `ignoring sdp-offer for pc=${message.pc} — audio arrives in a later phase`)
      return
    }
    if (this.closed) {
      this.options.log('warn', 'sdp-offer after teardown — ignoring')
      return
    }
    // A new offer means the mobile (re)built its pc — drop any stale answerer.
    this.stop()
    const pc = this.options.createPeerConnection()
    const state: ActivePc = {
      pc,
      pendingRemoteCandidates: [],
      remoteDescriptionSet: false,
      statsTimer: null,
      lastSample: null,
      lastSampleAtMs: 0
    }
    this.active = state
    this.wireHandlers(state)

    try {
      await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp })
      state.remoteDescriptionSet = true
      const answer = await pc.createAnswer()
      await pc.setLocalDescription(answer)
      this.options.signaling.sendSdpAnswer(PC_ID, answer.sdp)
      this.options.log('info', 'sdp answer sent', { sdpBytes: answer.sdp.length })
      // Candidates the mobile trickled while the answer was being built.
      const queued = state.pendingRemoteCandidates
      state.pendingRemoteCandidates = []
      for (const candidate of queued) {
        await pc.addIceCandidate(candidate)
      }
    } catch (error) {
      this.options.log('error', 'answering sdp-offer failed', { error: String(error) })
      this.stop()
      this.options.sink.clear()
    }
  }

  /** IPC push: an ICE candidate (or `candidate: null` = end-of-gathering) from the mobile. */
  async handleIceCandidate(message: SignalingIceMessage): Promise<void> {
    if (message.pc !== 'media') return // no mic pc exists yet (Phase8)
    const state = this.active
    if (state === null || this.closed) {
      this.options.log('warn', 'ice candidate with no active pc — ignoring')
      return
    }
    if (message.candidate === null || message.candidate === undefined) {
      // End-of-gathering marker, not a candidate to add (webrtc.md).
      this.options.log('debug', 'mobile finished ice gathering')
      return
    }
    try {
      if (!state.remoteDescriptionSet) {
        state.pendingRemoteCandidates.push(message.candidate)
        return
      }
      await state.pc.addIceCandidate(message.candidate)
    } catch (error) {
      this.options.log('warn', 'addIceCandidate failed', { error: String(error) })
    }
  }

  /**
   * The mobile is gone (socket dropped past the reconnect window, `bye`, or the
   * session was regenerated). Media is dead even if the socket reconnects — a
   * returning mobile always sends a fresh offer.
   */
  handleMobileGone(): void {
    this.stop()
    this.options.sink.clear()
  }

  /** Final teardown (window closing). */
  close(): void {
    this.closed = true
    this.handleMobileGone()
  }

  private wireHandlers(state: ActivePc): void {
    state.pc.onicecandidate = (event) => {
      // The whole candidate object goes on the wire verbatim (webrtc.md) —
      // including Chromium's mDNS-obfuscated host candidates (risk R4).
      this.options.signaling.sendIceCandidate(PC_ID, event.candidate)
    }
    state.pc.ontrack = (event) => {
      const stream = event.streams[0]
      if (stream !== undefined) {
        this.options.log('info', 'video track arrived — rendering')
        this.options.sink.show(stream)
      }
    }
    state.pc.onconnectionstatechange = () => {
      const stateName = state.pc.connectionState
      this.options.log('info', `connection ${stateName}`)
      if (stateName === 'connected') {
        this.startStats(state)
      } else if (stateName === 'failed' || stateName === 'closed') {
        // ICE-restart/recovery is the mobile's call (it is the offerer); the
        // desktop only stops polling and logs.
        this.stopStats(state)
      }
    }
  }

  /** webrtc.md: poll getStats ~1 Hz and log the receive-side video numbers. */
  private startStats(state: ActivePc): void {
    if (state.statsTimer !== null) return
    const now = this.options.now ?? Date.now
    state.lastSampleAtMs = now()
    state.statsTimer = setInterval(() => {
      void state.pc
        .getStats()
        .then((report) => {
          // Maplike: real entries are [id, stats] tuples — unwrap to stats.
          const entries = Array.from(report).map((entry) =>
            Array.isArray(entry) && entry.length === 2
              ? (entry[1] as Record<string, unknown>)
              : (entry as Record<string, unknown>)
          )
          const sample = sampleVideoReceive(entries)
          const atMs = now()
          const intervalMs = atMs - state.lastSampleAtMs
          this.options.log('info', 'stats', {
            bitrateBps: Math.round(bitrateBps(sample.bytesReceived, state.lastSample?.bytesReceived ?? 0, intervalMs)),
            rttMs: sample.rttMs !== null ? Math.round(sample.rttMs) : null,
            packetsLost: sample.packetsLost,
            jitterMs: Number(sample.jitterMs.toFixed(1)),
            framesDecoded: sample.framesDecoded,
            framesDropped: sample.framesDropped,
            decoder: sample.decoderImplementation
          })
          state.lastSample = sample
          state.lastSampleAtMs = atMs
        })
        .catch((error) => this.options.log('warn', 'getStats failed', { error: String(error) }))
    }, this.statsIntervalMs)
  }

  private stopStats(state: ActivePc): void {
    if (state.statsTimer !== null) {
      clearInterval(state.statsTimer)
      state.statsTimer = null
    }
  }

  private stop(): void {
    if (this.active === null) return
    this.stopStats(this.active)
    try {
      this.active.pc.close()
    } catch {
      // A pc torn down mid-negotiation may already be closing.
    }
    this.active = null
  }
}
