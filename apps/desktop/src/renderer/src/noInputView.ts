// The receiver's "no signal" stage (Phase15) — pure view copy, unit-testable
// without a DOM. Shown whenever the pairing session is alive (a device is
// paired; its socket may or may not be connected) but no video flows: the
// desktop behaves like a monitor without input instead of falling back to the
// QR hero. The QR returns only when the session itself is gone
// (bye/expiry/regenerate) — pairing.md's reconnect window means a missing
// video stream is never a lost pairing. Technical detail stays in logs
// (AGENTS.md: simple user-facing messages).

export interface NoInputView {
  heading: string
  subline: string
}

const NO_INPUT_HEADING = 'No input video'
const CONNECTED_SUBLINE = 'Connected to'
const START_CASTING_SUBLINE = 'start casting from your phone.'
const RECONNECT_SUBLINE = 'Waiting for'
const RECONNECT_SUBLINE_SUFFIX = 'to reconnect…'

/**
 * `reconnecting` is true while the paired device's socket is down but the
 * session's reconnect window is still open (MobileStateEvent 'disconnected').
 */
export function noInputView(name: string, reconnecting: boolean): NoInputView {
  if (reconnecting) {
    return { heading: NO_INPUT_HEADING, subline: `${RECONNECT_SUBLINE} ${name} ${RECONNECT_SUBLINE_SUFFIX}` }
  }
  return { heading: NO_INPUT_HEADING, subline: `${CONNECTED_SUBLINE} ${name} — ${START_CASTING_SUBLINE}` }
}
