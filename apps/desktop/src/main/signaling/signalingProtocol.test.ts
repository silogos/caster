import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import { PNG } from 'pngjs'
import jsQR from 'jsqr'
import { PairingServer, SESSION_TTL_SECONDS } from '../pairing/pairingServer'
import { SignalingServer, type SdpOfferEvent, type IceCandidateEvent } from './signalingServer'
import { computeMac } from './handshake'
import { encodeEnvelope, type Envelope } from './envelope'
import type { CastSessionInfo, QrPayloadV1 } from '../../shared/types'

/**
 * Phase4 loopback protocol tests (roadmap): two endpoints exchanging *recorded*
 * SDP/ICE blobs over a real WebSocket — no WebRTC, no media, no Electron.
 * Covers the full message set (webrtc.md), the heartbeat, and the
 * reconnect-within-TTL / expiry paths of the connection lifecycle.
 *
 * The recorded candidates include a Chromium mDNS-obfuscated host candidate
 * (`*.local`, risk R4): signaling must pass candidates through verbatim and
 * stay agnostic to their form. Resolving them on a real LAN is a Phase5
 * concern (first real media connection).
 */

const TEST_HOSTS = ['127.0.0.1']
const FIXED_NOW_MS = 1_800_000_000_000
const DESKTOP_NAME = 'test-desktop'
const UA = 'ZeroFrictionCast/0.1.0 (Android15; Pixel8)'
/** Tight heartbeat values so lifecycle tests run in milliseconds, not 5/15 s. */
const HEARTBEAT_INTERVAL_MS = 50
const HEARTBEAT_TIMEOUT_MS = 200

/** Recorded SDP offer (video + audio, H.264/VP8/Opus) — realistic size and line noise. */
const RECORDED_SDP_OFFER = [
  'v=0',
  'o=-4611731400430051336 2 IN IP4 127.0.0.1',
  's=-',
  't=0 0',
  'a=group:BUNDLE 0 1',
  'm=video 9 UDP/TLS/RTP/SAVPF 96 97',
  'a=mid:0',
  'a=rtpmap:96 H264/90000',
  'a=rtpmap:97 VP8/90000',
  'm=audio 9 UDP/TLS/RTP/SAVPF 111',
  'a=rtpmap:111 opus/48000/2'
].join('\r\n') + '\r\n'

/** Recorded SDP answer: the desktop picked H.264 (codec policy, webrtc.md). */
const RECORDED_SDP_ANSWER = [
  'v=0',
  'o=- 4611731400430051336 2 IN IP4 127.0.0.1',
  's=-',
  't=0 0',
  'a=group:BUNDLE 0 1',
  'm=video 9 UDP/TLS/RTP/SAVPF 96',
  'a=mid:0',
  'a=rtpmap:96 H264/90000',
  'm=audio 9 UDP/TLS/RTP/SAVPF 111',
  'a=rtpmap:111 opus/48000/2'
].join('\r\n') + '\r\n'

/**
 * Recorded ICE candidates. The first is a Chromium mDNS-obfuscated host
 * candidate (risk R4): a `*.local` name that must traverse signaling untouched,
 * whatever the LAN does with mDNS resolution later.
 */
const RECORDED_MDNS_CANDIDATE = {
  candidate:
    'candidate:842163049 1 udp 1677729535 7f8a9b2c-3d4e-5f60-a7b8-c9d0e1f23a45.local 51812 typ host generation 0 ufrag 9XYZ',
  sdpMid: '0',
  sdpMLineIndex: 0,
  usernameFragment: '9XYZ'
}
const RECORDED_HOST_CANDIDATE = {
  candidate: 'candidate:842163048 1 udp 1677729535 192.168.1.42 51811 typ host generation 0 ufrag 9XYZ',
  sdpMid: '0',
  sdpMLineIndex: 0,
  usernameFragment: '9XYZ'
}

const RECORDED_SESSION_INFO: CastSessionInfo = {
  profile: 'balanced',
  width: 1280,
  height: 720,
  fps: 30,
  gameAudio: true,
  mic: false
}

interface Harness {
  port: number
  payload: QrPayloadV1
  secret: Buffer
  pairing: PairingServer
  signaling: SignalingServer
  offers: SdpOfferEvent[]
  candidates: IceCandidateEvent[]
  sessionInfos: CastSessionInfo[]
  byeReasons: Array<string | undefined>
  disconnectedSids: string[]
  advanceClock: (ms: number) => void
  refreshSession: () => Promise<void>
}

let harness: Harness | null = null

/** A real SignalingServer + PairingServer with tight heartbeat settings. */
async function startHarness(): Promise<Harness> {
  let nowMs = FIXED_NOW_MS
  const offers: SdpOfferEvent[] = []
  const candidates: IceCandidateEvent[] = []
  const sessionInfos: CastSessionInfo[] = []
  const byeReasons: Array<string | undefined> = []
  const disconnectedSids: string[] = []

  const signaling = new SignalingServer({
    desktopName: DESKTOP_NAME,
    now: () => nowMs,
    heartbeatIntervalMs: HEARTBEAT_INTERVAL_MS,
    heartbeatTimeoutMs: HEARTBEAT_TIMEOUT_MS,
    onSdpOffer: (event) => offers.push(event),
    onIceCandidate: (event) => candidates.push(event),
    onSessionInfo: (info) => sessionInfos.push(info),
    onBye: (reason) => byeReasons.push(reason),
    onMobileDisconnected: ({ sid }) => disconnectedSids.push(sid)
  })
  const port = await signaling.start(0)
  const pairing = new PairingServer({ port, hosts: () => [...TEST_HOSTS], now: () => nowMs })
  signaling.attachPairing(pairing)
  await pairing.createSession()

  /** The phone's bootstrap: read the QR image and recover its payload v1. */
  const scanPayload = async (): Promise<QrPayloadV1> => {
    const view = await pairing.currentView()
    if (view === null) throw new Error('session creation failed')
    const png = PNG.sync.read(Buffer.from(view.qrDataUrl.slice('data:image/png;base64,'.length), 'base64'))
    const decoded = jsQR(new Uint8ClampedArray(png.data), png.width, png.height)
    if (decoded === null) throw new Error('test QR did not decode')
    return JSON.parse(decoded.data) as QrPayloadV1
  }

  let payload = await scanPayload()
  const harness: Harness = {
    port,
    payload,
    secret: Buffer.from(payload.k, 'base64url'),
    pairing,
    signaling,
    offers,
    candidates,
    sessionInfos,
    byeReasons,
    disconnectedSids,
    advanceClock: (ms: number) => {
      nowMs += ms
    },
    refreshSession: async () => {
      await pairing.createSession()
      payload = await scanPayload()
      harness.payload = payload
      harness.secret = Buffer.from(payload.k, 'base64url')
    }
  }
  return harness
}

/**
 * The scripted mobile endpoint: connects, handshakes with the real HMAC, and
 * collects every envelope the desktop sends. `autoPong` answers pings like the
 * real client would; with it off, the phone simulates a dead peer.
 */
class PhoneClient {
  private socket!: WebSocket
  private seq = 0
  private readonly replies: Envelope[] = []
  closed!: Promise<number>

  constructor(
    private readonly port: number,
    private readonly sid: string,
    private readonly secret: Buffer,
    private readonly autoPong: boolean,
    private readonly ua: string = UA
  ) {}

  connect(): Promise<this> {
    this.socket = new WebSocket(`ws://127.0.0.1:${this.port}/zfc/v1`)
    this.closed = new Promise((resolve) => {
      this.socket.on('close', (code) => resolve(code))
    })
    this.socket.on('message', (data) => {
      const envelope = JSON.parse(data.toString()) as Envelope
      this.replies.push(envelope)
      if (this.autoPong && envelope.type === 'ping') {
        this.send('pong', { t: (envelope.payload as { t: number }).t })
      }
    })
    return new Promise((resolve, reject) => {
      this.socket.once('open', () => resolve(this))
      this.socket.once('error', reject)
    })
  }

  send(type: string, payload: Record<string, unknown>): void {
    this.seq += 1
    this.socket.send(encodeEnvelope(type as never, this.seq, this.sid, payload as never))
  }

  /** hello → challenge → auth (real HMAC); returns the reply to the auth. */
  async handshake(protoMin = 1, protoMax = 1): Promise<Envelope> {
    this.send('hello', { ua: this.ua, protoMin, protoMax })
    const challenge = await this.nextReply(1_000)
    if (challenge.type !== 'challenge') return challenge
    const mac = computeMac(this.secret, this.sid, (challenge.payload as { n: string }).n)
    this.send('auth', { mac })
    return this.nextReply(1_000)
  }

  /**
   * Waits for the next non-heartbeat reply: the server pings every
   * HEARTBEAT_INTERVAL_MS, so `ping` frames are liveness noise, never answers.
   */
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

  waitClosed(timeoutMs = 2_000): Promise<number> {
    return Promise.race([
      this.closed,
      new Promise<number>((_, reject) => setTimeout(() => reject(new Error('socket did not close')), timeoutMs))
    ])
  }

  /** Asserts no close event lands within `timeoutMs`. */
  async expectStillOpen(timeoutMs = 300): Promise<void> {
    await Promise.race([
      this.closed.then((code) => {
        throw new Error(`socket closed unexpectedly: ${code}`)
      }),
      new Promise<void>((resolve) => setTimeout(resolve, timeoutMs))
    ])
  }

  /** Count of queued (undrained) replies of `type` — evidence of e.g. heartbeat pings. */
  countReplies(type: string): number {
    return this.replies.filter((reply) => reply.type === type).length
  }

  close(): void {
    this.socket.close(1000)
  }
}

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

/** New authenticated phone against the harness session. */
let activePhone: PhoneClient | null = null

async function authorizedPhone(h: Harness, autoPong: boolean): Promise<PhoneClient> {
  const phone = await new PhoneClient(h.port, h.payload.s, h.secret, autoPong).connect()
  activePhone = phone
  const reply = await phone.handshake()
  if (reply.type !== 'auth-ok') {
    throw new Error(`handshake failed: ${JSON.stringify(reply.payload)}`)
  }
  return phone
}

/**
 * Close any phone a failed assertion leaked — otherwise the session stays
 * authorized and every later test's handshake gets `busy`.
 */
afterEach(async () => {
  if (activePhone !== null) {
    activePhone.close()
    await sleep(150) // let the server process the close and release the session
    activePhone = null
  }
})

beforeAll(async () => {
  harness = await startHarness()
})

afterAll(() => {
  harness?.signaling.stop()
})

describe('signaling loopback: full message set (webrtc.md, Phase4)', () => {
  it('relays an sdp-offer and delivers an sdp-answer back (recorded blobs, no media)', async () => {
    const h = harness as Harness
    const phone = await authorizedPhone(h, true)

    phone.send('sdp-offer', { pc: 'media', sdp: RECORDED_SDP_OFFER })
    await sleep(100)
    expect(h.offers).toEqual([{ pc: 'media', sdp: RECORDED_SDP_OFFER }])

    h.signaling.sendSdpAnswer('media', RECORDED_SDP_ANSWER)
    const answer = await phone.nextReply(1_000)
    expect(answer.type).toBe('sdp-answer')
    expect(answer.payload).toEqual({ pc: 'media', sdp: RECORDED_SDP_ANSWER })
    phone.close()
  })

  it('passes ICE candidates through verbatim, including mDNS-obfuscated hosts and end-of-gathering', async () => {
    const h = harness as Harness
    const phone = await authorizedPhone(h, true)

    phone.send('ice', { pc: 'media', candidate: RECORDED_MDNS_CANDIDATE })
    phone.send('ice', { pc: 'media', candidate: RECORDED_HOST_CANDIDATE })
    phone.send('ice', { pc: 'media', candidate: null })
    await sleep(100)
    // Risk R4 plumbing evidence: the *.local candidate is untouched.
    expect(h.candidates).toEqual([
      { pc: 'media', candidate: RECORDED_MDNS_CANDIDATE },
      { pc: 'media', candidate: RECORDED_HOST_CANDIDATE },
      { pc: 'media', candidate: null }
    ])

    h.signaling.sendIceCandidate('media', RECORDED_HOST_CANDIDATE)
    const ice = await phone.nextReply(1_000)
    expect(ice.type).toBe('ice')
    expect(ice.payload).toEqual({ pc: 'media', candidate: RECORDED_HOST_CANDIDATE })
    phone.close()
  })

  it('echoes ping with pong carrying the same t', async () => {
    const h = harness as Harness
    const phone = await authorizedPhone(h, true)

    phone.send('ping', { t: 12345 })
    const pong = await phone.nextReply(1_000)
    expect(pong.type).toBe('pong')
    expect(pong.payload).toEqual({ t: 12345 })
    phone.close()
  })

  it('forwards session-info (display-only) and rejects a malformed one with bad-message', async () => {
    const h = harness as Harness
    const phone = await authorizedPhone(h, true)

    phone.send('session-info', { ...RECORDED_SESSION_INFO })
    await sleep(100)
    expect(h.sessionInfos).toEqual([RECORDED_SESSION_INFO])

    phone.send('session-info', { profile: 'balanced', width: 'many' })
    const error = await phone.nextReply(1_000)
    expect(error.type).toBe('error')
    expect((error.payload as { code: string }).code).toBe('bad-message')

    // Recoverable: the connection still carries media messages afterwards.
    phone.send('sdp-offer', { pc: 'mic', sdp: RECORDED_SDP_OFFER })
    await sleep(100)
    expect(h.offers).toContainEqual({ pc: 'mic', sdp: RECORDED_SDP_OFFER })
    phone.close()
  })

  it('rejects a malformed sdp-offer with bad-message but keeps the socket open', async () => {
    const h = harness as Harness
    const phone = await authorizedPhone(h, true)

    phone.send('sdp-offer', { pc: 'television', sdp: RECORDED_SDP_OFFER })
    const error = await phone.nextReply(1_000)
    expect(error.type).toBe('error')
    expect((error.payload as { code: string }).code).toBe('bad-message')

    // Still connected — a later well-formed offer goes through.
    phone.send('sdp-offer', { pc: 'media', sdp: RECORDED_SDP_OFFER })
    await sleep(100)
    expect(h.offers).toContainEqual({ pc: 'media', sdp: RECORDED_SDP_OFFER })
    phone.close()
  })
})

describe('signaling loopback: heartbeat (webrtc.md ping/pong)', () => {
  it('pings the authorized mobile and keeps the connection while it answers', async () => {
    const h = harness as Harness
    const phone = await authorizedPhone(h, true)

    // Several heartbeat sweeps across a live (advancing) clock, phone ponging.
    for (let i = 0; i < 6; i++) {
      await sleep(HEARTBEAT_INTERVAL_MS + 10)
      h.advanceClock(HEARTBEAT_INTERVAL_MS)
    }
    expect(phone.countReplies('ping')).toBeGreaterThan(0)
    await phone.expectStillOpen()
    phone.close()
  })

  it('closes a silent mobile after the heartbeat timeout and reports the disconnect', async () => {
    const h = harness as Harness
    const phone = await authorizedPhone(h, false) // never pongs

    const sidsBefore = h.disconnectedSids.length
    h.advanceClock(HEARTBEAT_TIMEOUT_MS + 1)
    const code = await phone.waitClosed()
    expect(code).toBe(1000)
    // The desktop treated the silence as a drop: reconnect window opens. The
    // server's close handler runs after the client sees the close frame — poll.
    await new Promise<void>((resolve, reject) => {
      const started = Date.now()
      const poll = (): void => {
        if (h.disconnectedSids.length === sidsBefore + 1) resolve()
        else if (Date.now() - started > 1_000) reject(new Error('disconnect was not reported'))
        else setTimeout(poll, 10)
      }
      poll()
    })
  })
})

describe('signaling loopback: bye from either side (webrtc.md)', () => {
  it('honours a mobile bye with a reason: session invalidated, onBye carries the reason', async () => {
    const h = harness as Harness
    const phone = await authorizedPhone(h, true)

    phone.send('bye', { reason: 'user-ended' })
    await phone.waitClosed()

    expect(h.byeReasons).toContain('user-ended')
    expect(h.pairing.lookup(h.payload.s)).toBeNull()
  })

  it('sends a desktop-initiated bye: mobile receives it, session invalidated, socket closed', async () => {
    const h = harness as Harness
    // The previous test's bye invalidated the session — mirror the coordinator:
    // after bye the desktop shows a fresh QR.
    await h.refreshSession()
    const phone = await authorizedPhone(h, true)

    h.signaling.sendBye('window-closed')
    const bye = await phone.nextReply(1_000)
    expect(bye.type).toBe('bye')
    expect(bye.payload).toEqual({ reason: 'window-closed' })
    await phone.waitClosed()

    expect(h.byeReasons).toContain('window-closed')
    expect(h.pairing.lookup(h.payload.s)).toBeNull()
  })
})

describe('signaling loopback: reconnect within TTL, expiry past it (webrtc.md)', () => {
  beforeAll(async () => {
    // The bye tests invalidated the session; reconnect happens against a fresh one.
    await (harness as Harness).refreshSession()
  })

  it('re-authenticates the same sid after a drop and carries media messages again', async () => {
    const h = harness as Harness
    const first = await authorizedPhone(h, true)
    first.close()
    await sleep(100) // let the close event land

    // Reconnect window: same sid, fresh handshake, media flows again.
    const second = await authorizedPhone(h, true)
    const offersBefore = h.offers.length
    second.send('sdp-offer', { pc: 'media', sdp: RECORDED_SDP_OFFER })
    await sleep(100)
    expect(h.offers.length).toBe(offersBefore + 1)
    second.close()
    await sleep(100)
  })

  it('refuses re-authentication once the session has expired', async () => {
    const h = harness as Harness
    // Jump past the QR expiry `e` — the desktop clock is authoritative.
    h.advanceClock((SESSION_TTL_SECONDS + 5) * 1000)
    const late = await new PhoneClient(h.port, h.payload.s, h.secret, true).connect()

    const reply = await late.handshake()
    expect(reply.type).toBe('error')
    expect((reply.payload as { code: string }).code).toBe('expired')
    await late.waitClosed()
  })
})
