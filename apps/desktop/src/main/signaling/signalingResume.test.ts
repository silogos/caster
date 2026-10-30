import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PairingServer } from '../pairing/pairingServer'
import { TrustStore } from '../pairing/trustStore'
import { BYE_FORGET, SignalingServer } from './signalingServer'
import { computeMac, deriveTrustKey } from './handshake'
import { encodeEnvelope, type Envelope } from './envelope'

/**
 * Loopback tests for the persistent session (pairing.md §Persistent sessions,
 * ADR-006): QR pairing writes the trust, then the phone reconnects with the
 * derived trustKey and `hello {resume}` — no QR anywhere. Revocation (`bye
 * forget`) and key rotation (re-pair) are exercised end-to-end over real
 * sockets, no Electron.
 */

const DESKTOP_NAME = 'test-desktop'
const DESKTOP_ID = 'desktop-id-1'
const HOSTS = ['192.168.1.50']
const UA = 'ZeroFrictionCast/0.1.0 (Android 16; TB321FU)'
const DEVICE_ID = 'phone-a-uuid'

interface Harness {
  port: number
  pairing: PairingServer
  signaling: SignalingServer
  trust: TrustStore
  connectedEvents: Array<{ deviceId?: string; resume: boolean }>
  byeReasons: Array<string | undefined>
  setNow: (ms: number) => void
  freshQr: () => Promise<{ sid: string; secret: Buffer }>
}

let harness: Harness | null = null
let dir = ''

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'zfc-resume-'))
  let nowMs = 1_800_000_000_000
  const connectedEvents: Array<{ deviceId?: string; resume: boolean }> = []
  const byeReasons: Array<string | undefined> = []
  let pairing!: PairingServer

  const trust = new TrustStore({ file: () => join(dir, 'trust.json'), now: () => nowMs })
  trust.load()
  const signaling = new SignalingServer({
    desktopName: DESKTOP_NAME,
    trustStore: trust,
    desktopId: DESKTOP_ID,
    currentHosts: () => [...HOSTS],
    now: () => nowMs,
    onMobileConnected: (event) => connectedEvents.push({ deviceId: event.deviceId, resume: event.resume }),
    onBye: (reason) => {
      byeReasons.push(reason)
      void pairing.createSession()
    }
  })
  const port = await signaling.start(0)
  pairing = new PairingServer({
    port,
    hosts: () => ['127.0.0.1'],
    now: () => nowMs,
    onSessionChanged: () => undefined
  })
  signaling.attachPairing(pairing)
  await pairing.createSession()

  harness = {
    port,
    pairing,
    signaling,
    trust,
    connectedEvents,
    byeReasons,
    setNow: (ms: number) => {
      nowMs = ms
    },
    freshQr: async () => {
      await pairing.createSession()
      const sid = pairing.currentSid as string
      const secret = (pairing.lookup(sid) as { secret: Buffer }).secret
      return { sid, secret }
    }
  }
})

afterAll(() => {
  harness?.signaling.stop()
  rmSync(dir, { recursive: true, force: true })
})

/** The scripted phone: QR handshakes and resume handshakes over real sockets. */
class PhoneClient {
  private readonly socket!: WebSocket
  private seq = 0
  private readonly replies: Envelope[] = []

  constructor(
    private readonly port: number,
    private readonly sid: string,
    private readonly secret: Buffer
  ) {}

  connect(): Promise<this> {
    const socket = new WebSocket(`ws://127.0.0.1:${this.port}/zfc/v1`)
    Object.assign(this, { socket })
    socket.on('message', (data) => {
      this.replies.push(JSON.parse(data.toString()) as Envelope)
    })
    return new Promise((resolve, reject) => {
      socket.once('open', () => resolve(this))
      socket.once('error', reject)
    })
  }

  sendRaw(raw: string): void {
    this.socket.send(raw)
  }

  private send(type: string, payload: Record<string, unknown>): void {
    this.seq += 1
    this.socket.send(encodeEnvelope(type as never, this.seq, this.sid, payload as never))
  }

  /** QR handshake: hello {deviceId?} → challenge → auth(HMAC(secret, sid‖n)). */
  async handshakeQr(deviceId?: string): Promise<Envelope> {
    this.send('hello', { ua: UA, protoMin: 1, protoMax: 1, ...(deviceId !== undefined ? { deviceId } : {}) })
    return this.finishHandshake()
  }

  /** Resume handshake: hello {resume: true, deviceId} with sid = deviceId (ADR-006). */
  async handshakeResume(deviceId: string): Promise<Envelope> {
    this.send('hello', { ua: UA, protoMin: 1, protoMax: 1, deviceId, resume: true })
    return this.finishHandshake()
  }

  private async finishHandshake(): Promise<Envelope> {
    const challenge = await this.nextReply()
    if (challenge.type !== 'challenge') return challenge
    const mac = computeMac(this.secret, this.sid, (challenge.payload as { n: string }).n)
    this.send('auth', { mac })
    return this.nextReply()
  }

  nextReply(timeoutMs = 1_000): Promise<Envelope> {
    return new Promise((resolve, reject) => {
      const started = Date.now()
      const poll = (): void => {
        if (this.replies.length > 0) {
          resolve(this.replies.shift() as Envelope)
        } else if (Date.now() - started > timeoutMs) {
          reject(new Error('timed out waiting for a reply'))
        } else {
          setTimeout(poll, 10)
        }
      }
      poll()
    })
  }

  bye(reason?: string): void {
    this.send('bye', reason === undefined ? {} : { reason })
    this.socket.close(1000)
  }

  close(): void {
    this.socket.close(1000)
  }
}

/** Let socket-close bookkeeping land between tests (same convention as the main loopback file). */
const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 200))

describe('persistent session (resume without QR, ADR-006)', () => {
  it('writes the trust on the first QR pairing and answers auth-ok with desktopId + hosts', async () => {
    const h = harness as Harness
    const qr = await h.freshQr()
    const phone = await new PhoneClient(h.port, qr.sid, qr.secret).connect()

    const authOk = await phone.handshakeQr(DEVICE_ID)
    expect(authOk.type).toBe('auth-ok')
    expect((authOk.payload as { desktopId?: string }).desktopId).toBe(DESKTOP_ID)
    expect((authOk.payload as { hosts?: string[] }).hosts).toEqual(HOSTS)

    // The stored trustKey is exactly what both sides derive from this QR session.
    const trust = h.trust.get()
    expect(trust).not.toBeNull()
    expect(trust?.deviceId).toBe(DEVICE_ID)
    expect(trust?.key.equals(deriveTrustKey(qr.secret, qr.sid))).toBe(true)
    expect(h.connectedEvents[h.connectedEvents.length - 1]).toMatchObject({ deviceId: DEVICE_ID, resume: false })
    phone.close()
    await settle()
  })

  it('resumes with the derived trustKey after the QR session is gone — no QR involved', async () => {
    const h = harness as Harness
    await h.freshQr() // regenerate: the pairing sid the phone knew is dead
    const key = (h.trust.get() as { key: Buffer }).key
    const phone = await new PhoneClient(h.port, DEVICE_ID, key).connect()

    const authOk = await phone.handshakeResume(DEVICE_ID)
    expect(authOk.type).toBe('auth-ok')
    expect((authOk.payload as { desktopId?: string }).desktopId).toBe(DESKTOP_ID)
    expect(h.connectedEvents[h.connectedEvents.length - 1]).toMatchObject({ resume: true })
    phone.close()
    await settle()
  })

  it('answers unknown-device for a resume it does not trust — carrying the sid', async () => {
    const h = harness as Harness
    const phone = await new PhoneClient(h.port, 'a-stranger', Buffer.alloc(32, 9)).connect()
    const reply = await phone.handshakeResume('a-stranger')
    expect(reply.type).toBe('error')
    expect((reply.payload as { code: string }).code).toBe('unknown-device')
    // Every reply names the session: the mobile rejects sid-less frames as
    // malformed, and handshake-phase errors used to go out sid-less (found
    // live 2026-10-30 — the phone ignored its own unknown-device error).
    expect(reply.sid).toBe('a-stranger')
  })

  it('answers busy for a second resume while the trusted phone is already authorized', async () => {
    const h = harness as Harness
    await settle()
    const key = (h.trust.get() as { key: Buffer }).key
    // One trusted phone, one live connection (e.g. a stale socket the server
    // has not seen drop yet) — a second resume from the same device is busy.
    // (A *different* phone cannot QR-pair over an existing trust in
    // production: the QR hero is only shown while nothing is trusted.)
    const live = await new PhoneClient(h.port, DEVICE_ID, key).connect()
    expect((await live.handshakeResume(DEVICE_ID)).type).toBe('auth-ok')

    const resuming = await new PhoneClient(h.port, DEVICE_ID, key).connect()
    const reply = await resuming.handshakeResume(DEVICE_ID)
    expect(reply.type).toBe('error')
    expect((reply.payload as { code: string }).code).toBe('busy')
    expect(reply.sid).toBe(DEVICE_ID)
    live.close()
    resuming.close()
    await settle()
  })

  it('rejects a resume with the wrong key (bad-auth) — a photographed old QR cannot resume after a re-pair', async () => {
    const h = harness as Harness
    const staleKey = deriveTrustKey(Buffer.alloc(32, 5), 'some-old-session')
    const phone = await new PhoneClient(h.port, DEVICE_ID, staleKey).connect()
    const reply = await phone.handshakeResume(DEVICE_ID)
    expect(reply.type).toBe('error')
    expect((reply.payload as { code: string }).code).toBe('bad-auth')
    expect(reply.sid).toBe(DEVICE_ID)
    // The injected clock is frozen; advance past the 1 s rate-limit block.
    h.setNow(1_800_000_002_000)
    await settle()
  })

  it("revokes the trust on the phone's `bye forget` — later resumes get unknown-device", async () => {
    const h = harness as Harness
    const key = (h.trust.get() as { key: Buffer }).key
    const phone = await new PhoneClient(h.port, DEVICE_ID, key).connect()
    expect((await phone.handshakeResume(DEVICE_ID)).type).toBe('auth-ok')

    phone.bye(BYE_FORGET)
    await settle()
    expect(h.trust.get()).toBeNull()
    expect(h.byeReasons[h.byeReasons.length - 1]).toBe(BYE_FORGET)

    const again = await new PhoneClient(h.port, DEVICE_ID, key).connect()
    expect(((await again.handshakeResume(DEVICE_ID)).payload as { code: string }).code).toBe('unknown-device')
    again.close()
  })

  it('keeps the trust through a plain bye — the phone reconnects with one tap', async () => {
    const h = harness as Harness
    const qr = await h.freshQr()
    const phone = await new PhoneClient(h.port, qr.sid, qr.secret).connect()
    expect((await phone.handshakeQr(DEVICE_ID)).type).toBe('auth-ok')
    phone.bye('user-ended')
    await settle()
    expect(h.trust.get()?.deviceId).toBe(DEVICE_ID)

    const key = (h.trust.get() as { key: Buffer }).key
    const resumed = await new PhoneClient(h.port, DEVICE_ID, key).connect()
    expect((await resumed.handshakeResume(DEVICE_ID)).type).toBe('auth-ok')
    resumed.close()
    await settle()
  })

  it('rotates the trustKey on a re-pair: the new QR derives a new key for the same phone', async () => {
    const h = harness as Harness
    const oldKey = (h.trust.get() as { key: Buffer }).key
    const qr = await h.freshQr()
    const phone = await new PhoneClient(h.port, qr.sid, qr.secret).connect()
    expect((await phone.handshakeQr(DEVICE_ID)).type).toBe('auth-ok')
    phone.close()
    await settle()

    const newKey = (h.trust.get() as { key: Buffer }).key
    expect(newKey.equals(oldKey)).toBe(false)

    const stale = await new PhoneClient(h.port, DEVICE_ID, oldKey).connect()
    expect(((await stale.handshakeResume(DEVICE_ID)).payload as { code: string }).code).toBe('bad-auth')
    stale.close()
    h.setNow(1_800_000_004_000)
    await settle()

    const fresh = await new PhoneClient(h.port, DEVICE_ID, newKey).connect()
    expect((await fresh.handshakeResume(DEVICE_ID)).type).toBe('auth-ok')
    fresh.close()
  })

  it('rejects a non-boolean resume flag as bad-message', async () => {
    const h = harness as Harness
    const phone = await new PhoneClient(h.port, DEVICE_ID, Buffer.alloc(32, 3)).connect()
    const malformed = JSON.stringify({ v: 1, type: 'hello', seq: 1, sid: DEVICE_ID, payload: { ua: UA, deviceId: DEVICE_ID, resume: 'yes', protoMin: 1, protoMax: 1 } })
    phone.sendRaw(malformed)
    const reply = await phone.nextReply()
    expect(reply.type).toBe('error')
    expect((reply.payload as { code: string }).code).toBe('bad-message')
  })
})
