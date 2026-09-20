import { describe, expect, it } from 'vitest'
import { PNG } from 'pngjs'
import jsQR from 'jsqr'
import { buildStaticQrPayload, encodeQrPayload, buildStaticSession } from './staticSession'
import type { QrPayloadV1 } from '../../shared/types'

const BASE64URL = /^[A-Za-z0-9_-]+$/

describe('static QR payload (pairing.md v1)', () => {
  it('is spec-shaped: v=1, t=zfc, host list, port, base64url secrets, expiry', () => {
    const payload: QrPayloadV1 = buildStaticQrPayload()

    expect(payload.v).toBe(1)
    expect(payload.t).toBe('zfc')
    expect(payload.h.length).toBeGreaterThan(0)
    expect(payload.p).toBe(52341)
    // 16 bytes base64url →22 chars; 32 bytes →43 chars.
    expect(payload.s).toMatch(BASE64URL)
    expect(payload.s).toHaveLength(22)
    expect(payload.k).toMatch(BASE64URL)
    expect(payload.k).toHaveLength(43)
    expect(payload.e).toBeGreaterThan(0)
  })

  it('encodes as compact JSON of the documented size class', () => {
    const encoded = encodeQrPayload(buildStaticQrPayload())
    expect(encoded).not.toContain(' ')

    const parsed = JSON.parse(encoded) as QrPayloadV1
    expect(parsed).toEqual(buildStaticQrPayload())
  })
})

describe('QR round-trip', () => {
  it('renders a QR that decodes back to the exact payload', async () => {
    const { qrDataUrl } = await buildStaticSession()
    expect(qrDataUrl).toMatch(/^data:image\/png;base64,/)

    const pngBase64 = qrDataUrl.slice('data:image/png;base64,'.length)
    const png = PNG.sync.read(Buffer.from(pngBase64, 'base64'))
    const decoded = jsQR(new Uint8ClampedArray(png.data), png.width, png.height)
    expect(decoded).not.toBeNull()
    expect(decoded?.data).toBe(encodeQrPayload(buildStaticQrPayload()))
  })
})
