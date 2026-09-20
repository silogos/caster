import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto'
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
