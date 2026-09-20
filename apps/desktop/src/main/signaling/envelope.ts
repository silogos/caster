// Message envelope for the signaling WebSocket — docs/architecture/webrtc.md.
// Every frame is JSON text: {v, type, seq, sid, payload}. Phase3 implements
// only the pairing-related types; Phase4 adds sdp-*/ice/ping/pong/session-info.

export const ENVELOPE_VERSION = 1
/** Max envelope size per webrtc.md. */
export const ENVELOPE_MAX_BYTES = 256 * 1024

export type EnvelopeType = 'hello' | 'challenge' | 'auth' | 'auth-ok' | 'error' | 'bye'

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
export interface ByePayload {}

export type EnvelopePayload = HelloPayload | ChallengePayload | AuthPayload | AuthOkPayload | ErrorPayload | ByePayload

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

function isEnvelopeType(value: unknown): value is EnvelopeType {
  return value === 'hello' || value === 'challenge' || value === 'auth' || value === 'auth-ok' || value === 'error' || value === 'bye'
}

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
  if (!isEnvelopeType(candidate.type)) {
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
