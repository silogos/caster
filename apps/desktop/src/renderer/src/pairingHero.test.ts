import { describe, expect, it } from 'vitest'
import { heroView } from './pairingHero'

// What the user sees per pairing state (Phase13): the QR hero with "Scan
// with your Android device", the paired check card, and the friendly
// session-creation error — all failure-mode copy non-technical (AGENTS.md).
describe('heroView', () => {
  it('waiting: the QR is the hero with the scan heading and validity hint', () => {
    const view = heroView(null, false)
    expect(view.icon).toBe('qr')
    expect(view.showQr).toBe(true)
    expect(view.heading).toBe('Scan with your Android device')
    expect(view.subline).toBe('Waiting for mobile device…')
    expect(view.showHint).toBe(true)
    expect(view.showRegenerate).toBe(true)
    expect(view.regenerateLabel).toBe('New QR code')
  })

  it('paired: shows the check card with the phone name and hands the stage to the phone', () => {
    const view = heroView('Pixel 8', false)
    expect(view.icon).toBe('check')
    expect(view.showQr).toBe(false)
    expect(view.heading).toBe('Connected to Pixel 8')
    expect(view.subline).toBe('Start casting from your phone.')
    expect(view.showHint).toBe(false)
    // Regenerating while paired would kill the live session.
    expect(view.showRegenerate).toBe(false)
  })

  it('session error: friendly message, no QR, and a retry button', () => {
    const view = heroView(null, true)
    expect(view.icon).toBe('warning')
    expect(view.showQr).toBe(false)
    expect(view.heading).toBe("Couldn't create a pairing session")
    expect(view.subline).toBe('Make sure this computer is connected to your Wi-Fi network, then try again.')
    expect(view.showHint).toBe(false)
    expect(view.showRegenerate).toBe(true)
    expect(view.regenerateLabel).toBe('Try again')
  })
})
