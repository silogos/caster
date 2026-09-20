// Message envelope for the signaling WebSocket — docs/architecture/webrtc.md.
// Every frame is JSON text: {v, type, seq, sid, payload}. The full Phase4
// message set: pairing (hello/challenge/auth/auth-ok), media plumbing
// (sdp-offer/sdp-answer/ice/session-info), and lifecycle (ping/pong/bye/error).

export const ENVELOPE_VERSION = 1
/** Max envelope size per webrtc.md. */
export const ENVELOPE_MAX_BYTES = 256 * 1024

export type EnvelopeType =
  | 'hello'
  | 'challenge'
  | 'auth'
  | 'auth-ok'
  | 'error'
  | 'bye'
  | 'sdp-offer'
  | 'sdp-answer'
  | 'ice'
  | 'session-info'
  | 'ping'
  | 'pong'

/** PeerConnection discriminator — the two PCs share one signaling channel (ADR-003). */
export type PcId = 'media' | 'mic'

/** Type guard for the `pc` discriminator (not part of the wire format). */
export function isPcId(value: unknown): value is PcId {
  return value === 'media' || value === 'mic'
}

export interface HelloPayload {
  /** App/platform string for logs, e.g. "ZeroFrictionCast/0.1.0 (Android15; Pixel8)". */
  ua: string
  protoMin: number
  protoMax: number
}
export interface ChallengePayload {
  /** 16 fresh random bytes, base64url. */
  n: string
}
export interface AuthPayload {
  /** base64url(HMAC-SHA256(k, ASCII(s) || ASCII(n))) — pairing.md. */
  mac: string
}
export interface AuthOkPayload {
  /** Desktop name, e.g. os.hostname() — mobile shows "Connected to …". */
  name: string
  /** Negotiated protocol version. */
  proto: number
}
export interface ErrorPayload {
  code: string
  msg?: string
}
export interface ByePayload {
  /** Free-form reason for logs; never user-facing (webrtc.md). */
  reason?: string
}
export interface SdpPayload {
  /** Which PeerConnection the blob belongs to. */
  pc: PcId
  /** Opaque SDP blob — signaling never parses it. */
  sdp: string
}
export interface IcePayload {
  pc: PcId
  /**
   * Platform candidate object, passed through verbatim (an RTCIceCandidateInit
   * on the desktop, its libwebrtc equivalent on mobile). `null` marks
   * end-of-gathering for this `pc` (webrtc.md). Signaling treats it as opaque.
   */
  candidate: unknown
}
export interface SessionInfoPayload {
  /** Display-only summary for the desktop status line — never acted on (webrtc.md). */
  profile: string
  width: number
  height: number
  fps: number
  gameAudio: boolean
  mic: boolean
}
export interface HeartbeatPayload {
  /** Sender's epoch milliseconds; echoed unchanged in the pong. */
  t: number
}

export type EnvelopePayload =
  | HelloPayload
  | ChallengePayload
  | AuthPayload
  | AuthOkPayload
  | ErrorPayload
  | ByePayload
  | SdpPayload
  | IcePayload
  | SessionInfoPayload
  | HeartbeatPayload

export interface Envelope<P extends EnvelopePayload = EnvelopePayload> {
  v: typeof ENVELOPE_VERSION
  type: EnvelopeType
  /** Per-sender monotonic counter starting at 1; regressions are logged, not enforced. */
  seq: number
  /** Session ID from the QR; required on every message. */
  sid: string
  payload: P
}

export type ParseResult =
  | { ok: true; envelope: Envelope }
  | { ok: false; code: 'bad-version' | 'bad-message'; reason: string }

const ENVELOPE_TYPES: ReadonlySet<string> = new Set<EnvelopeType>([
  'hello',
  'challenge',
  'auth',
  'auth-ok',
  'error',
  'bye',
  'sdp-offer',
  'sdp-answer',
  'ice',
  'session-info',
  'ping',
  'pong'
])

/**
 * Parse one inbound frame. Envelope version mismatch is terminal (bad-version);
 * anything unrecognised is bad-message. The caller decides whether the
 * connection stays open (webrtc.md: unknown types are recoverable).
 */
export function parseEnvelope(raw: string): ParseResult {
  if (raw.length > ENVELOPE_MAX_BYTES) {
    return { ok: false, code: 'bad-message', reason: 'envelope exceeds 256 KiB' }
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { ok: false, code: 'bad-message', reason: 'invalid JSON' }
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return { ok: false, code: 'bad-message', reason: 'not an object' }
  }
  const candidate = parsed as Record<string, unknown>
  if (candidate.v !== ENVELOPE_VERSION) {
    return { ok: false, code: 'bad-version', reason: `envelope v ${String(candidate.v)}` }
  }
  if (typeof candidate.type !== 'string' || !ENVELOPE_TYPES.has(candidate.type)) {
    return { ok: false, code: 'bad-message', reason: `unknown type ${String(candidate.type)}` }
  }
  if (typeof candidate.seq !== 'number' || !Number.isInteger(candidate.seq) || candidate.seq < 1) {
    return { ok: false, code: 'bad-message', reason: `bad seq ${String(candidate.seq)}` }
  }
  if (typeof candidate.sid !== 'string' || candidate.sid.length === 0) {
    return { ok: false, code: 'bad-message', reason: 'missing sid' }
  }
  if (typeof candidate.payload !== 'object' || candidate.payload === null) {
    return { ok: false, code: 'bad-message', reason: 'missing payload' }
  }
  return { ok: true, envelope: candidate as unknown as Envelope }
}

/** Serialize an outbound frame. */
export function encodeEnvelope(type: EnvelopeType, seq: number, sid: string, payload: EnvelopePayload): string {
  return JSON.stringify({ v: ENVELOPE_VERSION, type, seq, sid, payload })
}
