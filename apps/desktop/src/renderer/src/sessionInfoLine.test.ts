import { describe, expect, it } from 'vitest'
import type { CastSessionInfo } from '../../shared/types'
import { sessionInfoLine } from './sessionInfoLine'

const info = (overrides: Partial<CastSessionInfo> = {}): CastSessionInfo => ({
  profile: 'balanced',
  width: 1280,
  height: 720,
  fps: 30,
  gameAudio: true,
  mic: true,
  ...overrides
})

// The line is product copy shown in two windows — the behavior under test is
// exactly what the user reads (pairingHero tests, same philosophy).
describe('sessionInfoLine', () => {
  it('is empty while no phone is paired', () => {
    expect(sessionInfoLine(null, null)).toBe('')
    expect(sessionInfoLine(null, info())).toBe('')
  })

  it('shows the phone alone before the first session-info arrives', () => {
    expect(sessionInfoLine('Pixel 8', null)).toBe('Connected to Pixel 8')
  })

  it('lists the cast summary the mobile sent', () => {
    expect(sessionInfoLine('Pixel 8', info())).toBe(
      'Connected to Pixel 8 — 1280×720 · 30 fps · balanced · game audio · mic'
    )
  })

  it('omits audio sources that are off', () => {
    const line = sessionInfoLine('TB321FU', info({ gameAudio: true, mic: false }))
    expect(line).toBe('Connected to TB321FU — 1280×720 · 30 fps · balanced · game audio')
  })

  it('omits the whole sources tail when neither audio source is on', () => {
    const line = sessionInfoLine('TB321FU', info({ gameAudio: false, mic: false }))
    expect(line).toBe('Connected to TB321FU — 1280×720 · 30 fps · balanced')
  })

  it('tracks the profile label the mobile chose', () => {
    const line = sessionInfoLine('Pixel 8', info({ profile: 'cool', width: 960, height: 540, fps: 24 }))
    expect(line).toBe('Connected to Pixel 8 — 960×540 · 24 fps · cool · game audio · mic')
  })
})
