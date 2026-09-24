import type { PcId, SignalingIceMessage, SignalingSdpMessage } from '../../../shared/types'
import { bitrateBps, sampleVideoReceive, type VideoReceiveSample } from './stats'

/**
 * The renderer's media half of the desktop (docs/architecture/desktop.md):
 * answer the mobile's offers, glue ICE, and hand the remote tracks to the
 * sinks — the `media` pc (screen + game audio) to the `<video>` sink, the
 * `mic` pc to the audio sink (Phase8). The main process owns the socket —
 * this class only speaks the typed IPC surface below, so it is unit-testable
 * in plain Node.
 *
 * The desktop answers, never offers, and applies no inbound constraints beyond
 * decoding/rendering (webrtc.md) — the mobile is the offerer and configuration
 * owner. The two pcs are independent answerer instances: a mic offer never
 * disturbs the media pc, so the mobile can turn its mic on/off mid-cast
 * (fresh mic offer = previous mic pc torn down).
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

/** Where the media pc's stream goes (the VideoView in production). */
export interface VideoSink {
  show(stream: unknown): void
  clear(): void
}

/** Where the mic pc's stream goes (an <audio> element until Phase9's mixer). */
export interface AudioSink {
  show(stream: unknown): void
  clear(): void
}

/** Outbound media messages — implemented by the IPC bridge in production. */
export interface SignalingOut {
  sendSdpAnswer(pc: PcId, sdp: string): void
  sendIceCandidate(pc: PcId, candidate: unknown): void
}

export interface ReceiverSessionOptions {
  /** Fresh pc per offer (the mobile rebuilds its pcs per session/re-auth). */
  createPeerConnection: () => PeerConnectionLike
  signaling: SignalingOut
  sink: VideoSink
  /** The mic pc's stream destination (Phase8; Web Audio per-stream volume is Phase9). */
  micSink: AudioSink
  log: Logger
  /** getStats poll interval (webrtc.md: ~1 Hz); injectable for tests. */
  statsIntervalMs?: number
  now?: () => number
}

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
  /** One answerer per pc id (ADR-003: media and mic are independent). */
  private readonly active: Map<PcId, ActivePc> = new Map()
  /** Set once the mobile's session is over for good (fresh QR → next cast needs a re-scan). */
  private closed = false

  constructor(options: ReceiverSessionOptions) {
    this.options = options
    this.statsIntervalMs = options.statsIntervalMs ?? DEFAULT_STATS_INTERVAL_MS
  }

  /** IPC push: an SDP offer arrived for one pc. The mobile is always the offerer. */
  async handleSdpOffer(message: SignalingSdpMessage): Promise<void> {
    if (this.closed) {
      this.options.log('warn', 'sdp-offer after teardown — ignoring')
      return
    }
    const pcId = message.pc
    // A new offer means the mobile (re)built that pc — drop any stale answerer
    // for it, and only for it: the other pc keeps streaming untouched.
    this.stop(pcId)
    const pc = this.options.createPeerConnection()
    const state: ActivePc = {
      pc,
      pendingRemoteCandidates: [],
      remoteDescriptionSet: false,
      statsTimer: null,
      lastSample: null,
      lastSampleAtMs: 0
    }
    this.active.set(pcId, state)
    this.wireHandlers(pcId, state)

    try {
      await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp })
      state.remoteDescriptionSet = true
      const answer = await pc.createAnswer()
      await pc.setLocalDescription(answer)
      this.options.signaling.sendSdpAnswer(pcId, answer.sdp)
      this.options.log('info', `sdp answer sent (pc=${pcId})`, { sdpBytes: answer.sdp.length })
      // Candidates the mobile trickled while the answer was being built.
      const queued = state.pendingRemoteCandidates
      state.pendingRemoteCandidates = []
      for (const candidate of queued) {
        await pc.addIceCandidate(candidate)
      }
    } catch (error) {
      this.options.log('error', `answering sdp-offer failed (pc=${pcId})`, { error: String(error) })
      this.stop(pcId)
      this.clearSink(pcId)
    }
  }

  /** IPC push: an ICE candidate (or `candidate: null` = end-of-gathering) from the mobile. */
  async handleIceCandidate(message: SignalingIceMessage): Promise<void> {
    const state = this.active.get(message.pc)
    if (state === null || state === undefined) {
      this.options.log('warn', `ice candidate with no active pc=${message.pc} — ignoring`)
      return
    }
    if (this.closed) return
    if (message.candidate === null || message.candidate === undefined) {
      // End-of-gathering marker, not a candidate to add (webrtc.md).
      this.options.log('debug', `mobile finished ice gathering (pc=${message.pc})`)
      return
    }
    try {
      if (!state.remoteDescriptionSet) {
        state.pendingRemoteCandidates.push(message.candidate)
        return
      }
      await state.pc.addIceCandidate(message.candidate)
    } catch (error) {
      this.options.log('warn', `addIceCandidate failed (pc=${message.pc})`, { error: String(error) })
    }
  }

  /**
   * The mobile is gone (socket dropped past the reconnect window, `bye`, or the
   * session was regenerated). Media is dead even if the socket reconnects — a
   * returning mobile always sends a fresh offer.
   */
  handleMobileGone(): void {
    for (const pcId of [...this.active.keys()]) {
      this.stop(pcId)
    }
    this.options.sink.clear()
    this.options.micSink.clear()
  }

  /**
   * Final teardown (window closing).
   */
  close(): void {
    this.closed = true
    this.handleMobileGone()
  }

  /**
   * One getStats report per active pc, entries normalized to plain objects.
   * Phase15's debug hook: the CDP capture script (scripts/capture-session-stats.mjs)
   * reaches the renderer-local receiver through `window.__castReceiver` and records
   * the receive-side stats — read-only, never a signaling or media path.
   */
  async statsSnapshot(): Promise<Partial<Record<PcId, Array<Record<string, unknown>>>>> {
    const snapshot: Partial<Record<PcId, Array<Record<string, unknown>>>> = {}
    for (const [pcId, state] of this.active) {
      const report = await state.pc.getStats()
      snapshot[pcId] = Array.from(report).map((entry) =>
        Array.isArray(entry) && entry.length === 2
          ? (entry[1] as Record<string, unknown>)
          : (entry as Record<string, unknown>)
      )
    }
    return snapshot
  }

  private wireHandlers(pcId: PcId, state: ActivePc): void {
    state.pc.onicecandidate = (event) => {
      // The whole candidate object goes on the wire verbatim (webrtc.md) —
      // including Chromium's mDNS-obfuscated host candidates (risk R4). But
      // first it must survive the renderer→main IPC hop: RTCIceCandidate is
      // not structured-cloneable, and Electron's serializer delivered an
      // empty there (Phase15 live finding — the mobile logged one
      // "malformed candidate" per cast and connectivity survived only via
      // ICE peer-reflexive discovery). toJSON() is exactly the
      // RTCIceCandidateInit shape the wire wants.
      const candidate = (event.candidate as { toJSON?: () => unknown } | null)?.toJSON?.() ?? event.candidate
      this.options.signaling.sendIceCandidate(pcId, candidate)
    }
    state.pc.ontrack = (event) => {
      const stream = event.streams[0]
      if (stream !== undefined) {
        // The media pc's stream carries screen + game audio together
        // (ADR-003); the mic pc's stream is the independent mic track. Both
        // play through their element — Web Audio per-stream volume is
        // Phase9's AudioMixer, not this class.
        const kind = (event.track as { kind?: string } | null)?.kind ?? 'unknown'
        this.options.log('info', `${kind} track arrived (pc=${pcId}) — rendering`)
        if (pcId === 'media') {
          this.options.sink.show(stream)
        } else {
          this.options.micSink.show(stream)
        }
      }
    }
    state.pc.onconnectionstatechange = () => {
      const stateName = state.pc.connectionState
      this.options.log('info', `connection ${stateName} (pc=${pcId})`)
      if (stateName === 'connected') {
        if (pcId === 'media') this.startStats(state)
      } else if (stateName === 'failed' || stateName === 'closed') {
        // ICE-restart/recovery is the mobile's call (it is the offerer); the
        // desktop only stops polling and logs. The media pc's sink is only
        // cleared when the whole session ends (handleMobileGone) — the mic
        // pc, though, is per-toggle: 'closed' means the mobile turned the mic
        // off and 'failed' won't recover without a fresh mic offer, so its
        // sink stops immediately either way.
        if (pcId === 'media') {
          this.stopStats(state)
        } else {
          this.stop(pcId)
          this.options.micSink.clear()
        }
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

  private stop(pcId: PcId): void {
    const state = this.active.get(pcId)
    if (state === undefined) return
    this.stopStats(state)
    try {
      state.pc.close()
    } catch {
      // A pc torn down mid-negotiation may already be closing.
    }
    this.active.delete(pcId)
  }

  private clearSink(pcId: PcId): void {
    if (pcId === 'media') {
      this.options.sink.clear()
    } else {
      this.options.micSink.clear()
    }
  }
}
