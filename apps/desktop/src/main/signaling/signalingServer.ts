import { WebSocketServer, type WebSocket } from 'ws'
import type { PairingServer } from '../pairing/pairingServer'
import type { TrustStore, TrustedDevice } from '../pairing/trustStore'
import {
  encodeEnvelope,
  isPcId,
  parseEnvelope,
  type Envelope,
  type EnvelopePayload,
  type EnvelopeType,
  type AuthOkPayload,
  type HelloPayload,
  type IcePayload,
  type PcId,
  type SessionInfoPayload,
  type SdpPayload
} from './envelope'
import { computeMac, createNonce, deriveTrustKey, negotiateProtocol, verifyMac } from './handshake'
import { deviceNameFromUa } from './ua'
import { logger } from '../log'

/**
 * WebSocket signaling endpoint — ws://<host>:<port>/zfc/v1 (docs/architecture/webrtc.md).
 * Dumb, reliable message plumbing: pairing handshake, then the full Phase4
 * message set (sdp-*, ice, ping/pong, session-info, bye, error) forwarded to
 * consumers as typed events — no media logic lives here (webrtc.md).
 * Electron-import-free for plain-Node loopback tests.
 */

const LOG_SCOPE = 'signaling'
/** Path of the signaling endpoint — versioned per webrtc.md. */
export const SIGNALING_PATH = '/zfc/v1'
/** Default port per pairing.md; falls back to an ephemeral port if occupied. */
export const SIGNALING_PORT_DEFAULT = 52341
/** The full handshake must complete within this window (pairing.md). */
const HANDSHAKE_TIMEOUT_MS = 10_000
/** Failed-auth rate limit per address: 1 s doubling, capped (pairing.md). */
const RATE_LIMIT_INITIAL_MS = 1_000
const RATE_LIMIT_MAX_MS = 30_000
/** App-level heartbeat cadence (webrtc.md: ping every 5 s). */
export const HEARTBEAT_INTERVAL_MS = 5_000
/** A peer silent longer than this is considered gone (webrtc.md: 15 s). */
export const HEARTBEAT_TIMEOUT_MS = 15_000

export const ERROR_CODES = {
  badVersion: 'bad-version',
  unknownSession: 'unknown-session',
  expired: 'expired',
  badAuth: 'bad-auth',
  busy: 'busy',
  badMessage: 'bad-message',
  /** Resume with a deviceId this desktop no longer trusts (pairing.md §Persistent sessions). */
  unknownDevice: 'unknown-device'
} as const

/**
 * Reserved `bye` reason (pairing.md §Persistent sessions): end the cast AND
 * revoke the persistent trust on both sides — what both Disconnect buttons
 * send. Every other reason (e.g. `user-ended`) ends the session but keeps
 * the trust, so the phone reconnects with one tap.
 */
export const BYE_FORGET = 'forget'

/** pairing.md: `deviceId` is an install-scoped UUID — anything longer is malformed. */
const MAX_DEVICE_ID_LENGTH = 128

/** Inbound SDP/ICE from the mobile — forwarded, never inspected (webrtc.md). */
export interface SdpOfferEvent {
  pc: PcId
  sdp: string
}
export interface IceCandidateEvent {
  pc: PcId
  /** Opaque candidate object; `null` marks end-of-gathering for this pc. */
  candidate: unknown
}

export interface SignalingServerOptions {
  /** Shown in auth-ok; the mobile displays "Connected to <name>". */
  desktopName: string
  /**
   * Persistent trust for the paired phone (pairing.md §Persistent sessions):
   * written on the first QR pairing with a `deviceId`, consulted for resume
   * hellos, revoked by `bye forget` / the desktop Disconnect. Optional —
   * without it the server behaves exactly as before (QR pairing only).
   */
  trustStore?: TrustStore
  /** The desktop's persistent UUID — sent as `auth-ok.desktopId` when present. */
  desktopId?: string
  /** Current LAN IPv4 candidates for `auth-ok.hosts` — injectable for tests. */
  currentHosts?: () => string[]
  /** Injected clock for tests. */
  now?: () => number
  /** Heartbeat cadence/timeout — injectable so tests use tight values (webrtc.md). */
  heartbeatIntervalMs?: number
  heartbeatTimeoutMs?: number
  onMobileConnected?: (event: { sid: string; ua: string; name: string; deviceId?: string; remote: string; resume: boolean }) => void
  onMobileDisconnected?: (event: { sid: string; deviceId?: string; remote: string }) => void
  /** `bye` from either side — the coordinator invalidates the session and shows a fresh QR. */
  onBye?: (reason?: string) => void
  /** Media plumbing events for the (Phase5) ReceiverSession — SDP offers are never answered here. */
  onSdpOffer?: (event: SdpOfferEvent) => void
  onIceCandidate?: (event: IceCandidateEvent) => void
  /** Display-only summary for the status line (webrtc.md) — never acted on. */
  onSessionInfo?: (info: SessionInfoPayload) => void
}

interface ConnectionState {
  socket: WebSocket
  socketId: string
  remote: string
  phase: 'awaiting-hello' | 'awaiting-auth' | 'authorized'
  sid: string | null
  nonce: string | null
  hello: HelloPayload | null
  /**
   * The trusted phone this connection is resuming (pairing.md §Persistent
   * sessions), or null on the QR path — decides which key verifies `auth`
   * and skips the QR session bookkeeping.
   */
  resumeDevice: TrustedDevice | null
  /** Server-side outbound seq counter (webrtc.md: per-sender, from 1). */
  sendSeq: number
  lastReceivedSeq: number
  handshakeTimer: NodeJS.Timeout | null
  heartbeatTimer: NodeJS.Timeout | null
  /** Injected-clock timestamp of the last inbound frame (heartbeat silence, webrtc.md). */
  lastSeenAt: number
}

export class SignalingServer {
  private pairing: PairingServer | null = null
  private readonly desktopName: string
  private readonly trustStore: TrustStore | undefined
  private readonly desktopId: string | undefined
  private readonly currentHosts: (() => string[]) | undefined
  private readonly now: () => number
  private readonly heartbeatIntervalMs: number
  private readonly heartbeatTimeoutMs: number
  private readonly options: SignalingServerOptions
  private wss: WebSocketServer | null = null
  private port = 0
  private readonly connections = new Map<string, ConnectionState>()
  private nextSocketId = 1
  /** remote address → failed-auth bookkeeping for the rate limit. */
  private readonly authFailures = new Map<string, { fails: number; blockedUntil: number }>()

  constructor(options: SignalingServerOptions) {
    this.desktopName = options.desktopName
    this.trustStore = options.trustStore
    this.desktopId = options.desktopId
    this.currentHosts = options.currentHosts
    this.now = options.now ?? Date.now
    this.heartbeatIntervalMs = options.heartbeatIntervalMs ?? HEARTBEAT_INTERVAL_MS
    this.heartbeatTimeoutMs = options.heartbeatTimeoutMs ?? HEARTBEAT_TIMEOUT_MS
    this.options = options
  }

  /**
   * Attach the pairing store. Must happen after start() resolved (the actual
   * port is needed to build the QR payload) and before any session is published
   * — no client can complete a handshake before a session exists.
   */
  attachPairing(pairing: PairingServer): void {
    this.pairing = pairing
  }

  /** Pairing store accessor — attached before any session is published. */
  private get store(): PairingServer {
    if (this.pairing === null) {
      throw new Error('pairing store not attached')
    }
    return this.pairing
  }

  get actualPort(): number {
    return this.port
  }

  /** Bind to the requested port, falling back to an ephemeral port (pairing.md `p`). */
  start(port = SIGNALING_PORT_DEFAULT): Promise<number> {
    return new Promise((resolve, reject) => {
      const attempt = (requested: number): void => {
        const wss = new WebSocketServer({ port: requested, host: '0.0.0.0', path: SIGNALING_PATH })
        wss.once('listening', () => {
          this.wss = wss
          // The actually bound port (differs from `requested` on the ephemeral fallback).
          const address = wss.address()
          this.port = typeof address === 'object' && address !== null ? address.port : requested
          wss.on('connection', (socket, request) => this.onConnection(socket, request.socket.remoteAddress ?? 'unknown'))
          logger.info(LOG_SCOPE, `listening on port ${this.port} at ${SIGNALING_PATH}`)
          resolve(this.port)
        })
        wss.once('error', (error: NodeJS.ErrnoException) => {
          wss.removeAllListeners()
          if (requested !== 0 && error.code === 'EADDRINUSE') {
            logger.warn(LOG_SCOPE, `port ${requested} occupied — falling back to an ephemeral port`)
            attempt(0)
          } else {
            reject(error)
          }
        })
      }
      attempt(port)
    })
  }

  stop(): void {
    for (const conn of this.connections.values()) {
      this.clearHandshakeTimer(conn)
      this.clearHeartbeatTimer(conn)
      conn.socket.terminate()
    }
    this.connections.clear()
    this.wss?.close()
    this.wss = null
  }

  /** Drop the authorized connection if any (used when the session is regenerated). */
  disconnectAuthorized(): void {
    for (const conn of this.connections.values()) {
      if (conn.phase === 'authorized') {
        conn.socket.close(1000, 'session-replaced')
      }
    }
  }

  /**
   * Whether the authorized mobile's socket is still connected (Phase15): the
   * pairing session's expiry sweep defers regeneration while this is true —
   * a live cast must not be killed by the QR's TTL (pairing.md).
   */
  hasAuthorizedConnection(): boolean {
    for (const conn of this.connections.values()) {
      if (conn.phase === 'authorized') return true
    }
    return false
  }

  // ---- Desktop → mobile senders (the ReceiverSession's half of the plumbing, Phase5+) ----

  /** Send the SDP answer for one PeerConnection (mobile is always the offerer, webrtc.md). */
  sendSdpAnswer(pc: PcId, sdp: string): void {
    this.withAuthorized((conn) => this.send(conn, 'sdp-answer', { pc, sdp } satisfies SdpPayload))
  }

  /** Trickled candidate for one pc; `candidate: null` marks end-of-gathering. */
  sendIceCandidate(pc: PcId, candidate: unknown): void {
    this.withAuthorized((conn) => this.send(conn, 'ice', { pc, candidate } satisfies IcePayload))
  }

  /**
   * Desktop-initiated graceful end: `bye` invalidates the session immediately
   * (webrtc.md), so this sends bye, closes the socket, and fires onBye for the
   * coordinator to show a fresh QR. The reserved `forget` reason (the desktop
   * Disconnect button) additionally revokes the persistent trust first.
   */
  sendBye(reason: string): void {
    if (reason === BYE_FORGET) this.trustStore?.clear()
    this.withAuthorized((conn) => {
      logger.info(LOG_SCOPE, `bye to ${conn.remote}`, { reason })
      this.send(conn, 'bye', { reason })
      this.store.invalidate()
      conn.socket.close(1000, 'bye')
      this.options.onBye?.(reason)
    })
  }

  private withAuthorized(action: (conn: ConnectionState) => void): void {
    const conn = [...this.connections.values()].find((c) => c.phase === 'authorized')
    if (conn === undefined) {
      logger.warn(LOG_SCOPE, 'send with no authorized mobile connected — dropping message')
      return
    }
    action(conn)
  }

  // ---- Connection handling ----

  private onConnection(socket: WebSocket, remote: string): void {
    if (this.pairing === null) {
      // No session has been published yet — nothing to authenticate against.
      socket.close(1013, 'no-session')
      return
    }
    const failure = this.authFailures.get(remote)
    if (failure !== undefined && this.now() < failure.blockedUntil) {
      logger.warn(LOG_SCOPE, `closing ${remote}: rate-limited after failed auth attempts`)
      socket.close(1008, 'rate-limited')
      return
    }

    const conn: ConnectionState = {
      socket,
      socketId: `sock-${this.nextSocketId++}`,
      remote,
      phase: 'awaiting-hello',
      sid: null,
      nonce: null,
      hello: null,
      resumeDevice: null,
      sendSeq: 0,
      lastReceivedSeq: 0,
      handshakeTimer: setTimeout(() => {
        if (conn.phase !== 'authorized') {
          logger.info(LOG_SCOPE, `closing ${remote}: handshake did not complete in time`)
          socket.close(1008, 'handshake-timeout')
        }
      }, HANDSHAKE_TIMEOUT_MS),
      heartbeatTimer: null,
      lastSeenAt: 0
    }
    this.connections.set(conn.socketId, conn)

    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        logger.warn(LOG_SCOPE, `binary frame from ${remote} — closing`)
        socket.close(1003, 'binary-not-supported')
        return
      }
      // ws delivers text frames as Buffers; the protocol is JSON text (webrtc.md).
      this.onMessage(conn, data.toString())
    })
    socket.on('close', () => this.onClose(conn))
    socket.on('error', (error) => logger.warn(LOG_SCOPE, `socket error from ${remote}`, { error: String(error) }))
  }

  private onMessage(conn: ConnectionState, raw: string): void {
    // Any inbound frame is liveness for the heartbeat (webrtc.md: silence >15 s = gone).
    conn.lastSeenAt = this.now()

    const parsed = parseEnvelope(raw)
    if (!parsed.ok) {
      // bad-version is terminal; bad-message keeps the socket open only for
      // unknown types — an oversized or malformed frame closes it.
      this.sendError(conn, parsed.code, parsed.reason)
      if (parsed.code === 'bad-version' || parsed.reason.includes('exceeds')) {
        conn.socket.close(1002, parsed.code)
      }
      return
    }
    const envelope = parsed.envelope
    if (envelope.seq <= conn.lastReceivedSeq) {
      logger.debug(LOG_SCOPE, `seq regression from ${conn.remote}: ${envelope.seq} after ${conn.lastReceivedSeq}`)
    }
    conn.lastReceivedSeq = envelope.seq

    if (conn.phase === 'awaiting-hello' && envelope.type !== 'hello') {
      this.sendError(conn, ERROR_CODES.badMessage, `expected hello, got ${envelope.type}`)
      return
    }
    if (conn.phase === 'awaiting-auth' && envelope.type !== 'auth') {
      this.sendError(conn, ERROR_CODES.badMessage, `expected auth, got ${envelope.type}`)
      return
    }

    switch (envelope.type) {
      case 'hello':
        this.onHello(conn, envelope)
        break
      case 'auth':
        this.onAuth(conn, envelope)
        break
      case 'bye':
        this.onBye(conn, envelope)
        break
      case 'error':
        logger.info(LOG_SCOPE, `mobile reported ${String((envelope.payload as { code?: string }).code ?? 'error')}`)
        break
      case 'sdp-offer':
        this.onSdpOffer(conn, envelope)
        break
      case 'ice':
        this.onIce(conn, envelope)
        break
      case 'session-info':
        this.onSessionInfo(conn, envelope)
        break
      case 'ping':
        this.onPing(conn, envelope)
        break
      case 'pong':
        // Liveness only — lastSeenAt was reset above.
        break
      case 'sdp-answer':
        this.sendError(conn, ERROR_CODES.badMessage, 'desktop is the answerer, not the offerer')
        break
      case 'challenge':
      case 'auth-ok':
        this.sendError(conn, ERROR_CODES.badMessage, `unexpected ${envelope.type}`)
        break
      default:
        this.sendError(conn, ERROR_CODES.badMessage, `unexpected ${envelope.type}`)
    }
  }

  private onHello(conn: ConnectionState, envelope: Envelope): void {
    // Payload shape first — both the QR and the resume path need a well-formed
    // hello before anything is revealed about sessions or trusted devices.
    const hello = envelope.payload as HelloPayload
    if (typeof hello.ua !== 'string' || typeof hello.protoMin !== 'number' || typeof hello.protoMax !== 'number') {
      this.sendError(conn, ERROR_CODES.badMessage, 'malformed hello payload')
      conn.socket.close(1002, ERROR_CODES.badMessage)
      return
    }
    // deviceId is hello's optional field (pairing.md, ADR-005): absent
    // = unidentified older client — accepted; present but malformed = the
    // same bad-message treatment as any other malformed payload.
    if (hello.deviceId !== undefined && (typeof hello.deviceId !== 'string' || hello.deviceId.length === 0 || hello.deviceId.length > MAX_DEVICE_ID_LENGTH)) {
      this.sendError(conn, ERROR_CODES.badMessage, 'malformed hello deviceId')
      conn.socket.close(1002, ERROR_CODES.badMessage)
      return
    }
    if (hello.resume !== undefined && typeof hello.resume !== 'boolean') {
      this.sendError(conn, ERROR_CODES.badMessage, 'malformed hello resume')
      conn.socket.close(1002, ERROR_CODES.badMessage)
      return
    }
    // The envelope's sid is known the moment hello validates — own it now so
    // every reply below (challenge *or* any handshake failure) carries it.
    // Frames with an empty sid are malformed on the mobile side, and replies
    // used to go out sid-less exactly on the failure paths (found live
    // 2026-10-30: the phone ignored its own unknown-device error and sat on
    // "Connecting…" until the desktop's handshake timeout closed the socket).
    conn.sid = envelope.sid

    const session = this.store.lookup(envelope.sid)
    if (session === null) {
      // Not the live QR session: either a stale QR (unknown-session, as
      // before) or a trusted phone resuming without one (pairing.md
      // §Persistent sessions — the envelope sid then IS the phone deviceId).
      // A server wired without a trust store never answers resume — it stays
      // on the plain QR behavior (older desktops answer unknown-session).
      if (hello.resume === true && this.trustStore !== undefined) {
        const trust = this.trustStore.get()
        if (trust === null || trust.deviceId !== envelope.sid) {
          logger.info(LOG_SCOPE, `resume from ${conn.remote}: no trusted device matches`)
          this.failHandshake(conn, ERROR_CODES.unknownDevice)
          return
        }
        conn.resumeDevice = trust
        conn.sid = envelope.sid
        conn.hello = hello
        conn.nonce = createNonce()
        conn.phase = 'awaiting-auth'
        logger.info(LOG_SCOPE, `resume hello from ${conn.remote}`, { ua: hello.ua })
        this.send(conn, 'challenge', { n: conn.nonce })
        return
      }
      logger.info(LOG_SCOPE, `hello from ${conn.remote}: unknown session`)
      this.failHandshake(conn, ERROR_CODES.unknownSession)
      return
    }
    if (this.store.isExpired(session)) {
      logger.info(LOG_SCOPE, `hello from ${conn.remote}: session expired`)
      this.failHandshake(conn, ERROR_CODES.expired)
      return
    }
    conn.sid = envelope.sid
    conn.hello = hello
    conn.nonce = createNonce()
    conn.phase = 'awaiting-auth'
    logger.info(LOG_SCOPE, `hello from ${conn.remote}`, { ua: hello.ua })
    this.send(conn, 'challenge', { n: conn.nonce })
  }

  private onAuth(conn: ConnectionState, envelope: Envelope): void {
    const sid = conn.sid ?? ''
    if (conn.nonce === null || conn.hello === null) {
      this.failHandshake(conn, ERROR_CODES.unknownSession)
      return
    }
    // Which key verifies the mac: the stored trustKey on the resume path, the
    // QR session secret otherwise (pairing.md §Authentication / §Persistent sessions).
    const resume = conn.resumeDevice
    const session = resume === null ? this.store.lookup(sid) : null
    if (resume === null && (session === null || this.store.isExpired(session))) {
      this.failHandshake(conn, session === null ? ERROR_CODES.unknownSession : ERROR_CODES.expired)
      return
    }
    const authKey = resume !== null ? resume.key : session!.secret

    const mac = (envelope.payload as { mac?: unknown }).mac
    const expected = computeMac(authKey, sid, conn.nonce)
    if (typeof mac !== 'string' || !verifyMac(expected, mac)) {
      this.recordAuthFailure(conn)
      logger.warn(LOG_SCOPE, `auth rejected from ${conn.remote}`)
      this.failHandshake(conn, ERROR_CODES.badAuth)
      return
    }
    this.clearAuthFailures(conn.remote)

    const proto = negotiateProtocol(conn.hello)
    if (proto === null) {
      logger.info(LOG_SCOPE, `no overlapping protocol version with ${conn.remote}`)
      this.failHandshake(conn, ERROR_CODES.badVersion)
      return
    }
    // One authorized connection at a time — across QR sessions *and* the
    // resume path (which bypasses the session bookkeeping entirely).
    const alreadyAuthorized = this.hasAuthorizedConnection()
    const result = resume !== null ? 'authorized' : this.store.authorize(sid, conn.socketId)
    if (alreadyAuthorized || result === 'busy') {
      logger.warn(LOG_SCOPE, `second device attempted to pair (${conn.remote})`)
      this.failHandshake(conn, ERROR_CODES.busy)
      return
    }

    conn.phase = 'authorized'
    this.clearHandshakeTimer(conn)
    this.startHeartbeat(conn)
    const authOk: AuthOkPayload = { name: this.desktopName, proto }
    if (this.desktopId !== undefined) {
      authOk.desktopId = this.desktopId
    }
    const hosts = this.currentHosts?.()
    if (hosts !== undefined && hosts.length > 0) {
      authOk.hosts = hosts
    }
    this.send(conn, 'auth-ok', authOk)
    const name = deviceNameFromUa(conn.hello.ua)

    if (resume !== null) {
      // A resume bypasses the QR session: stamp the trust and close the QR
      // session it never used — a fresh pending one is staged underneath so a
      // photographed old QR cannot piggyback while the trusted phone casts.
      this.trustStore?.touch()
      this.store.invalidate()
      void this.pairing
        ?.createSession()
        .catch((error) => logger.warn(LOG_SCOPE, 'staging a fresh QR session after resume failed', { error: String(error) }))
      logger.info(LOG_SCOPE, `mobile re-authorized via saved session`, { name })
    } else if (conn.hello.deviceId !== undefined && this.trustStore !== undefined && session !== null) {
      // First trust grant (or a re-pair rotating the key): both sides derive
      // the same trustKey from this QR session's secret (ADR-006).
      const key = deriveTrustKey(session.secret, sid)
      this.trustStore.save(conn.hello.deviceId, name, key)
      logger.info(LOG_SCOPE, `mobile authorized — persistent session saved`, { name })
    } else {
      logger.info(LOG_SCOPE, `mobile authorized`, { name })
    }
    this.options.onMobileConnected?.({
      sid,
      ua: conn.hello.ua,
      name,
      deviceId: conn.hello.deviceId,
      remote: conn.remote,
      resume: resume !== null
    })
  }

  private onSdpOffer(conn: ConnectionState, envelope: Envelope): void {
    if (conn.phase !== 'authorized') {
      this.rejectPreAuth(conn, envelope.type)
      return
    }
    const payload = envelope.payload as SdpPayload
    if (!isPcId(payload.pc) || typeof payload.sdp !== 'string' || payload.sdp.length === 0) {
      this.sendError(conn, ERROR_CODES.badMessage, 'malformed sdp-offer payload')
      return
    }
    this.options.onSdpOffer?.({ pc: payload.pc, sdp: payload.sdp })
  }

  private onIce(conn: ConnectionState, envelope: Envelope): void {
    if (conn.phase !== 'authorized') {
      this.rejectPreAuth(conn, envelope.type)
      return
    }
    const payload = envelope.payload as IcePayload
    // `candidate` may be null (end-of-gathering) but the key must be present.
    if (!isPcId(payload.pc) || !('candidate' in (envelope.payload as Record<string, unknown>))) {
      this.sendError(conn, ERROR_CODES.badMessage, 'malformed ice payload')
      return
    }
    this.options.onIceCandidate?.({ pc: payload.pc, candidate: payload.candidate })
  }

  private onSessionInfo(conn: ConnectionState, envelope: Envelope): void {
    if (conn.phase !== 'authorized') {
      this.rejectPreAuth(conn, envelope.type)
      return
    }
    const info = envelope.payload as SessionInfoPayload
    const wellFormed =
      typeof info.profile === 'string' &&
      typeof info.width === 'number' &&
      typeof info.height === 'number' &&
      typeof info.fps === 'number' &&
      typeof info.gameAudio === 'boolean' &&
      typeof info.mic === 'boolean'
    if (!wellFormed) {
      this.sendError(conn, ERROR_CODES.badMessage, 'malformed session-info payload')
      return
    }
    logger.info(LOG_SCOPE, 'session-info from mobile', { profile: info.profile })
    this.options.onSessionInfo?.(info)
  }

  private onPing(conn: ConnectionState, envelope: Envelope): void {
    if (conn.phase !== 'authorized') {
      this.rejectPreAuth(conn, envelope.type)
      return
    }
    const t = (envelope.payload as { t?: unknown }).t
    if (typeof t !== 'number') {
      this.sendError(conn, ERROR_CODES.badMessage, 'malformed ping payload')
      return
    }
    this.send(conn, 'pong', { t })
  }

  private onBye(conn: ConnectionState, envelope: Envelope): void {
    const reason = (envelope.payload as { reason?: unknown }).reason
    // `bye forget` (the phone's Disconnect) revokes the persistent trust —
    // cleared *before* onBye so the coordinator already sees the empty store.
    if (reason === BYE_FORGET) this.trustStore?.clear()
    logger.info(LOG_SCOPE, `bye from ${conn.remote}`, {
      reason: typeof reason === 'string' ? reason : undefined
    })
    this.store.invalidate()
    conn.socket.close(1000, 'bye')
    this.options.onBye?.(typeof reason === 'string' ? reason : undefined)
  }

  private rejectPreAuth(conn: ConnectionState, type: string): void {
    // webrtc.md: only hello/auth are accepted until auth-ok.
    this.sendError(conn, ERROR_CODES.badMessage, `unexpected ${type} before auth`)
  }

  /** Heartbeat (webrtc.md): ping every interval; a peer silent > timeout is gone. */
  private startHeartbeat(conn: ConnectionState): void {
    conn.lastSeenAt = this.now()
    conn.heartbeatTimer = setInterval(() => {
      if (this.now() - conn.lastSeenAt > this.heartbeatTimeoutMs) {
        logger.info(LOG_SCOPE, `mobile ${conn.remote} silent > ${this.heartbeatTimeoutMs} ms — closing`)
        this.clearHeartbeatTimer(conn)
        conn.socket.close(1000, 'heartbeat-timeout')
        return
      }
      this.send(conn, 'ping', { t: this.now() })
    }, this.heartbeatIntervalMs)
  }

  private onClose(conn: ConnectionState): void {
    this.clearHandshakeTimer(conn)
    this.clearHeartbeatTimer(conn)
    this.connections.delete(conn.socketId)
    if (conn.phase === 'authorized') {
      this.store.release(conn.socketId)
      logger.info(LOG_SCOPE, 'authorized mobile disconnected — reconnect window open until expiry')
      this.options.onMobileDisconnected?.({
        sid: conn.sid ?? '',
        deviceId: conn.hello?.deviceId,
        remote: conn.remote,
      })
    }
  }

  /** Terminal error: send, then close (webrtc.md). Nothing else is expected. */
  private failHandshake(conn: ConnectionState, code: string): void {
    this.sendError(conn, code)
    this.clearHandshakeTimer(conn)
    conn.socket.close(1008, code)
  }

  private sendError(conn: ConnectionState, code: string, msg?: string): void {
    const payload = msg === undefined ? { code } : { code, msg }
    this.send(conn, 'error', payload)
  }

  private send(conn: ConnectionState, type: EnvelopeType, payload: EnvelopePayload): void {
    conn.sendSeq += 1
    conn.socket.send(encodeEnvelope(type, conn.sendSeq, conn.sid ?? '', payload))
  }

  private recordAuthFailure(conn: ConnectionState): void {
    const record = this.authFailures.get(conn.remote) ?? { fails: 0, blockedUntil: 0 }
    record.fails +=1
    record.blockedUntil = this.now() + Math.min(RATE_LIMIT_INITIAL_MS * 2 ** (record.fails - 1), RATE_LIMIT_MAX_MS)
    this.authFailures.set(conn.remote, record)
  }

  private clearAuthFailures(remote: string): void {
    this.authFailures.delete(remote)
  }

  private clearHandshakeTimer(conn: ConnectionState): void {
    if (conn.handshakeTimer !== null) {
      clearTimeout(conn.handshakeTimer)
      conn.handshakeTimer = null
    }
  }

  private clearHeartbeatTimer(conn: ConnectionState): void {
    if (conn.heartbeatTimer !== null) {
      clearInterval(conn.heartbeatTimer)
      conn.heartbeatTimer = null
    }
  }
}
