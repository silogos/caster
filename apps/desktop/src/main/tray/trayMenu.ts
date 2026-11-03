import type { CastSessionInfo } from '../../shared/types'
import { sessionInfoLine } from '../../renderer/src/sessionInfoLine'

/**
 * The tray's menu model (issue #94, ADR-010) — pure, like `heroView`: the
 * copy and item states are decided here and unit-tested; `tray.ts` only maps
 * the model onto Electron's Menu. The status line reuses the session-info
 * line's vocabulary (the one status line both receiver surfaces render) so
 * the tray never invents a second dialect: `Waiting…` / `Connected to
 * <phone>` / `Waiting for <phone> to reconnect…` (noInputView's phrase) and,
 * while casting, the full `Connected to <phone> — WxH · fps · …` line.
 */

export type TrayConnectionStatus =
  /** No phone paired — the QR hero's state. */
  | 'waiting'
  /** An authorized socket is up. */
  | 'connected'
  /** The socket dropped but the reconnect window is open (ADR-006). */
  | 'reconnecting'

export function trayStatusLine(status: TrayConnectionStatus, name: string | null, info: CastSessionInfo | null): string {
  switch (status) {
    case 'waiting':
      return 'Waiting…'
    case 'reconnecting':
      return name === null ? 'Waiting…' : `Waiting for ${name} to reconnect…`
    case 'connected':
      if (name === null) return 'Waiting…'
      return info === null ? `Connected to ${name}` : sessionInfoLine(name, info)
  }
}

export interface TrayMenuInput {
  statusLine: string
  /** The trusted phone's name — Forget phone is armed only when one exists (ADR-006). */
  trustedPhoneName: string | null
  /** Virtual camera capability (ADR-007): the checkbox only exists where supported. */
  vcSupported: boolean
  vcActive: boolean
  /**
   * Launch at login (receiver/environment control, off by default — issue
   * #94). The checkbox only exists where Electron can set it (macOS/Windows).
   */
  loginItemSupported: boolean
  launchAtLogin: boolean
}

export type TrayMenuItem =
  | { kind: 'status'; label: string }
  | { kind: 'separator' }
  | { kind: 'open-window' }
  | { kind: 'forget-phone'; enabled: boolean }
  | { kind: 'vc-toggle'; checked: boolean }
  | { kind: 'launch-at-login'; checked: boolean }
  | { kind: 'quit' }

/**
 * Status (disabled row) → Open window → Forget phone → Virtual camera →
 * Launch at login → Quit. Identifiers, not labels — `tray.ts` owns the
 * label strings (single source: this model owns structure + state).
 */
export function trayMenuTemplate(input: TrayMenuInput): TrayMenuItem[] {
  const items: TrayMenuItem[] = [
    { kind: 'status', label: input.statusLine },
    { kind: 'separator' },
    { kind: 'open-window' },
    // The no-input stage's Disconnect, in the tray: revoke the trust (bye
    // forget / silent revoke) and return to the QR hero. Armed only when a
    // phone is actually trusted — the un-trusted desktop has nothing to forget.
    { kind: 'forget-phone', enabled: input.trustedPhoneName !== null }
  ]
  if (input.vcSupported) {
    items.push({ kind: 'vc-toggle', checked: input.vcActive })
  }
  if (input.loginItemSupported) {
    items.push({ kind: 'launch-at-login', checked: input.launchAtLogin })
  }
  items.push({ kind: 'separator' }, { kind: 'quit' })
  return items
}
