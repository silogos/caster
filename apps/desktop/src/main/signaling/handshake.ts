import { createHmac, hkdfSync, randomBytes, timingSafeEqual } from 'node:crypto'
import type { HelloPayload } from './envelope'

// Handshake crypto per docs/architecture/pairing.md and ADR-002: the secret
// never crosses the wire; each handshake gets a fresh nonce bound to the session
// id so a captured mac cannot be replayed. Randomness uses the platform CSPRNG
// (crypto.randomBytes) and the desktop comparison is constant-time.

/** Fresh random bytes per handshake, base64url-encoded (pairing.md `n`). */
export const NONCE_BYTES = 16
/** Protocol versions this desktop understands (webrtc.md `proto`). */
export const DESKTOP_PROTO_MIN = 1
export const DESKTOP_PROTO_MAX = 1

/** 16 fresh random bytes, base64url — one per handshake. */
export function createNonce(): string {
  return randomBytes(NONCE_BYTES).toString('base64url')
}

/**
 * mac = base64url(HMAC-SHA256(key = k, msg = ASCII(s) || ASCII(n)))
 * `n` is the base64url nonce string as transmitted in the challenge.
 */
export function computeMac(secret: Buffer, sid: string, nonce: string): string {
  return createHmac('sha256', secret).update(sid, 'ascii').update(nonce, 'ascii').digest('base64url')
}

/** Constant-time comparison of the two base64url macs (ADR-002). */
export function verifyMac(expected: string, actual: string): boolean {
  const expectedBuf = Buffer.from(expected, 'ascii')
  const actualBuf = Buffer.from(actual, 'ascii')
  if (expectedBuf.length !== actualBuf.length) return false
  return timingSafeEqual(expectedBuf, actualBuf)
}

/**
 * Version negotiation: pick the highest protocol version both sides support.
 * Null when the ranges don't intersect → the caller answers `bad-version`.
 */
export function negotiateProtocol(hello: HelloPayload): number | null {
  const lo = Math.max(hello.protoMin, DESKTOP_PROTO_MIN)
  const hi = Math.min(hello.protoMax, DESKTOP_PROTO_MAX)
  return lo <= hi ? hi : null
}

/**
 * HKDF info string and output length for the persistent-trust key
 * (pairing.md §Persistent sessions, ADR-006): both sides derive the same
 * 32-byte `trustKey` from the QR pairing's own secret after a successful
 * handshake — it is never transmitted, and a passive sniffer cannot compute
 * it (the same property that protects the HMAC handshake itself).
 */
export const TRUST_INFO = 'zfc-trust-v1'
export const TRUST_KEY_BYTES = 32

/**
 * trustKey = HKDF-SHA256(ikm = k, salt = ASCII(s), info = "zfc-trust-v1", L = 32).
 * Both endpoints compute this locally at auth-ok time; re-pairing (a fresh QR
 * session with a fresh `k`) rotates the key, evicting any trust that was
 * derived from a photographed older QR.
 */
export function deriveTrustKey(secret: Buffer, sid: string): Buffer {
  return Buffer.from(hkdfSync('sha256', secret, Buffer.from(sid, 'ascii'), TRUST_INFO, TRUST_KEY_BYTES))
}
