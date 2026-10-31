import { Menu, Tray, nativeImage, app } from 'electron'

/**
 * The menu-bar presence of the windowless mode (ADR-007 follow-up, shipped
 * 2026-10-31): closing the receiver window hides it instead of ending the
 * app — the cast keeps flowing, the virtual camera keeps publishing (Syphon
 * consumers never notice), and this tray is the app's handle: show the
 * window, toggle the camera, quit. The icon is an embedded macOS template
 * image (black + alpha) so it follows the light/dark menu bar.
 */

/** 22×22 template PNG (cast glyph: dot + waves + top bar), generated once. */
const TRAY_ICON_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAABYAAAAWCAYAAADEtGw7AAAARElQVR42mNgGAV4wH8K8MAYTPdgoXl4D17D/xOwgKrhSxXD/xNhOAM1DadqWFNsOLHep9jg/7Q2mIEW4Un1nDYKqAMALahrlbhitFgAAAAASUVORK5CYII='

export interface TrayCallbacks {
  onShowReceiver: () => void
  onToggleVirtualCamera: () => void
  onQuit: () => void
}

/** True while quit was requested — the window's close-to-hide must step aside. */
let quitting = false

export function requestQuit(): void {
  quitting = true
  app.quit()
}

export function isQuitting(): boolean {
  return quitting
}

let vcActive = false

export function createCastTray(callbacks: TrayCallbacks): Tray {
  const icon = nativeImage.createFromBuffer(Buffer.from(TRAY_ICON_BASE64, 'base64'))
  const tray = new Tray(icon)
  tray.setToolTip('Zero-Friction Local Cast')
  const applyMenu = (): void => {
    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: 'Show receiver', click: callbacks.onShowReceiver },
        { type: 'separator' },
        {
          label: 'Virtual camera',
          type: 'checkbox',
          checked: vcActive,
          click: callbacks.onToggleVirtualCamera
        },
        { type: 'separator' },
        { label: 'Quit', click: callbacks.onQuit }
      ])
    )
  }
  applyMenu()
  applyMenuRef = applyMenu
  return tray
}

let applyMenuRef: (() => void) | null = null

/** Keep the tray's checkbox honest: called on every virtual-camera state change. */
export function refreshTrayMenu(active: boolean): void {
  vcActive = active
  applyMenuRef?.()
}
