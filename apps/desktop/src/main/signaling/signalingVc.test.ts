import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import { PairingServer } from '../pairing/pairingServer'
import { SignalingServer } from './signalingServer'
import { computeMac } from './handshake'
import { encodeEnvelope, type Envelope } from './envelope'

/**
 * Virtual-camera control loopback (webrtc.md proto 2, ADR-007): the phone's
 * toggle and the desktop's status push over a real socket — no camera, no
 * Electron, no media. Mirrors the Phase4 loopback harness (signalingProtocol.
 * test.ts) with the vc callbacks wired instead of the media ones.
 */

const DESKTOP_NAME = 'vc-test-desktop'
const UA = 'ZeroFrictionCast/0.1.0 (Android15; Pixel8)'

interface VcHarness {
  port: number
  sid: string
  secret: Buffer
  signaling: SignalingServer
  queries: number[]
  starts: number[]
  stops: number[]
  routingQueries: number[]
  routingSets: string[]
}

let harness: VcHarness | null = null

async function startHarness(): Promise<VcHarness> {
  const queries: number[] = []
  const starts: number[] = []
  const stops: number[] = []
  const routingQueries: number[] = []
  const routingSets: string[] = []
  const signaling = new SignalingServer({
    desktopName: DESKTOP_NAME,
    onVcQuery: () => queries.push(Date.now()),
    onVcStart: () => starts.push(Date.now()),
    onVcStop: () => stops.push(Date.now()),
    onAudioRoutingQuery: () => routingQueries.push(Date.now()),
    onAudioRoutingSet: (mode) => routingSets.push(mode)
  })
  const port = await signaling.start(0)
  const pairing = new PairingServer({ port, hosts: () => ['127.0.0.1'], now: () => Date.now() })
  signaling.attachPairing(pairing)
  await pairing.createSession()
  const sid = pairing.currentSid
  const record = sid !== null ? pairing.lookup(sid) : null
  if (sid === null || record === null) throw new Error('session creation failed')
  return { port, sid, secret: record.secret, signaling, queries, starts, stops, routingQueries, routingSets }
}


/** Collects every envelope the desktop sends; auto-pongs like the real client. */
class VcPhone {
  private socket!: WebSocket
  private seq = 0
  private readonly replies: Envelope[] = []

  constructor(
    private readonly port: number,
    private readonly sid: string,
    private readonly secret: Buffer
  ) {}

  connect(): Promise<this> {
    this.socket = new WebSocket(`ws://127.0.0.1:${this.port}/zfc/v1`)
    this.socket.on('message', (data) => {
      const envelope = JSON.parse(data.toString()) as Envelope
      this.replies.push(envelope)
      if (envelope.type === 'ping') {
        this.send('pong', { t: (envelope.payload as { t: number }).t })
      }
    })
    return new Promise((resolve, reject) => {
      this.socket.once('open', () => resolve(this))
      this.socket.once('error', reject)
    })
  }

  send(type: string, payload: Record<string, unknown> = {}): void {
    this.seq += 1
    this.socket.send(encodeEnvelope(type as never, this.seq, this.sid, payload as never))
  }

  /** hello → challenge → auth with the real HMAC; returns the auth-ok reply. */
  async handshake(protoMin: number, protoMax: number): Promise<Envelope> {
    this.send('hello', { ua: UA, protoMin, protoMax })
    const challenge = await this.nextReply(1_000)
    if (challenge.type !== 'challenge') return challenge
    const mac = computeMac(this.secret, this.sid, (challenge.payload as { n: string }).n)
    this.send('auth', { mac })
    return this.nextReply(1_000)
  }

  /** Next non-ping reply (heartbeat pings are liveness noise). */
  nextReply(timeoutMs: number): Promise<Envelope> {
    return new Promise((resolve, reject) => {
      const started = Date.now()
      const poll = (): void => {
        const first = this.replies.findIndex((reply) => reply.type !== 'ping')
        if (first >= 0) {
          resolve(this.replies.splice(first, 1)[0])
        } else if (Date.now() - started > timeoutMs) {
          reject(new Error('timed out waiting for a reply'))
        } else {
          setTimeout(poll, 10)
        }
      }
      poll()
    })
  }

  countReplies(type: string): number {
    return this.replies.filter((reply) => reply.type === type).length
  }

  close(): void {
    this.socket.close(1000)
  }
}

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

let activePhone: VcPhone | null = null

afterEach(async () => {
  if (activePhone !== null) {
    activePhone.close()
    await sleep(150)
    activePhone = null
  }
})

beforeAll(async () => {
  harness = await startHarness()
})

afterAll(() => {
  harness?.signaling.stop()
})

describe('signaling loopback: vc-* control (webrtc.md proto 2, ADR-007)', () => {
  it('negotiates proto 2 with a proto-2 phone and proto 1 with an old phone', async () => {
    const h = harness!
    const modern = await new VcPhone(h.port, h.sid, h.secret).connect()
    activePhone = modern
    const modernOk = await modern.handshake(1, 2)
    expect(modernOk.type).toBe('auth-ok')
    expect((modernOk.payload as { proto: number }).proto).toBe(2)
    modern.close()
    await sleep(150)

    const old = await new VcPhone(h.port, h.sid, h.secret).connect()
    activePhone = old
    const oldOk = await old.handshake(1, 1)
    expect(oldOk.type).toBe('auth-ok')
    expect((oldOk.payload as { proto: number }).proto).toBe(1)
  })

  it('delivers vc-query/start/stop to the coordinator on a proto-2 connection', async () => {
    const h = harness!
    const phone = await new VcPhone(h.port, h.sid, h.secret).connect()
    activePhone = phone
    await phone.handshake(1, 2)
    expect(h.queries.length).toBe(0)
    phone.send('vc-query')
    phone.send('vc-start')
    phone.send('vc-stop')
    await sleep(150)
    expect(h.queries.length).toBe(1)
    expect(h.starts.length).toBe(1)
    expect(h.stops.length).toBe(1)
  })

  it('pushes vc-status to the phone (query reply and state changes)', async () => {
    const h = harness!
    const phone = await new VcPhone(h.port, h.sid, h.secret).connect()
    activePhone = phone
    await phone.handshake(1, 2)
    h.signaling.sendVcStatus({ available: true, needsRegistration: true, active: false })
    const status = await phone.nextReply(1_000)
    expect(status.type).toBe('vc-status')
    expect(status.payload).toEqual({ available: true, needsRegistration: true, active: false })
    h.signaling.sendVcStatus({ available: true, needsRegistration: false, active: true })
    const active = await phone.nextReply(1_000)
    expect(active.type).toBe('vc-status')
    expect((active.payload as { active: boolean }).active).toBe(true)
  })

  it('never sends vc-* to a proto-1 phone and rejects its vc frames recoverably', async () => {
    const h = harness!
    const phone = await new VcPhone(h.port, h.sid, h.secret).connect()
    activePhone = phone
    await phone.handshake(1, 1)
    phone.send('vc-start')
    const error = await phone.nextReply(1_000)
    expect(error.type).toBe('error')
    expect((error.payload as { code: string }).code).toBe('bad-message')
    // Recoverable (webrtc.md): the socket stays open.
    await sleep(200)
    expect(phone.countReplies('close')).toBe(0)
    // And a status push must not leak to the old phone.
    h.signaling.sendVcStatus({ available: true, needsRegistration: false, active: false })
    await sleep(150)
    expect(phone.countReplies('vc-status')).toBe(0)
  })

  it('rejects vc-* before auth and vc-status from the phone', async () => {
    const h = harness!
    const phone = await new VcPhone(h.port, h.sid, h.secret).connect()
    activePhone = phone
    // Half a handshake: hello answered, auth never sent — the vc frame now
    // reaches the authorized-only gate instead of the awaiting-hello one.
    phone.send('hello', { ua: UA, protoMin: 1, protoMax: 2 })
    const challenge = await phone.nextReply(1_000)
    expect(challenge.type).toBe('challenge')
    phone.send('vc-start')
    const preAuth = await phone.nextReply(1_000)
    expect(preAuth.type).toBe('error')
    // webrtc.md: only hello/auth are accepted before auth-ok — vc frames get
    // the generic pre-auth rejection, not any vc-specific one.
    expect((preAuth.payload as { msg?: string }).msg).toContain('expected auth, got vc-start')
    // Now finish the same handshake (auth for the challenge above) and prove
    // the phone→desktop direction of vc-status is rejected too.
    const nonce = (challenge.payload as { n: string }).n
    phone.send('auth', { mac: computeMac(h.secret, h.sid, nonce) })
    const authOk = await phone.nextReply(1_000)
    expect(authOk.type).toBe('auth-ok')
    phone.send('vc-status', { available: true, needsRegistration: false, active: true })
    const wrongWay = await phone.nextReply(1_000)
    expect(wrongWay.type).toBe('error')
    expect((wrongWay.payload as { msg?: string }).msg).toContain('sender of vc-status')
  })

  it('delivers audio-routing-query/set to the coordinator on a proto-2 connection', async () => {
    const h = harness!
    const phone = await new VcPhone(h.port, h.sid, h.secret).connect()
    activePhone = phone
    await phone.handshake(1, 2)
    phone.send('audio-routing-query')
    phone.send('audio-routing-set', { mode: 'cable' })
    await sleep(150)
    expect(h.routingQueries.length).toBe(1)
    expect(h.routingSets).toEqual(['cable'])
  })

  it('rejects a malformed audio-routing-set mode recoverably', async () => {
    const h = harness!
    h.routingSets.length = 0 // shared harness arrays — this test owns the delta
    const phone = await new VcPhone(h.port, h.sid, h.secret).connect()
    activePhone = phone
    await phone.handshake(1, 2)
    phone.send('audio-routing-set', { mode: 'loudhailer' })
    const reply = await phone.nextReply(1_000)
    expect(reply.type).toBe('error')
    expect((reply.payload as { msg?: string }).msg).toContain('malformed audio-routing-set mode')
    expect(h.routingSets).toEqual([])
  })

  it('pushes audio-routing-status to a proto-2 phone', async () => {
    const h = harness!
    const phone = await new VcPhone(h.port, h.sid, h.secret).connect()
    activePhone = phone
    await phone.handshake(1, 2)
    h.signaling.sendAudioRoutingStatus({ mode: 'both', cableAvailable: true })
    const status = await phone.nextReply(1_000)
    expect(status.type).toBe('audio-routing-status')
    expect(status.payload).toEqual({ mode: 'both', cableAvailable: true })
  })
})
