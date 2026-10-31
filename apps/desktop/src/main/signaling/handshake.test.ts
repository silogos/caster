import { describe, expect, it } from 'vitest'
import { createNonce, computeMac, deriveTrustKey, negotiateProtocol, verifyMac, NONCE_BYTES } from './handshake'
import type { HelloPayload } from './envelope'

// Cross-platform interop vector: the identical values and expected mac are
// asserted in the Android unit test (apps/mobile …/signaling/HandshakeTest.kt),
// proving both HMAC implementations agree byte-for-byte.
const VECTOR_SECRET_B64URL = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8'
const VECTOR_SID = 'Jm1LIOO4mWm0lRSSl2fClw'
const VECTOR_NONCE = 'EBESExQVFhcYGRobHB0eHw'
const VECTOR_MAC = 'cPTgkuJCjSp2z9MZyxVs-QtHs8fCYrMN7s-k0YfyXZQ'

// Cross-platform HKDF vector for the persistent trust key (ADR-006): the same
// inputs and expected trustKey are mirrored in the mobile's HandshakeTest —
// both derivations must agree or a saved session could never resume.
const TRUST_VECTOR_SECRET_B64URL = 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY'
const TRUST_VECTOR_SID = 'sess'
const TRUST_VECTOR_KEY_B64URL = 'Hng10A_e7L4UF8QKc5Tf_Lv-Ut2wijkNvFFuqWaKfhU'
const TRUST_VECTOR2_SECRET_HEX = '9f2c7be14ad83a05c6f1b29e4d7a83c0b5e1962d483fc7abe1d05f92c38b7a41'
const TRUST_VECTOR2_SID = 'c2Vzc2lvbi1pZA'
const TRUST_VECTOR2_KEY_B64URL = 'GsBUjq7GFfivYpQyGAa4pYNgbBkFiFDfjK8KpCFRoF4'

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

  it('matches the cross-platform HKDF trust-key vectors (ADR-006)', () => {
    expect(deriveTrustKey(Buffer.from(TRUST_VECTOR_SECRET_B64URL, 'base64url'), TRUST_VECTOR_SID).toString('base64url'))
      .toBe(TRUST_VECTOR_KEY_B64URL)
    expect(deriveTrustKey(Buffer.from(TRUST_VECTOR2_SECRET_HEX, 'hex'), TRUST_VECTOR2_SID).toString('base64url'))
      .toBe(TRUST_VECTOR2_KEY_B64URL)
  })

  it('derives 32 bytes that depend on both the secret and the session id', () => {
    const secret = Buffer.from(TRUST_VECTOR_SECRET_B64URL, 'base64url')
    const key = deriveTrustKey(secret, TRUST_VECTOR_SID)
    expect(key).toHaveLength(32)
    expect(deriveTrustKey(secret, 'other-sid').equals(key)).toBe(false)
    expect(deriveTrustKey(Buffer.alloc(32, 7), TRUST_VECTOR_SID).equals(key)).toBe(false)
  })

  it('negotiates the highest mutually supported protocol version', () => {
    const hello = (protoMin: number, protoMax: number): HelloPayload => ({ ua: 'test', protoMin, protoMax })
    // Desktop speaks 1..2 (proto 2 = the vc-* messages, ADR-007).
    expect(negotiateProtocol(hello(1, 1))).toBe(1)
    expect(negotiateProtocol(hello(1, 9))).toBe(2)
    expect(negotiateProtocol(hello(1, 2))).toBe(2)
    expect(negotiateProtocol(hello(2, 9))).toBe(2)
    expect(negotiateProtocol(hello(3, 9))).toBeNull()
    expect(negotiateProtocol(hello(0, 0))).toBeNull()
  })
})
