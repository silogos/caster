// Shared between main, preload, and renderer. Type-only — no runtime imports.

/**
 * QR pairing payload, schema v1 — see docs/architecture/pairing.md.
 * Field names are deliberately short to keep QR density low.
 */
export interface QrPayloadV1 {
  /** Payload schema version. */
  v:1
  /** Literal "zfc" (zero-friction cast). Guards against scanning unrelated QR codes. */
  t: 'zfc'
  /** All candidate IPv4 addresses of the desktop on non-loopback interfaces. */
  h: string[]
  /** WebSocket signaling port. */
  p: number
  /** Session ID — 16 random bytes, base64url. */
  s: string
  /** Pairing secret — 32 random bytes, base64url. */
  k: string
  /** Expiry — Unix seconds. */
  e: number
}

/**
 * What the main process hands the renderer for the pairing screen.
 * `qrDataUrl` is a data: URL so no custom protocol or file access is needed.
 */
export interface PairingSessionView {
  qrDataUrl: string
  /** Unix seconds — informational only in Phase 2 (static test data). */
  expiresAt: number
}
