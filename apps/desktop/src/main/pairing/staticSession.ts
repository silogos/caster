import type { PairingSessionView, QrPayloadV1 } from '../../shared/types'
import { toQrDataUrl } from './qr'

/**
 * Phase 2 stand-in for the PairingServer (which arrives in Phase 3).
 * Emits a *static, spec-shaped* QR payload v1 (docs/architecture/pairing.md)
 * so the QR rendering path is exactly what the real pairing session will use;
 * only the payload source changes in Phase 3.
 *
 * The hosts/port/secrets below are fixed test values — nothing listens on them.
 */

// Values mirror the example in docs/architecture/pairing.md.
const TEST_HOSTS = ['192.168.1.42', '192.168.137.1']
const SIGNALING_PORT_DEFAULT = 52341
//16 random bytes, base64url — generated once, fixed test value.
const STATIC_SESSION_ID = 'Jm1LIOO4mWm0lRSSl2fClw'
//32 random bytes, base64url — generated once, fixed test value.
const STATIC_PAIRING_SECRET = '8lbEZ9jLGeoNaE1YmuEJJGtF6R01gbXnBILvz4e_UYQ'
// Fixed expiry (matches the pairing.md example; deliberately in the past — test data).
const STATIC_EXPIRY_UNIX_SECONDS = 1758300000

export function buildStaticQrPayload(): QrPayloadV1 {
  return {
    v: 1,
    t: 'zfc',
    h: TEST_HOSTS,
    p: SIGNALING_PORT_DEFAULT,
    s: STATIC_SESSION_ID,
    k: STATIC_PAIRING_SECRET,
    e: STATIC_EXPIRY_UNIX_SECONDS
  }
}

/** Compact JSON — no whitespace, key order as in the spec example. */
export function encodeQrPayload(payload: QrPayloadV1): string {
  const { v, t, h, p, s, k, e } = payload
  return JSON.stringify({ v, t, h, p, s, k, e })
}

export async function buildStaticSession(): Promise<PairingSessionView> {
  const qrDataUrl = await toQrDataUrl(encodeQrPayload(buildStaticQrPayload()))
  return { qrDataUrl, expiresAt: STATIC_EXPIRY_UNIX_SECONDS }
}
