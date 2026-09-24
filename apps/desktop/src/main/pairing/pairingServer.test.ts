import { describe, expect, it } from 'vitest'
import { PNG } from 'pngjs'
import jsQR from 'jsqr'
import { PairingServer, SESSION_TTL_SECONDS, encodeQrPayload, NO_LAN_INTERFACE_MESSAGE } from './pairingServer'
import type { QrPayloadV1 } from '../../shared/types'

const BASE64URL = /^[A-Za-z0-9_-]+$/
const TEST_HOSTS = ['192.168.1.42', '192.168.137.1']
const TEST_PORT = 52341
const FIXED_NOW_MS = 1_800_000_000_000

function createTestServer(
  overrides: { now?: () => number; hosts?: () => string[]; hasLiveAuthorizedSocket?: () => boolean } = {}
): PairingServer {
  return new PairingServer({
    port: TEST_PORT,
    hosts: overrides.hosts ?? (() => [...TEST_HOSTS]),
    now: overrides.now ?? (() => FIXED_NOW_MS),
    hasLiveAuthorizedSocket: overrides.hasLiveAuthorizedSocket
  })
}

/** Decode the QR PNG the renderer receives — exactly what a scanning phone does. */
async function scanQrPayload(view: { qrDataUrl: string }): Promise<QrPayloadV1> {
  expect(view.qrDataUrl).toMatch(/^data:image\/png;base64,/)
  const png = PNG.sync.read(Buffer.from(view.qrDataUrl.slice('data:image/png;base64,'.length), 'base64'))
  const decoded = jsQR(new Uint8ClampedArray(png.data), png.width, png.height)
  expect(decoded).not.toBeNull()
  return JSON.parse(decoded!.data) as QrPayloadV1
}

describe('QR payload (pairing.md v1)', () => {
  it('is spec-shaped: v=1, t=zfc, host list, port, base64url secrets, fresh expiry', async () => {
    const server = createTestServer()
    const payload = await scanQrPayload(await server.createSession())

    expect(payload.v).toBe(1)
    expect(payload.t).toBe('zfc')
    expect(payload.h).toEqual(TEST_HOSTS)
    expect(payload.p).toBe(TEST_PORT)
    // 16 bytes base64url → 22 chars; 32 bytes → 43 chars.
    expect(payload.s).toMatch(BASE64URL)
    expect(payload.s).toHaveLength(22)
    expect(payload.k).toMatch(BASE64URL)
    expect(payload.k).toHaveLength(43)
    expect(payload.e).toBe(FIXED_NOW_MS / 1000 + SESSION_TTL_SECONDS)
  })

  it('generates a fresh random session id and secret every time', async () => {
    const server = createTestServer()
    const first = await scanQrPayload(await server.createSession())
    const second = await scanQrPayload(await server.createSession())

    expect(first.s).not.toBe(second.s)
    expect(first.k).not.toBe(second.k)
  })

  it('encodes as compact JSON with the documented key order', () => {
    const payload: QrPayloadV1 = {
      v: 1,
      t: 'zfc',
      h: TEST_HOSTS,
      p: TEST_PORT,
      s: 'Jm1LIOO4mWm0lRSSl2fClw',
      k: 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8',
      e: 1758300000
    }
    const encoded = encodeQrPayload(payload)
    expect(encoded).toBe(
      '{"v":1,"t":"zfc","h":["192.168.1.42","192.168.137.1"],"p":52341,' +
        '"s":"Jm1LIOO4mWm0lRSSl2fClw","k":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","e":1758300000}'
    )
    expect(JSON.parse(encoded)).toEqual(payload)
  })
})

describe('session lifecycle', () => {
  it('expires on the desktop clock and ensureFreshSession replaces the QR', async () => {
    let now = FIXED_NOW_MS
    const server = createTestServer({ now: () => now })
    const first = await server.createSession()

    const unchanged = await server.ensureFreshSession()
    expect(unchanged).toBe(false)
    expect((await server.currentView())?.expiresAt).toBe(first.expiresAt)

    now = FIXED_NOW_MS + (SESSION_TTL_SECONDS + 1) * 1000
    const changed = await server.ensureFreshSession()
    expect(changed).toBe(true)
    expect((await server.currentView())?.expiresAt).toBe(now / 1000 + SESSION_TTL_SECONDS)
  })

  it('authorizes one connection; a concurrent second one is busy; after release re-auth is allowed', async () => {
    const server = createTestServer()
    await server.createSession()
    const sid = server.currentSid as string

    expect(server.authorize(sid, 'sock-1')).toBe('authorized')
    expect(server.authorize(sid, 'sock-2')).toBe('busy')

    server.release('sock-1')
    expect(server.authorize(sid, 'sock-3')).toBe('authorized')
  })

  it('defers the expiry sweep while the authorized socket is live, regenerates once it drops (Phase15)', async () => {
    let now = FIXED_NOW_MS
    let live = false
    const server = createTestServer({ now: () => now, hasLiveAuthorizedSocket: () => live })
    const first = await server.createSession()
    server.authorize(server.currentSid as string, 'sock-1')

    now = FIXED_NOW_MS + (SESSION_TTL_SECONDS + 1) * 1000
    // Live cast at TTL: the session stays — no regeneration, no new sid.
    live = true
    expect(await server.ensureFreshSession()).toBe(false)
    expect((await server.currentView())?.expiresAt).toBe(first.expiresAt)
    expect(server.currentSid).not.toBeNull()

    // Socket gone: the already-expired session regenerates on the next sweep
    // — the reconnect window ends at expiry, as documented (rescan follows).
    live = false
    expect(await server.ensureFreshSession()).toBe(true)
    expect((await server.currentView())?.expiresAt).toBe(now / 1000 + SESSION_TTL_SECONDS)
  })

  it('an expired pending session still regenerates even with the liveness predicate set', async () => {
    let now = FIXED_NOW_MS
    const server = createTestServer({ now: () => now, hasLiveAuthorizedSocket: () => true })
    await server.createSession()

    now = FIXED_NOW_MS + (SESSION_TTL_SECONDS + 1) * 1000
    expect(await server.ensureFreshSession()).toBe(true)
  })

  it('bye invalidates the session: authorize is refused afterwards', async () => {
    const server = createTestServer()
    await server.createSession()
    const sid = server.currentSid as string

    expect(server.authorize(sid, 'sock-1')).toBe('authorized')
    server.invalidate()
    expect(server.authorize(sid, 'sock-2')).toBe('busy')
  })

  it('regenerating invalidates the previous session id', async () => {
    const server = createTestServer()
    await server.createSession()
    const oldSid = server.currentSid as string
    expect(server.lookup(oldSid)).not.toBeNull()

    await server.createSession()
    expect(server.lookup(oldSid)).toBeNull()
  })

  it('fails with no LAN IPv4 hosts and recovers once hosts exist', async () => {
    const views: Array<unknown> = []
    let hosts: string[] = []
    const server = new PairingServer({
      port: TEST_PORT,
      hosts: () => hosts,
      now: () => FIXED_NOW_MS,
      onSessionChanged: (view) => views.push(view)
    })

    await expect(server.createSession()).rejects.toThrow(NO_LAN_INTERFACE_MESSAGE)
    expect(views).toEqual([null])

    hosts = [...TEST_HOSTS]
    expect(await server.ensureFreshSession()).toBe(true)
    expect(views[views.length - 1]).toMatchObject({ expiresAt: FIXED_NOW_MS / 1000 + SESSION_TTL_SECONDS })
  })
})
