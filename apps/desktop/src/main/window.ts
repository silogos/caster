import { BrowserWindow, powerSaveBlocker, screen } from 'electron'
import { join } from 'node:path'
import { contentRectForAspect } from './windowGeometry'

const WINDOW_DEFAULT_WIDTH = 960
const WINDOW_DEFAULT_HEIGHT = 640
const WINDOW_MIN_WIDTH = 800
const WINDOW_MIN_HEIGHT = 520
// Near-black, matches the renderer background so there is no white flash on open.
const WINDOW_BACKGROUND_COLOR = '#0a0c10'

// The off-cast session-info window (Phase14): a small, frameless, always-on-top
// companion — outside the receiver window, so window capture (e.g. OBS) sees a
// pure video feed. Wide enough for the full status line; the text ellipsizes if
// a longer line ever appears.
export const SESSION_INFO_WINDOW_WIDTH = 520
export const SESSION_INFO_WINDOW_HEIGHT = 64

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
 * A cast is live: relax the window's minimum size so the user can keep the
 * window small (a portrait stream letterboxed into a compact window is a
 * perfectly good receiver shape).
 */
export function enterCastWindowLayout(window: BrowserWindow): void {
  window.setMinimumSize(CAST_MIN_WIDTH, CAST_MIN_HEIGHT)
}

/** The cast is over: back to the waiting layout's minimums. */
export function leaveCastWindowLayout(window: BrowserWindow): void {
  window.setMinimumSize(WINDOW_MIN_WIDTH, WINDOW_MIN_HEIGHT)
}

/**
 * Snap the window to the stream's aspect — the ON-DEMAND "Match window to
 * video" action (Phase14). The window's shape is otherwise the user's: by
 * default the video letterboxes via CSS and the window never moves by
 * itself (the early auto-reshape at cast start/rotation was found live to
 * fight the user's own window sizing, especially for portrait streams).
 * Geometry math: windowGeometry.ts.
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

/**
 * The optional session-info overlay (Phase14) — a second window on purpose:
 * window capture targets one window, so keeping the info line *off* the
 * receiver window keeps the captured feed pure video. Frameless and always on
 * top; the page drags via a CSS app-region and closes through the same IPC
 * toggle the Settings checkbox uses, so both controls share one state.
 */
export function createSessionInfoWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: SESSION_INFO_WINDOW_WIDTH,
    height: SESSION_INFO_WINDOW_HEIGHT,
    resizable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    frame: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    backgroundColor: WINDOW_BACKGROUND_COLOR,
    show: false,
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
    window.loadURL(`${process.env['ELECTRON_RENDERER_URL']}/overlay.html`)
  } else {
    window.loadFile(join(__dirname, '../renderer/overlay.html'))
  }
  return window
}

// OBS-session hardening (Phase14): display sleep or a screensaver firing
// mid-session would stall or pollute the captured feed — the blocker holds
// the display awake for exactly the cast's lifetime.
let keepAwakeId: number | null = null

export function startCastKeepAwake(): void {
  if (keepAwakeId !== null && powerSaveBlocker.isStarted(keepAwakeId)) return
  keepAwakeId = powerSaveBlocker.start('prevent-display-sleep')
}

export function stopCastKeepAwake(): void {
  if (keepAwakeId === null || !powerSaveBlocker.isStarted(keepAwakeId)) return
  powerSaveBlocker.stop(keepAwakeId)
  keepAwakeId = null
}
