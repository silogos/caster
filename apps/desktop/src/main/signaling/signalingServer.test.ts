import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import { PNG } from 'pngjs'
import jsQR from 'jsqr'
import { PairingServer, SESSION_TTL_SECONDS } from '../pairing/pairingServer'
import { SignalingServer } from './signalingServer'
import { computeMac } from './handshake'
import { encodeEnvelope, type Envelope } from './envelope'
import type { QrPayloadV1 } from '../../shared/types'

/**
 * Loopback protocol tests (roadmap Phase3): a real WebSocket server plus a real
 * `ws` client acting as the phone. The client bootstraps exactly like the app:
 * it "scans" the QR (PNG → jsQR → payload v1), then connects and runs the
 * hello/challenge/auth/auth-ok handshake. No media, no Electron.
 *
 * Tests share one server and run in file order; the order matters where a test
 * leaves session state behind (bad-auth blocks the address until the clock is
 * advanced, bye replaces the session).
 */

const TEST_HOSTS = ['127.0.0.1']
const FIXED_NOW_MS = 1_800_000_000_000
const DESKTOP_NAME = 'test-desktop'
const UA = 'ZeroFrictionCast/0.1.0 (Android 15; Pixel 8)'

interface Harness {
  port: number
  payload: QrPayloadV1
  pairing: PairingServer
  signaling: SignalingServer
  connectedEvents: Array<{ sid: string; ua: string; name: string }>
  disconnectedEvents: Array<{ sid: string }>
  getByeEvents: () => number
  sessionViews: Array<unknown>
  setNow: (ms: number) => void
}

let harness: Harness | null = null

/** Mirror the production wiring from src/main/index.ts, minus Electron. */
async function startHarness(): Promise<Harness> {
  let nowMs = FIXED_NOW_MS
  const connectedEvents: Array<{ sid: string; ua: string; name: string }> = []
  const disconnectedEvents: Array<{ sid: string }> = []
  const sessionViews: Array<unknown> = []
  let byeEvents = 0
  let pairing!: PairingServer

  const signaling = new SignalingServer({
    desktopName: DESKTOP_NAME,
    now: () => nowMs,
    onMobileConnected: (event) => connectedEvents.push(event),
    onMobileDisconnected: (event) => disconnectedEvents.push(event),
    onBye: () => {
      byeEvents += 1
      void pairing.createSession()
    }
  })
  const port = await signaling.start(0)
  pairing = new PairingServer({
    port,
    hosts: () => [...TEST_HOSTS],
    now: () => nowMs,
    onSessionChanged: (view) => sessionViews.push(view)
  })
  signaling.attachPairing(pairing)
  const view = await pairing.createSession()

  const png = PNG.sync.read(Buffer.from(view.qrDataUrl.slice('data:image/png;base64,'.length), 'base64'))
  const decoded = jsQR(new Uint8ClampedArray(png.data), png.width, png.height)
  if (decoded === null) throw new Error('test QR did not decode')
  const payload = JSON.parse(decoded.data) as QrPayloadV1

  return {
    port,
    payload,
    pairing,
    signaling,
    connectedEvents,
    disconnectedEvents,
    getByeEvents: () => byeEvents,
    sessionViews,
    setNow: (ms: number) => {
      nowMs = ms
    }
  }
}

/** A minimal scripted phone: connect, exchange envelopes, collect replies. */
class PhoneClient {
  private socket!: WebSocket
  private seq = 0
  private readonly replies: Envelope[] = []
  closed!: Promise<number>

  constructor(
    private readonly port: number,
    private readonly sid: string,
    private readonly secret: Buffer,
    private readonly ua: string = UA
  ) {}

  connect(): Promise<this> {
    this.socket = new WebSocket(`ws://127.0.0.1:${this.port}/zfc/v1`)
    this.closed = new Promise((resolve) => {
      this.socket.on('close', (code) => resolve(code))
    })
    this.socket.on('message', (data) => {
      this.replies.push(JSON.parse(data.toString()) as Envelope)
    })
    return new Promise((resolve, reject) => {
      this.socket.once('open', () => resolve(this))
      this.socket.once('error', reject)
    })
  }

  send(type: string, payload: Record<string, unknown>): void {
    this.seq += 1
    this.sendRaw(encodeEnvelope(type as never, this.seq, this.sid, payload as never))
  }

  sendRaw(raw: string): void {
    this.socket.send(raw)
  }

  /** Run hello → challenge → auth; returns the reply to the auth. */
  async handshake(protoMin = 1, protoMax = 1): Promise<Envelope> {
    this.send('hello', { ua: this.ua, protoMin, protoMax })
    const challenge = await this.nextReply(1_000)
    if (challenge.type !== 'challenge') return challenge
    const mac = computeMac(this.secret, this.sid, (challenge.payload as { n: string }).n)
    this.send('auth', { mac })
    return this.nextReply(1_000)
  }

  nextReply(timeoutMs: number): Promise<Envelope> {
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

  expectNoReply(timeoutMs: number): Promise<void> {
    return new Promise((resolve, reject) => {
      setTimeout(() => {
        if (this.replies.length === 0) resolve()
        else reject(new Error(`unexpected reply: ${JSON.stringify(this.replies[0])}`))
      }, timeoutMs)
    })
  }

  waitClosed(timeoutMs = 2_000): Promise<number> {
    return Promise.race([
      this.closed,
      new Promise<number>((_, reject) => setTimeout(() => reject(new Error('socket did not close')), timeoutMs))
    ])
  }

  close(): void {
    this.socket.close(1000)
  }
}

beforeAll(async () => {
  harness = await startHarness()
})

afterAll(() => {
  harness?.signaling.stop()
})

describe('signaling loopback (webrtc.md envelope + pairing.md handshake)', () => {
  it('completes the full handshake and reports the phone to the coordinator', async () => {
    const h = harness as Harness
    const phone = await new PhoneClient(h.port, h.payload.s, Buffer.from(h.payload.k, 'base64url')).connect()

    const authOk = await phone.handshake()
    expect(authOk.type).toBe('auth-ok')
    expect((authOk.payload as { name: string }).name).toBe(DESKTOP_NAME)
    expect((authOk.payload as { proto: number }).proto).toBe(1)
    // Per-sender seq starts at 1: challenge=1, auth-ok=2.
    expect(authOk.seq).toBe(2)

    expect(h.connectedEvents).toEqual([{ sid: h.payload.s, ua: UA, name: 'Pixel 8' }])
    phone.close()
  })

  it('answers busy for a second phone while the first is connected, and allows reconnect after a drop', async () => {
    const h = harness as Harness
    // Let the previous test's socket-close event finish landing before counting.
    await new Promise((resolve) => setTimeout(resolve, 200))
    const disconnectedBefore = h.disconnectedEvents.length
    const first = await new PhoneClient(h.port, h.payload.s, Buffer.from(h.payload.k, 'base64url')).connect()
    expect((await first.handshake()).type).toBe('auth-ok')

    const second = await new PhoneClient(h.port, h.payload.s, Buffer.from(h.payload.k, 'base64url')).connect()
    const busy = await second.handshake()
    expect(busy.type).toBe('error')
    expect((busy.payload as { code: string }).code).toBe('busy')
    await second.waitClosed()

    // The authorized phone disconnects — reconnect window opens for the same sid.
    first.close()
    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(h.disconnectedEvents.length).toBe(disconnectedBefore + 1)

    const reconnected = await new PhoneClient(h.port, h.payload.s, Buffer.from(h.payload.k, 'base64url')).connect()
    expect((await reconnected.handshake()).type).toBe('auth-ok')
    reconnected.close()
  })

  it('answers bad-version for an unsupported protocol range', async () => {
    const h = harness as Harness
    const phone = await new PhoneClient(h.port, h.payload.s, Buffer.from(h.payload.k, 'base64url')).connect()

    const reply = await phone.handshake(5, 9)
    expect(reply.type).toBe('error')
    expect((reply.payload as { code: string }).code).toBe('bad-version')
    await phone.waitClosed()
  })

  it('drops pre-auth non-hello frames with bad-message but keeps the socket open', async () => {
    const h = harness as Harness
    const phone = await new PhoneClient(h.port, h.payload.s, Buffer.from(h.payload.k, 'base64url')).connect()

    phone.send('bye', {})
    const reply = await phone.nextReply(1_000)
    expect(reply.type).toBe('error')
    expect((reply.payload as { code: string }).code).toBe('bad-message')

    // Recoverable: hello still works afterwards.
    expect((await phone.handshake()).type).toBe('auth-ok')
    phone.close()
  })

  it('closes with bad-version on an unknown envelope version', async () => {
    const h = harness as Harness
    const phone = await new PhoneClient(h.port, h.payload.s, Buffer.alloc(32)).connect()

    phone.sendRaw(JSON.stringify({ v: 2, type: 'hello', seq: 1, sid: h.payload.s, payload: {} }))
    const reply = await phone.nextReply(1_000)
    expect((reply.payload as { code: string }).code).toBe('bad-version')
    await phone.waitClosed()
  })

  it('rejects an expired session with expired', async () => {
    const h = harness as Harness
    h.setNow(FIXED_NOW_MS + (SESSION_TTL_SECONDS + 1) * 1000)
    const phone = await new PhoneClient(h.port, h.payload.s, Buffer.from(h.payload.k, 'base64url')).connect()

    const reply = await phone.handshake()
    expect(reply.type).toBe('error')
    expect((reply.payload as { code: string }).code).toBe('expired')
    await phone.waitClosed()
    h.setNow(FIXED_NOW_MS)
  })

  it('rejects a wrong mac with bad-auth and rate-limits the address', async () => {
    const h = harness as Harness
    const phone = await new PhoneClient(h.port, h.payload.s, Buffer.alloc(32)).connect()

    const reply = await phone.handshake()
    expect(reply.type).toBe('error')
    expect((reply.payload as { code: string }).code).toBe('bad-auth')
    await phone.waitClosed()

    // Next connection from the same address is dropped before the handshake.
    const blocked = await new PhoneClient(h.port, h.payload.s, Buffer.alloc(32)).connect()
    await expect(blocked.expectNoReply(500)).resolves.toBeUndefined()
    await blocked.waitClosed()

    // The injected clock is frozen; advance past the 1 s block so later tests connect.
    h.setNow(FIXED_NOW_MS + 2_000)
  })

  it('honours bye: session invalidated, coordinator regenerates a fresh QR', async () => {
    const h = harness as Harness
    const phone = await new PhoneClient(h.port, h.payload.s, Buffer.from(h.payload.k, 'base64url')).connect()
    expect((await phone.handshake()).type).toBe('auth-ok')

    const sessionsBefore = h.sessionViews.length
    phone.send('bye', {})
    await phone.waitClosed()

    expect(h.getByeEvents()).toBe(1)
    // The coordinator (mirrored here) created a fresh session with a new sid.
    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(h.sessionViews.length).toBe(sessionsBefore + 1)
    expect(h.pairing.lookup(h.payload.s)).toBeNull()
  })
})
