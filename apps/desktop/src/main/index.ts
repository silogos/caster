import { app, BrowserWindow, ipcMain } from 'electron'
import { hostname } from 'node:os'
import { IPC } from '../shared/ipc'
import type { MobileStateEvent, PairingSessionView } from '../shared/types'
import { createMainWindow } from './window'
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

function pushToRenderer(channel: string, payload: PairingSessionView | null | MobileStateEvent): void {
  if (mainWindow !== null && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload)
  }
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
    // Phase4: signaling is dumb plumbing — media messages are logged, and the
    // ReceiverSession (Phase5) will consume/answer them. session-info is the
    // one message the desktop displays (status line, webrtc.md).
    onSdpOffer: ({ pc }) => logger.info(LOG_SCOPE, `sdp-offer received for pc=${pc} — answering is Phase5`),
    onIceCandidate: ({ pc, candidate }) =>
      logger.debug(LOG_SCOPE, `ice candidate for pc=${pc}`, { candidate: candidate === null ? 'end-of-gathering' : 'host' }),
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
