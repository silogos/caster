import { describe, expect, it } from 'vitest'
import { createNonce, computeMac, verifyMac, negotiateProtocol, NONCE_BYTES } from './handshake'
import type { HelloPayload } from './envelope'

// Cross-platform interop vector: the identical values and expected mac are
// asserted in the Android unit test (apps/mobile …/signaling/HandshakeTest.kt),
// proving both HMAC implementations agree byte-for-byte.
const VECTOR_SECRET_B64URL = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8'
const VECTOR_SID = 'Jm1LIOO4mWm0lRSSl2fClw'
const VECTOR_NONCE = 'EBESExQVFhcYGRobHB0eHw'
const VECTOR_MAC = 'cPTgkuJCjSp2z9MZyxVs-QtHs8fCYrMN7s-k0YfyXZQ'

describe('handshake crypto (pairing.md / ADR-002)', () => {
  it('matches the cross-platform HMAC-SHA256 vector', () => {
    const secret = Buffer.from(VECTOR_SECRET_B64URL, 'base64url')
    const mac = computeMac(secret, VECTOR_SID, VECTOR_NONCE)
    expect(mac).toBe(VECTOR_MAC)
    expect(verifyMac(VECTOR_MAC, mac)).toBe(true)
  })

  it('rejects a wrong mac, a wrong nonce and a wrong secret', () => {
    const secret = Buffer.from(VECTOR_SECRET_B64URL, 'base64url')
    const good = computeMac(secret, VECTOR_SID, VECTOR_NONCE)

    expect(verifyMac(good, computeMac(secret, VECTOR_SID, 'different-nonce'))).toBe(false)
    expect(verifyMac(good, computeMac(Buffer.from('x'.repeat(32), 'ascii'), VECTOR_SID, VECTOR_NONCE))).toBe(false)
    expect(verifyMac(good, good.slice(0, 42))).toBe(false)
  })

  it('issues a fresh base64url 16-byte nonce per call', () => {
    const first = createNonce()
    const second = createNonce()
    expect(Buffer.from(first, 'base64url')).toHaveLength(NONCE_BYTES)
    expect(first).toMatch(/^[A-Za-z0-9_-]+$/)
    expect(first).not.toBe(second)
  })

  it('negotiates the highest mutually supported protocol version', () => {
    const hello = (protoMin: number, protoMax: number): HelloPayload => ({ ua: 'test', protoMin, protoMax })
    expect(negotiateProtocol(hello(1, 1))).toBe(1)
    expect(negotiateProtocol(hello(1, 9))).toBe(1)
    expect(negotiateProtocol(hello(2, 9))).toBeNull()
    expect(negotiateProtocol(hello(0, 0))).toBeNull()
  })
})
