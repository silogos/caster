// The pairing stage's view model (Phase13) — pure, so the waiting/connected/
// error screens the user sees are unit-testable without a DOM. These strings
// are the product's UI copy; technical detail stays in the main-process logs
// (AGENTS.md: simple user-facing errors).

export type HeroIcon = 'qr' | 'check' | 'warning'

export interface HeroView {
  icon: HeroIcon
  heading: string
  subline: string
  showQr: boolean
  showHint: boolean
  showRegenerate: boolean
  regenerateLabel: string
}

// The QR validity hint is static copy in index.html ("Scan with your Android
// device — valid 10 minutes, refreshes automatically" region).
const SESSION_ERROR_HEADING = "Couldn't create a pairing session"
const SESSION_ERROR_SUBLINE = 'Make sure this computer is connected to your Wi-Fi network, then try again.'
const CONNECTED_SUBLINE = 'Start casting from your phone.'
const REGENERATE_LABEL = 'New QR code'
const TRY_AGAIN_LABEL = 'Try again'

/**
 * Maps the pairing state (a live paired phone, or a session-creation error)
 * onto what the waiting screen shows. Waiting shows the QR as the hero with
 * "Scan with your Android device"; paired shows a check and hands the stage
 * to the phone (the desktop has no cast controls — overview.md).
 */
export function heroView(connectedName: string | null, sessionError: boolean): HeroView {
  if (sessionError) {
    return {
      icon: 'warning',
      heading: SESSION_ERROR_HEADING,
      subline: SESSION_ERROR_SUBLINE,
      showQr: false,
      showHint: false,
      showRegenerate: true,
      regenerateLabel: TRY_AGAIN_LABEL
    }
  }
  if (connectedName !== null) {
    return {
      icon: 'check',
      heading: `Connected to ${connectedName}`,
      subline: CONNECTED_SUBLINE,
      showQr: false,
      showHint: false,
      // Regenerating while paired would invalidate the live session
      // (pairing.md: one session at a time) — the control returns once the
      // phone is gone.
      showRegenerate: false,
      regenerateLabel: REGENERATE_LABEL
    }
  }
  return {
    icon: 'qr',
    heading: 'Scan with your Android device',
    subline: 'Waiting for mobile device…',
    showQr: true,
    showHint: true,
    showRegenerate: true,
    regenerateLabel: REGENERATE_LABEL
  }
}
