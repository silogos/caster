import { describe, expect, it } from 'vitest'
import { heroView } from './pairingHero'

// What the user sees on the un-paired pairing stage (Phase13/15): the QR hero
// with "Scan with your Android device" and the friendly session-creation
// error — all failure-mode copy non-technical (AGENTS.md). The paired state
// renders the receiver's no-input stage instead (noInputView.test.ts).
describe('heroView', () => {
  it('waiting: the QR is the hero with the scan heading and validity hint', () => {
    const view = heroView(false)
    expect(view.icon).toBe('qr')
    expect(view.showQr).toBe(true)
    expect(view.heading).toBe('Scan with your Android device')
    expect(view.subline).toBe('Waiting for mobile device…')
    expect(view.showHint).toBe(true)
    expect(view.showRegenerate).toBe(true)
    expect(view.regenerateLabel).toBe('New QR code')
  })

  it('session error: friendly message, no QR, and a retry button', () => {
    const view = heroView(true)
    expect(view.icon).toBe('warning')
    expect(view.showQr).toBe(false)
    expect(view.heading).toBe("Couldn't create a pairing session")
    expect(view.subline).toBe('Make sure this computer is connected to your Wi-Fi network, then try again.')
    expect(view.showHint).toBe(false)
    expect(view.showRegenerate).toBe(true)
    expect(view.regenerateLabel).toBe('Try again')
  })
})
