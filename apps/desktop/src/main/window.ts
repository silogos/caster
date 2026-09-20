import { BrowserWindow, screen } from 'electron'
import { join } from 'node:path'
import { contentRectForAspect } from './windowGeometry'

const WINDOW_DEFAULT_WIDTH = 960
const WINDOW_DEFAULT_HEIGHT = 640
const WINDOW_MIN_WIDTH = 800
const WINDOW_MIN_HEIGHT = 520
// Near-black, matches the renderer background so there is no white flash on open.
const WINDOW_BACKGROUND_COLOR = '#0a0c10'

/** While a cast fills the window, the waiting layout's minimums (QR + status) don't apply. */
export const CAST_MIN_WIDTH = 320
export const CAST_MIN_HEIGHT = 240

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

/**
 * A cast is live: relax the window's minimum size so a portrait stream can
 * actually fill the window (the waiting layout's 800px minimum would force
 * letterboxing for every portrait cast).
 */
export function enterCastWindowLayout(window: BrowserWindow): void {
  window.setMinimumSize(CAST_MIN_WIDTH, CAST_MIN_HEIGHT)
}

/** The cast is over: back to the waiting layout's minimums. */
export function leaveCastWindowLayout(window: BrowserWindow): void {
  window.setMinimumSize(WINDOW_MIN_WIDTH, WINDOW_MIN_HEIGHT)
}

/**
 * Reshape the window to the stream's aspect (rotation included) so the
 * letterboxed video fills it edge-to-edge — the receiver's window behavior,
 * never a cast setting (overview.md). Geometry math: windowGeometry.ts.
 */
export function resizeToStreamAspect(
  window: BrowserWindow,
  streamWidth: number,
  streamHeight: number
): void {
  const content = window.getContentBounds()
  const workArea = screen.getDisplayMatching(window.getBounds()).workArea
  window.setContentBounds(contentRectForAspect(content, streamWidth / streamHeight, workArea))
}
