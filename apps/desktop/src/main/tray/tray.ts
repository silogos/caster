import { app, Menu, nativeImage, Tray } from 'electron'
import { trayMenuTemplate, type TrayMenuInput, type TrayMenuItem } from './trayMenu'

/**
 * The app's menu-bar / system-tray presence (issue #94, ADR-010): the tray
 * owns the app's lifetime — closing the receiver window hides it, and only
 * Quit (here, Cmd+Q, or the OS) ends the app. All state and copy decisions
 * live in the pure `trayMenu.ts`; this module only maps the model onto
 * Electron and forwards clicks. Instance-based (no module-level refs): the
 * app entry holds the one CastTray and calls [update] on every state change
 * that the menu shows.
 */

/** 22×22 template PNG (cast glyph: dot + waves + top bar) — embedded, no file IO. */
const TRAY_ICON_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAABYAAAAWCAYAAADEtGw7AAAARElQVR42mNgGAV4wH8K8MAYTPdgoXl4D17D/xOwgKrhSxXD/xNhOAM1DadqWFNsOLHep9jg/7Q2mIEW4Un1nDYKqAMALahrlbhitFgAAAAASUVORK5CYII='

const TRAY_ITEM_LABELS: Record<Exclude<TrayMenuItem, { kind: 'status' | 'separator' }>['kind'], string> = {
  'open-window': 'Open window',
  'forget-phone': 'Forget phone',
  'vc-toggle': 'Virtual camera',
  'launch-at-login': 'Launch at login',
  quit: 'Quit'
}

export interface TrayCallbacks {
  onOpenWindow: () => void
  /** The tray's Forget phone — same revocation as the no-input stage's Disconnect (ADR-006). */
  onForgetPhone: () => void
  onToggleVirtualCamera: () => void
  onSetLaunchAtLogin: (enabled: boolean) => void
  onQuit: () => void
}

/** Electron can set login items on macOS and Windows only — the checkbox hides elsewhere. */
export function loginItemSupported(): boolean {
  return process.platform === 'darwin' || process.platform === 'win32'
}

/** The OS is the store for this preference (no second persistence layer). */
export function readLaunchAtLogin(): boolean {
  if (!loginItemSupported()) return false
  return app.getLoginItemSettings().openAtLogin
}

export function writeLaunchAtLogin(enabled: boolean): void {
  if (!loginItemSupported()) return
  app.setLoginItemSettings({ openAtLogin: enabled })
}

export class CastTray {
  private readonly tray: Tray

  constructor(private readonly callbacks: TrayCallbacks) {
    const icon = nativeImage.createFromBuffer(Buffer.from(TRAY_ICON_BASE64, 'base64'))
    // Template image on macOS: black + alpha, follows the menu bar's light/dark.
    if (process.platform === 'darwin') icon.setTemplateImage(true)
    this.tray = new Tray(icon)
    this.tray.setToolTip('Zero-Friction Local Cast')
  }

  /** Rebuild the menu from the current state — cheap, called on every change it shows. */
  update(input: TrayMenuInput): void {
    const template = trayMenuTemplate(input).map((item) => {
      if (item.kind === 'separator') return { type: 'separator' as const }
      if (item.kind === 'status') return { label: item.label, enabled: false }
      const label = TRAY_ITEM_LABELS[item.kind]
      switch (item.kind) {
        case 'open-window':
          return { label, click: this.callbacks.onOpenWindow }
        case 'forget-phone':
          return { label, enabled: item.enabled, click: this.callbacks.onForgetPhone }
        case 'vc-toggle':
          return { label, type: 'checkbox' as const, checked: item.checked, click: () => this.callbacks.onToggleVirtualCamera() }
        case 'launch-at-login':
          return {
            label,
            type: 'checkbox' as const,
            checked: item.checked,
            click: () => this.callbacks.onSetLaunchAtLogin(!item.checked)
          }
        case 'quit':
          return { label, click: this.callbacks.onQuit }
      }
    })
    this.tray.setContextMenu(Menu.buildFromTemplate(template))
  }

  destroy(): void {
    this.tray.destroy()
  }
}
