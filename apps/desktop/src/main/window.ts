import { BrowserWindow } from 'electron'
import { join } from 'node:path'

const WINDOW_DEFAULT_WIDTH = 960
const WINDOW_DEFAULT_HEIGHT = 640
const WINDOW_MIN_WIDTH = 800
const WINDOW_MIN_HEIGHT =520
// Near-black, matches the renderer background so there is no white flash on open.
const WINDOW_BACKGROUND_COLOR = '#0a0c10'

/**
 * Window lifecycle for the receiver. In later phases this module also owns the
 * powerSaveBlocker during an active cast (docs/architecture/desktop.md).
 */
export function createMainWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: WINDOW_DEFAULT_WIDTH,
    height: WINDOW_DEFAULT_HEIGHT,
    minWidth: WINDOW_MIN_WIDTH,
    minHeight: WINDOW_MIN_HEIGHT,
    backgroundColor: WINDOW_BACKGROUND_COLOR,
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      spellcheck: false,
      sandbox: true
    }
  })

  window.on('ready-to-show', () => {
    window.show()
  })

  if (process.env['ELECTRON_RENDERER_URL']) {
    // Dev: load the vite dev server (openDevTools alone leaves the window
    // blank — the URL must actually be loaded; found during Phase6 verification).
    window.loadURL(process.env['ELECTRON_RENDERER_URL'])
    window.webContents.openDevTools({ mode: 'detach' })
  } else {
    window.loadFile(join(__dirname, '../renderer/index.html'))
  }

  return window
}
