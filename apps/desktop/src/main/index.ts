import { app, BrowserWindow, ipcMain } from 'electron'
import { hostname } from 'node:os'
import { IPC } from '../shared/ipc'
import type { MobileStateEvent, PairingSessionView } from '../shared/types'
import type { PcId } from '../shared/types'
import { isPcId } from './signaling/envelope'
import { createMainWindow, enterCastWindowLayout, leaveCastWindowLayout, resizeToStreamAspect } from './window'
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

function pushToRenderer(
  channel: string,
  payload: PairingSessionView | null | MobileStateEvent | { pc: PcId; sdp: string } | { pc: PcId; candidate: unknown }
): void {
  if (mainWindow !== null && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload)
  }
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
    onMobileConnected: ({ name }) => pushToRenderer(IPC.pairing.mobileState, { state: 'connected', name }),
    onMobileDisconnected: () => pushToRenderer(IPC.pairing.mobileState, { state: 'waiting' }),
    onBye: (reason) => {
      logger.info(LOG_SCOPE, 'session ended', { reason })
      pairing?.createSession().catch((error) => logger.error(LOG_SCOPE, 'regeneration after bye failed', { error: String(error) }))
    },
    // Media plumbing (webrtc.md): the main process relays SDP/ICE frames
    // between the socket and the renderer's ReceiverSession — it never answers
    // offers or inspects SDP/candidates itself (desktop.md process split).
    onSdpOffer: (offer) => pushToRenderer(IPC.signaling.sdpOffer, offer),
    onIceCandidate: (event) => pushToRenderer(IPC.signaling.iceCandidate, event),
    onSessionInfo: (info) => pushToRenderer(IPC.pairing.mobileState, { state: 'session-info', info })
  })
  await signaling.start(SIGNALING_PORT_DEFAULT)

  pairing = new PairingServer({
    port: signaling.actualPort,
    onSessionChanged: (view) => pushToRenderer(IPC.pairing.sessionUpdated, view)
  })
  signaling.attachPairing(pairing)
  await pairing.createSession()

  // Regenerate the QR once the session has expired (the desktop clock is
  // authoritative, pairing.md) or after a failed creation attempt.
  setInterval(() => {
    pairing?.ensureFreshSession().catch((error) => logger.error(LOG_SCOPE, 'expiry sweep failed', { error: String(error) }))
  }, EXPIRY_SWEEP_INTERVAL_MS)

  ipcMain.handle(IPC.pairing.getSession, () => pairing!.currentView())
  ipcMain.handle(IPC.pairing.regenerate, () => {
    signaling.disconnectAuthorized()
    return pairing!.createSession()
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

  // Window behavior (overview.md: the receiver's own control — never a cast
  // setting). While a cast fills the window, the waiting layout's minimums
  // are relaxed so portrait streams can fill it; on cast end they're restored.
  ipcMain.handle(IPC.window.castActive, (_event, raw) => {
    if (mainWindow === null || mainWindow.isDestroyed()) return
    if (raw === true) {
      enterCastWindowLayout(mainWindow)
    } else {
      leaveCastWindowLayout(mainWindow)
    }
  })
  // The window follows the stream's aspect (rotation included) so the
  // letterboxed video fills it edge-to-edge in every orientation.
  ipcMain.handle(IPC.window.resizeToStream, (_event, raw) => {
    const size = asStreamSize(raw)
    if (size === null) {
      logger.warn(LOG_SCOPE, 'renderer sent a malformed stream size — dropping')
      return
    }
    if (mainWindow === null || mainWindow.isDestroyed()) return
    resizeToStreamAspect(mainWindow, size.width, size.height)
    logger.info(LOG_SCOPE, 'window reshaped to the stream', { width: size.width, height: size.height })
  })

  mainWindow = createMainWindow()

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
