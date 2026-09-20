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
 * PeerConnection discriminator (ADR-003) — the two PCs share one signaling
 * channel. Mirrors the `pc` field of the signaling envelope (webrtc.md).
 */
export type PcId = 'media' | 'mic'

/** Inbound SDP offer/answer content, forwarded verbatim from the socket. */
export interface SignalingSdpMessage {
  pc: PcId
  /** Opaque SDP blob — nobody between the socket and the RTCPeerConnection parses it. */
  sdp: string
}

/**
 * Inbound ICE candidate (platform object, opaque to everything in between);
 * `candidate: null` marks end-of-gathering for that pc (webrtc.md).
 */
export interface SignalingIceMessage {
  pc: PcId
  candidate: unknown
}

/**
 * What the main process hands the renderer for the pairing screen.
 * `qrDataUrl` is a data: URL so no custom protocol or file access is needed.
 */
export interface PairingSessionView {
  qrDataUrl: string
  /** Unix seconds — session lifetime; the desktop regenerates a fresh QR after it. */
  expiresAt: number
}

/**
 * Push event for the renderer: which mobile (if any) is paired right now.
 * `name` comes from the hello user-agent (docs/architecture/webrtc.md) so the
 * desktop can show "Connected to Pixel 8" — the desktop only *displays* it.
 */
export type MobileStateEvent =
  | { state: 'waiting' }
  | { state: 'connected'; name: string }
  | { state: 'session-info'; info: CastSessionInfo }

/**
 * Display-only summary pushed by the mobile's `session-info` message
 * (docs/architecture/webrtc.md) — shown on the status line, never acted on.
 */
export interface CastSessionInfo {
  profile: string
  width: number
  height: number
  fps: number
  gameAudio: boolean
  mic: boolean
}
