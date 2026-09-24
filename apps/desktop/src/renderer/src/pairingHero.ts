// The pairing stage's view model (Phase13) — pure, so the waiting/error
// screens the user sees are unit-testable without a DOM. These strings
// are the product's UI copy; technical detail stays in the main-process logs
// (AGENTS.md: simple user-facing errors).
//
// Phase15: the paired state no longer renders here — while a device is
// paired (session alive), the receiver shows the "No input video" stage
// (noInputView.ts) instead; this hero is only for a session that is gone or
// never existed (QR waiting / creation error).

export type HeroIcon = 'qr' | 'warning'

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
const REGENERATE_LABEL = 'New QR code'
const TRY_AGAIN_LABEL = 'Try again'

/**
 * Maps the un-paired pairing states onto what the waiting screen shows:
 * waiting shows the QR as the hero with "Scan with your Android device"; a
 * session-creation error shows the friendly retry. A paired device renders
 * the receiver's no-input stage instead (noInputView — Phase15).
 */
export function heroView(sessionError: boolean): HeroView {
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
