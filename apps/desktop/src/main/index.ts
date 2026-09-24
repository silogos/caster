import { app, BrowserWindow, ipcMain } from 'electron'
import { hostname } from 'node:os'
import { IPC } from '../shared/ipc'
import type { MobileStateEvent, PairingSessionView } from '../shared/types'
import type { PcId } from '../shared/types'
import { isPcId } from './signaling/envelope'
import {
  createMainWindow,
  createSessionInfoWindow,
  enterCastWindowLayout,
  leaveCastWindowLayout,
  resizeToStreamAspect,
  startCastKeepAwake,
  stopCastKeepAwake
} from './window'
import { PairingServer } from './pairing/pairingServer'
import { SIGNALING_PORT_DEFAULT, SignalingServer } from './signaling/signalingServer'
import { logger } from './log'

// All cast configuration lives on the mobile app; this process owns sockets,
// sessions, and the window (docs/architecture/desktop.md). This module only
// wires the modules together and relays their events to the renderer.

const LOG_SCOPE = 'app'
/** How often the app checks whether the session needs regenerating. */
const EXPIRY_SWEEP_INTERVAL_MS = 1_000

let mainWindow: BrowserWindow | null = null
let pairing: PairingServer | null = null
// Phase14 receiver-window state, owned here because windows are main-process
// domain (desktop.md process model):
// - whether a cast is live (drives keep-awake, the relaxed minimums, and the
//   session-info window's lifetime),
// - the user's session-info-overlay preference,
// - the latest mobile state + stream size, replayed to the overlay window when
//   it opens and to a "fit window to video" request.
let castActive = false
let sessionInfoOverlayEnabled = false
let sessionInfoWindow: BrowserWindow | null = null
let lastMobileState: MobileStateEvent | null = null
let lastStreamSize: { width: number; height: number } | null = null
// The paired device's name, tracked separately from lastMobileState (which
// mid-cast holds the latest session-info): 'disconnected' (Phase15) needs the
// name after the socket is gone, and only a real session replacement clears it.
let pairedMobileName: string | null = null

function pushToRenderer(
  channel: string,
  payload: PairingSessionView | null | MobileStateEvent | { pc: PcId; sdp: string } | { pc: PcId; candidate: unknown }
): void {
  if (mainWindow !== null && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload)
  }
}

/**
 * Mobile state reaches BOTH renderer windows: the receiver (status line) and,
 * while it exists, the off-cast session-info window (Phase14). The latest
 * 'connected'/'session-info' event is cached so a mid-cast toggle can replay
 * the current line into the freshly opened overlay.
 */
function pushMobileState(state: MobileStateEvent): void {
  lastMobileState = state.state === 'waiting' ? null : state
  if (state.state === 'connected') pairedMobileName = state.name
  pushToRenderer(IPC.pairing.mobileState, state)
  if (sessionInfoWindow !== null && !sessionInfoWindow.isDestroyed()) {
    sessionInfoWindow.webContents.send(IPC.pairing.mobileState, state)
  }
}

/**
 * 'waiting' is pushed only when the session itself is replaced (bye, expiry
 * sweep, regenerate button) — that is the only moment the QR hero honestly
 * means "no device is paired" (Phase15: a socket drop alone keeps the
 * reconnect window open, so the receiver shows "No input video" instead).
 */
function pushWaiting(): void {
  pairedMobileName = null
  pushMobileState({ state: 'waiting' })
}

/** IPC input from the renderer is untrusted at the process boundary — validate before it reaches the socket. */
function asSdpMessage(raw: unknown): { pc: PcId; sdp: string } | null {
  if (typeof raw !== 'object' || raw === null) return null
  const { pc, sdp } = raw as Record<string, unknown>
  if (!isPcId(pc) || typeof sdp !== 'string' || sdp.length === 0) return null
  return { pc, sdp }
}

function asIceMessage(raw: unknown): { pc: PcId; candidate: unknown } | null {
  if (typeof raw !== 'object' || raw === null) return null
  const { pc, candidate } = raw as Record<string, unknown>
  if (!isPcId(pc) || !('candidate' in (raw as Record<string, unknown>))) return null
  return { pc, candidate }
}

/** Renderer input is untrusted at the process boundary — same rule as the SDP/ICE relays above. */
function asStreamSize(raw: unknown): { width: number; height: number } | null {
  if (typeof raw !== 'object' || raw === null) return null
  const { width, height } = raw as Record<string, unknown>
  if (!Number.isInteger(width) || !Number.isInteger(height)) return null
  if ((width as number) <= 0 || (height as number) <= 0) return null
  return { width: width as number, height: height as number }
}

app.whenReady().then(async () => {
  // Order matters: bind the signaling port first (the QR must carry the actual
  // port), then build the pairing store around it, then publish the first QR.
  const signaling = new SignalingServer({
    desktopName: hostname(),
    onMobileConnected: ({ name }) => pushMobileState({ state: 'connected', name }),
    // Phase15: the authorized socket dropped — the pairing session is still
    // live (reconnect window open until expiry), so the receiver keeps
    // showing the device instead of falling back to the QR hero.
    onMobileDisconnected: () => {
      if (pairedMobileName !== null) {
        pushMobileState({ state: 'disconnected', name: pairedMobileName })
      } else {
        pushWaiting()
      }
    },
    onBye: (reason) => {
      logger.info(LOG_SCOPE, 'session ended', { reason })
      pairing
        ?.createSession()
        .then(() => pushWaiting())
        .catch((error) => logger.error(LOG_SCOPE, 'regeneration after bye failed', { error: String(error) }))
    },
    // Media plumbing (webrtc.md): the main process relays SDP/ICE frames
    // between the socket and the renderer's ReceiverSession — it never answers
    // offers or inspects SDP/candidates itself (desktop.md process split).
    onSdpOffer: (offer) => pushToRenderer(IPC.signaling.sdpOffer, offer),
    onIceCandidate: (event) => pushToRenderer(IPC.signaling.iceCandidate, event),
    onSessionInfo: (info) => pushMobileState({ state: 'session-info', info })
  })
  await signaling.start(SIGNALING_PORT_DEFAULT)

  pairing = new PairingServer({
    port: signaling.actualPort,
    onSessionChanged: (view) => pushToRenderer(IPC.pairing.sessionUpdated, view)
  })
  signaling.attachPairing(pairing)
  await pairing.createSession()

  // Regenerate the QR once the session has expired (the desktop clock is
  // authoritative, pairing.md) or after a failed creation attempt. A fresh
  // session means no device is paired anymore — 'waiting' (QR hero) follows.
  setInterval(() => {
    pairing
      ?.ensureFreshSession()
      .then((regenerated) => {
        if (regenerated) pushWaiting()
      })
      .catch((error) => logger.error(LOG_SCOPE, 'expiry sweep failed', { error: String(error) }))
  }, EXPIRY_SWEEP_INTERVAL_MS)

  ipcMain.handle(IPC.pairing.getSession, () => pairing!.currentView())
  ipcMain.handle(IPC.pairing.regenerate, () => {
    signaling.disconnectAuthorized()
    return pairing!
      .createSession()
      .then((session) => {
        pushWaiting()
        return session
      })
  })

  // Renderer (ReceiverSession) → socket: the desktop's half of the media plumbing.
  ipcMain.handle(IPC.signaling.sendSdpAnswer, (_event, raw) => {
    const message = asSdpMessage(raw)
    if (message === null) {
      logger.warn(LOG_SCOPE, 'renderer sent a malformed sdp-answer — dropping')
      return
    }
    logger.info(LOG_SCOPE, `sdp-answer for pc=${message.pc}`)
    signaling.sendSdpAnswer(message.pc, message.sdp)
  })
  ipcMain.handle(IPC.signaling.sendIceCandidate, (_event, raw) => {
    const message = asIceMessage(raw)
    if (message === null) {
      logger.warn(LOG_SCOPE, 'renderer sent a malformed ice candidate — dropping')
      return
    }
    signaling.sendIceCandidate(message.pc, message.candidate)
  })

  // Overlay lifecycle (Phase14): the window exists exactly while a cast is
  // live AND the user wants it. The receiver's × and the Settings checkbox
  // both funnel through setSessionInfoOverlayEnabled, so one state backs both.
  const closeSessionInfoWindow = (): void => {
    if (sessionInfoWindow !== null && !sessionInfoWindow.isDestroyed()) {
      sessionInfoWindow.close()
    }
    sessionInfoWindow = null
  }

  const pushOverlayState = (enabled: boolean): void => {
    if (mainWindow !== null && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send(IPC.window.sessionInfoOverlay, enabled)
    }
  }

  const setSessionInfoOverlayEnabled = (enabled: boolean): void => {
    sessionInfoOverlayEnabled = enabled
    if (enabled && castActive && sessionInfoWindow === null) {
      sessionInfoWindow = createSessionInfoWindow()
      // Replay the current line into the fresh window (mid-cast toggles);
      // 'waiting' never reaches here — the cache is cleared by then.
      sessionInfoWindow.webContents.once('did-finish-load', () => {
        if (sessionInfoWindow !== null && !sessionInfoWindow.isDestroyed() && lastMobileState !== null) {
          sessionInfoWindow.webContents.send(IPC.pairing.mobileState, lastMobileState)
        }
      })
      sessionInfoWindow.on('closed', () => {
        sessionInfoWindow = null
      })
      logger.info(LOG_SCOPE, 'session-info window opened')
    } else if (!enabled) {
      closeSessionInfoWindow()
    }
    pushOverlayState(enabled)
  }

  // Window behavior (overview.md: the receiver's own control — never a cast
  // setting). While a cast fills the window, the waiting layout's minimums
  // are relaxed so portrait streams can fill it; on cast end they're restored.
  ipcMain.handle(IPC.window.castActive, (_event, raw) => {
    castActive = raw === true
    if (mainWindow === null || mainWindow.isDestroyed()) return
    if (castActive) {
      enterCastWindowLayout(mainWindow)
      // 30+ min sessions: hold the display awake — sleep or a screensaver
      // firing mid-cast would stall or pollute the captured feed (Phase14).
      startCastKeepAwake()
      if (sessionInfoOverlayEnabled) {
        setSessionInfoOverlayEnabled(true)
      }
    } else {
      leaveCastWindowLayout(mainWindow)
      stopCastKeepAwake()
      closeSessionInfoWindow()
      lastStreamSize = null
    }
  })
  // The stream's size (first metadata, every rotation, quality steps): the
  // window's shape is the user's — the video letterboxes via CSS (contain),
  // never the other way around. The size is cached for the on-demand
  // "Match window to video" reshape.
  ipcMain.handle(IPC.window.streamSize, (_event, raw) => {
    const size = asStreamSize(raw)
    if (size === null) {
      logger.warn(LOG_SCOPE, 'renderer sent a malformed stream size — dropping')
      return
    }
    lastStreamSize = size
    logger.info(LOG_SCOPE, 'stream size changed', { width: size.width, height: size.height })
  })
  // On-demand reshape (Phase14): after the user has sized the window for their
  // canvas, one click snaps it to the stream's aspect — the same geometry
  // math as before (current content area, stream aspect, work-area clamps).
  ipcMain.handle(IPC.window.fitToStream, () => {
    if (mainWindow === null || mainWindow.isDestroyed()) return
    if (!castActive || lastStreamSize === null) {
      logger.warn(LOG_SCOPE, 'match-to-stream requested with no live stream size — ignoring')
      return
    }
    resizeToStreamAspect(mainWindow, lastStreamSize.width, lastStreamSize.height)
    logger.info(LOG_SCOPE, 'window matched to the stream', { width: lastStreamSize.width, height: lastStreamSize.height })
  })
  ipcMain.handle(IPC.window.sessionInfoOverlay, (_event, raw) => {
    if (typeof raw !== 'boolean') {
      logger.warn(LOG_SCOPE, 'renderer sent a malformed session-info-overlay state — dropping')
      return
    }
    setSessionInfoOverlayEnabled(raw)
  })

  mainWindow = createMainWindow()
  // The receiver window is the product: if it is closed mid-cast, the session
  // is over even if the companion overlay is still open — release the blocker
  // and the overlay with it (window-all-closed then quits as before).
  mainWindow.on('closed', () => {
    stopCastKeepAwake()
    closeSessionInfoWindow()
  })

  app.on('activate', () => {
    // macOS: re-create the window when the dock icon is clicked with no windows.
    if (BrowserWindow.getAllWindows().length === 0) {
      mainWindow = createMainWindow()
    }
  })
}).catch((error) => {
  logger.error(LOG_SCOPE, 'startup failed', { error: String(error) })
  app.quit()
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
