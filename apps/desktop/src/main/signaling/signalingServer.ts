import { WebSocketServer, type WebSocket } from 'ws'
import type { PairingServer } from '../pairing/pairingServer'
import {
  encodeEnvelope,
  parseEnvelope,
  type Envelope,
  type EnvelopePayload,
  type EnvelopeType,
  type HelloPayload
} from './envelope'
import { computeMac, createNonce, negotiateProtocol, verifyMac } from './handshake'
import { deviceNameFromUa } from './ua'
import { logger } from '../log'

/**
 * WebSocket signaling endpoint — ws://<host>:<port>/zfc/v1 (docs/architecture/webrtc.md).
 * Phase3 implements connection + pairing handshake only: hello → challenge →
 * auth → auth-ok, plus `bye` session invalidation. Media messages (sdp-*, ice,
 * ping/pong) are Phase4+; pre-auth only hello/auth are accepted, anything else
 * is answered with `bad-message`. Electron-import-free for plain-Node tests.
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

export const ERROR_CODES = {
  badVersion: 'bad-version',
  unknownSession: 'unknown-session',
  expired: 'expired',
  badAuth: 'bad-auth',
  busy: 'busy',
  badMessage: 'bad-message'
} as const

export interface SignalingServerOptions {
  /** Shown in auth-ok; the mobile displays "Connected to <name>". */
  desktopName: string
  /** Injected clock for tests. */
  now?: () => number
  onMobileConnected?: (event: { sid: string; ua: string; name: string }) => void
  onMobileDisconnected?: (event: { sid: string }) => void
  /** `bye` from the mobile — the coordinator invalidates the session and shows a fresh QR. */
  onBye?: () => void
}

interface ConnectionState {
  socket: WebSocket
  socketId: string
  remote: string
  phase: 'awaiting-hello' | 'awaiting-auth' | 'authorized'
  sid: string | null
  nonce: string | null
  hello: HelloPayload | null
  /** Server-side outbound seq counter (webrtc.md: per-sender, from 1). */
  sendSeq: number
  lastReceivedSeq: number
  handshakeTimer: NodeJS.Timeout | null
}

export class SignalingServer {
  private pairing: PairingServer | null = null
  private readonly desktopName: string
  private readonly now: () => number
  private readonly options: SignalingServerOptions
  private wss: WebSocketServer | null = null
  private port = 0
  private readonly connections = new Map<string, ConnectionState>()
  private nextSocketId = 1
  /** remote address → failed-auth bookkeeping for the rate limit. */
  private readonly authFailures = new Map<string, { fails: number; blockedUntil: number }>()

  constructor(options: SignalingServerOptions) {
    this.desktopName = options.desktopName
    this.now = options.now ?? Date.now
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
      sendSeq: 0,
      lastReceivedSeq: 0,
      handshakeTimer: setTimeout(() => {
        if (conn.phase !== 'authorized') {
          logger.info(LOG_SCOPE, `closing ${remote}: handshake did not complete in time`)
          socket.close(1008, 'handshake-timeout')
        }
      }, HANDSHAKE_TIMEOUT_MS)
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
    if (conn.phase === 'authorized' && envelope.type !== 'bye') {
      this.sendError(conn, ERROR_CODES.badMessage, `unexpected ${envelope.type} before Phase4`)
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
        this.onBye(conn)
        break
      case 'error':
        logger.info(LOG_SCOPE, `mobile reported ${String((envelope.payload as { code?: string }).code ?? 'error')}`)
        break
      default:
        // challenge/auth-ok are desktop→mobile only.
        this.sendError(conn, ERROR_CODES.badMessage, `unexpected ${envelope.type}`)
    }
  }

  private onHello(conn: ConnectionState, envelope: Envelope): void {
    const session = this.store.lookup(envelope.sid)
    if (session === null) {
      logger.info(LOG_SCOPE, `hello from ${conn.remote}: unknown session`)
      this.failHandshake(conn, ERROR_CODES.unknownSession)
      return
    }
    if (this.store.isExpired(session)) {
      logger.info(LOG_SCOPE, `hello from ${conn.remote}: session expired`)
      this.failHandshake(conn, ERROR_CODES.expired)
      return
    }
    const hello = envelope.payload as HelloPayload
    if (typeof hello.ua !== 'string' || typeof hello.protoMin !== 'number' || typeof hello.protoMax !== 'number') {
      this.sendError(conn, ERROR_CODES.badMessage, 'malformed hello payload')
      conn.socket.close(1002, ERROR_CODES.badMessage)
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
    const session = this.store.lookup(sid)
    if (session === null || conn.nonce === null || conn.hello === null) {
      this.failHandshake(conn, ERROR_CODES.unknownSession)
      return
    }
    if (this.store.isExpired(session)) {
      this.failHandshake(conn, ERROR_CODES.expired)
      return
    }

    const mac = (envelope.payload as { mac?: unknown }).mac
    const expected = computeMac(session.secret, sid, conn.nonce)
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
    const result = this.store.authorize(sid, conn.socketId)
    if (result === 'busy') {
      logger.warn(LOG_SCOPE, `second device attempted to pair (${conn.remote})`)
      this.failHandshake(conn, ERROR_CODES.busy)
      return
    }

    conn.phase = 'authorized'
    this.clearHandshakeTimer(conn)
    this.send(conn, 'auth-ok', { name: this.desktopName, proto })
    const name = deviceNameFromUa(conn.hello.ua)
    logger.info(LOG_SCOPE, `mobile authorized`, { name })
    this.options.onMobileConnected?.({ sid, ua: conn.hello.ua, name })
  }

  private onBye(conn: ConnectionState): void {
    logger.info(LOG_SCOPE, `bye from ${conn.remote}`)
    this.store.invalidate()
    conn.socket.close(1000, 'bye')
    this.options.onBye?.()
  }

  private onClose(conn: ConnectionState): void {
    this.clearHandshakeTimer(conn)
    this.connections.delete(conn.socketId)
    if (conn.phase === 'authorized') {
      this.store.release(conn.socketId)
      logger.info(LOG_SCOPE, 'authorized mobile disconnected — reconnect window open until expiry')
      this.options.onMobileDisconnected?.({ sid: conn.sid ?? '' })
    }
  }

  /** Terminal error: send, then close (webrtc.md). */
  private failHandshake(conn: ConnectionState, code: string): void {
    this.sendError(conn, code)
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
    record.fails += 1
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
}
