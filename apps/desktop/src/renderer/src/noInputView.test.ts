import { describe, expect, it } from 'vitest'
import { noInputView } from './noInputView'

// The receiver's "no signal" copy (Phase15): paired + no video must never
// look like a lost pairing — the QR hero is only for a dead session.
describe('noInputView', () => {
  it('paired with the socket alive: connected copy that hands the stage to the phone', () => {
    const view = noInputView('TB321FU', false)
    expect(view.heading).toBe('No input video')
    expect(view.subline).toBe('Connected to TB321FU — start casting from your phone.')
  })

  it('paired inside the reconnect window: keeps the device name, never the QR', () => {
    const view = noInputView('TB321FU', true)
    expect(view.heading).toBe('No input video')
    expect(view.subline).toBe('Waiting for TB321FU to reconnect…')
  })
})
