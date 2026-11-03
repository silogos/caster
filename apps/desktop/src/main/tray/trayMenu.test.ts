import { describe, expect, it } from 'vitest'
import type { CastSessionInfo } from '../../shared/types'
import { trayMenuTemplate, trayStatusLine, type TrayMenuInput } from './trayMenu'

const info = (overrides: Partial<CastSessionInfo> = {}): CastSessionInfo => ({
  profile: 'balanced',
  width: 1920,
  height: 1200,
  fps: 60,
  gameAudio: true,
  mic: false,
  ...overrides
})

const menu = (overrides: Partial<TrayMenuInput> = {}): TrayMenuInput => ({
  statusLine: 'Waiting…',
  trustedPhoneName: null,
  vcSupported: false,
  vcActive: false,
  loginItemSupported: true,
  launchAtLogin: false,
  ...overrides
})

describe('trayStatusLine', () => {
  it('says Waiting… while no phone is paired', () => {
    expect(trayStatusLine('waiting', null, null)).toBe('Waiting…')
    expect(trayStatusLine('waiting', 'TB321FU', null)).toBe('Waiting…')
  })

  it('shows the phone by name once connected — the session-info vocabulary', () => {
    expect(trayStatusLine('connected', 'TB321FU', null)).toBe('Connected to TB321FU')
  })

  it('carries the full cast summary while casting — the same line the receiver shows', () => {
    expect(trayStatusLine('connected', 'TB321FU', info())).toBe(
      'Connected to TB321FU — 1920×1200 · 60 fps · balanced · game audio'
    )
  })

  it('keeps the reconnect-window phrase when the socket dropped (noInputView vocabulary)', () => {
    expect(trayStatusLine('reconnecting', 'TB321FU', null)).toBe('Waiting for TB321FU to reconnect…')
  })

  it('degrades honestly without a name', () => {
    expect(trayStatusLine('connected', null, null)).toBe('Waiting…')
    expect(trayStatusLine('reconnecting', null, null)).toBe('Waiting…')
  })
})

describe('trayMenuTemplate', () => {
  it('leads with the status row, then Open window and Forget phone', () => {
    const items = trayMenuTemplate(menu())
    expect(items[0]).toEqual({ kind: 'status', label: 'Waiting…' })
    expect(items[1]).toEqual({ kind: 'separator' })
    expect(items[2]).toEqual({ kind: 'open-window' })
    expect(items[3]).toEqual({ kind: 'forget-phone', enabled: false })
  })

  it('arms Forget phone only when a phone is trusted (ADR-006)', () => {
    expect(trayMenuTemplate(menu({ trustedPhoneName: 'TB321FU' }))).toContainEqual({
      kind: 'forget-phone',
      enabled: true
    })
    expect(trayMenuTemplate(menu())).toContainEqual({ kind: 'forget-phone', enabled: false })
  })

  it('shows the Virtual camera checkbox only where the backend supports it', () => {
    expect(trayMenuTemplate(menu({ vcSupported: true, vcActive: true }))).toContainEqual({
      kind: 'vc-toggle',
      checked: true
    })
    expect(trayMenuTemplate(menu({ vcSupported: false }))).not.toContainEqual(
      expect.objectContaining({ kind: 'vc-toggle' })
    )
  })

  it('shows Launch at login only where Electron can set it, mirroring the OS state', () => {
    expect(trayMenuTemplate(menu({ launchAtLogin: true }))).toContainEqual({
      kind: 'launch-at-login',
      checked: true
    })
    expect(trayMenuTemplate(menu({ loginItemSupported: false }))).not.toContainEqual(
      expect.objectContaining({ kind: 'launch-at-login' })
    )
  })

  it('always ends with Quit behind a separator', () => {
    const items = trayMenuTemplate(menu())
    expect(items[items.length - 2]).toEqual({ kind: 'separator' })
    expect(items[items.length - 1]).toEqual({ kind: 'quit' })
  })
})
