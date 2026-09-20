import { randomBytes } from 'node:crypto'
import type { PairingSessionView, QrPayloadV1 } from '../../shared/types'
import { toQrDataUrl } from './qr'
import { getLanIpv4Hosts } from './networkInfo'

/**
 * Session generation and lifecycle (docs/architecture/pairing.md):
 *
 *   generated ──▶ pending ──(successful auth)──▶ authorized ──▶ closed
 *      │            │                                │
 *      └─ regenerate ┴── expiry (e) ──────────────────┘
 *
 * One session at a time; generating a new QR invalidates the previous session.
 * `pending → authorized` happens exactly once per session (a second phone gets
 * `busy`), but the same session id may re-authenticate after a disconnect until
 * expiry or `bye` (reconnect window). Expiry is enforced authoritatively here,
 * on the desktop clock. Electron-import-free so unit tests run in plain Node.
 */

/** Session lifetime per pairing.md: expiry = now + 600 s (10 minutes). */
export const SESSION_TTL_SECONDS = 600
const SESSION_ID_BYTES = 16
const SESSION_SECRET_BYTES = 32
/** Error used when no LAN interface is available — surfaced as a friendly message. */
export const NO_LAN_INTERFACE_MESSAGE = 'No LAN IPv4 address found — cannot build a QR payload.'

export type SessionState = 'pending' | 'authorized' | 'closed'

export interface PairingSessionRecord {
  sid: string
  /** Raw 32-byte secret — never leaves this module except into the QR payload (as base64url). */
  secret: Buffer
  expiresAt: number
  state: SessionState
  /** Socket id of the authorized connection; null while none is connected (reconnect window). */
  authorizedSocketId: string | null
}

export interface PairingServerOptions {
  /** Actual signaling port (the QR payload must carry the real one, pairing.md `p`). */
  port: number
  /** Candidate hosts for the QR payload — injectable for tests; default: live LAN IPs. */
  hosts?: () => string[]
  /** Injected clock for tests. */
  now?: () => number
  /** Pushed to the renderer whenever the session is replaced (or creation failed → null). */
  onSessionChanged?: (view: PairingSessionView | null) => void
}

export class PairingServer {
  private readonly port: number
  private readonly hosts: () => string[]
  private readonly now: () => number
  private readonly onSessionChanged?: (view: PairingSessionView | null) => void
  private session: PairingSessionRecord | null = null
  private qrDataUrlCache = new WeakMap<PairingSessionRecord, string>()

  constructor(options: PairingServerOptions) {
    this.port = options.port
    this.hosts = options.hosts ?? getLanIpv4Hosts
    this.now = options.now ?? Date.now
    this.onSessionChanged = options.onSessionChanged
  }

  /**
   * Generate a fresh session and invalidate any previous one. Returns the
   * renderer view. Throws (and publishes `null` via onSessionChanged) when the
   * machine has no LAN IPv4 address — the QR is useless then.
   */
  async createSession(): Promise<PairingSessionView> {
    const hosts = this.hosts()
    if (hosts.length === 0) {
      this.session = null
      this.onSessionChanged?.(null)
      throw new Error(NO_LAN_INTERFACE_MESSAGE)
    }

    const sid = randomBytes(SESSION_ID_BYTES).toString('base64url')
    const secret = randomBytes(SESSION_SECRET_BYTES)
    const expiresAt = Math.floor(this.now() / 1000) + SESSION_TTL_SECONDS
    const record: PairingSessionRecord = { sid, secret, expiresAt, state: 'pending', authorizedSocketId: null }
    this.session = record

    const view = await this.buildView(record)
    this.onSessionChanged?.(view)
    return view
  }

  /** Current session id — public by design: it travels in the QR and every envelope. */
  get currentSid(): string | null {
    return this.session?.sid ?? null
  }

  /** Current renderer view, creating a session on first call. Null when creation failed. */
  async currentView(): Promise<PairingSessionView | null> {
    if (this.session === null) return null
    return this.buildView(this.session)
  }

  /** Session record for `sid`, or null when it doesn't match the current live session. */
  lookup(sid: string): PairingSessionRecord | null {
    // A closed (bye/regenerated) session is gone: new hellos for its sid fail.
    if (this.session !== null && this.session.sid === sid && this.session.state !== 'closed') {
      return this.session
    }
    return null
  }

  /** Desktop clock is authoritative (pairing.md). */
  isExpired(record: PairingSessionRecord): boolean {
    return Math.floor(this.now() / 1000) >= record.expiresAt
  }

  /**
   * First successful auth moves the session to authorized; a second connection
   * while the authorized one is still attached gets `busy`. After the authorized
   * socket disconnects (release), the same session id may re-authenticate until
   * expiry — the reconnect window.
   */
  authorize(sid: string, socketId: string): 'authorized' | 'busy' {
    const record = this.lookup(sid)
    if (record === null) return 'busy'
    if (record.state === 'authorized' && record.authorizedSocketId !== null) return 'busy'
    record.state = 'authorized'
    record.authorizedSocketId = socketId
    return 'authorized'
  }

  /** The authorized socket went away — keep the reconnect window open until expiry. */
  release(socketId: string): void {
    if (this.session !== null && this.session.authorizedSocketId === socketId) {
      this.session.authorizedSocketId = null
    }
  }

  /** `bye` from either side invalidates the session immediately (pairing.md). */
  invalidate(): void {
    if (this.session !== null) {
      this.session.state = 'closed'
      this.session.authorizedSocketId = null
    }
  }

  /**
   * Regenerate when the current session has expired (pending *or* authorized),
   * or when a previous creation attempt failed. Returns true when a fresh
   * session was created. Called from a slow sweep in the app entry — timers
   * stay at the edges so unit tests can drive the clock.
   */
  async ensureFreshSession(): Promise<boolean> {
    if (this.session === null) {
      await this.createSession()
      return true
    }
    if (this.isExpired(this.session)) {
      await this.createSession()
      return true
    }
    return false
  }

  private async buildView(record: PairingSessionRecord): Promise<PairingSessionView> {
    let qrDataUrl = this.qrDataUrlCache.get(record)
    if (qrDataUrl === undefined) {
      const payload: QrPayloadV1 = {
        v: 1,
        t: 'zfc',
        h: this.hosts(),
        p: this.port,
        s: record.sid,
        k: record.secret.toString('base64url'),
        e: record.expiresAt
      }
      qrDataUrl = await toQrDataUrl(encodeQrPayload(payload))
      this.qrDataUrlCache.set(record, qrDataUrl)
    }
    return { qrDataUrl, expiresAt: record.expiresAt }
  }
}

/** Compact JSON — no whitespace, key order as in the spec example. */
export function encodeQrPayload(payload: QrPayloadV1): string {
  const { v, t, h, p, s, k, e } = payload
  return JSON.stringify({ v, t, h, p, s, k, e })
}
