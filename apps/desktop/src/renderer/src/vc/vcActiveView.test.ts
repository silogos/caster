import { describe, expect, it } from 'vitest'
import { vcActiveView } from './vcActiveView'

describe('vcActiveView (ADR-007 placeholder copy)', () => {
  it('states what is happening, that preview is off, and that minimizing is fine', () => {
    const view = vcActiveView()
    expect(view.heading).toBe('Virtual camera active')
    expect(view.subline).toContain('published as a camera')
    expect(view.subline).toContain('preview is off')
    expect(view.subline.toLowerCase()).toContain('minimize')
  })
})
