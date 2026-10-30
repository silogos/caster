import { app, BrowserWindow, ipcMain } from 'electron'
import { hostname } from 'node:os'
import { join } from 'node:path'
import { IPC } from '../shared/ipc'
import type { MobileStateEvent, PairingSessionView, TrustedDeviceView, VcPumpCommand } from '../shared/types'
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
import { TrustStore, loadOrCreateDesktopId } from './pairing/trustStore'
import { getLanIpv4Hosts } from './pairing/networkInfo'
import { BYE_FORGET, SIGNALING_PORT_DEFAULT, SignalingServer } from './signaling/signalingServer'
import { loadSoftcam } from './vc/softcamLibrary'
import { SoftcamRegistration, softcamDir } from './vc/registration'
import { VirtualCameraController } from './vc/virtualCamera'
import { logger } from './log'

// All cast configuration lives on the mobile app; this process owns sockets,
// sessions, and the window (docs/architecture/desktop.md). This module only
// wires the modules together and relays their events to the renderer.

const LOG_SCOPE = 'app'
/** How often the app checks whether the session needs regenerating. */
const EXPIRY_SWEEP_INTERVAL_MS = 1_000

let mainWindow: BrowserWindow | null = null
let pairing: PairingServer | null = null
// Persistent session (ADR-006): the trust outlives every QR session —
// a trusted phone reconnects with one tap on its Connect button, and the
// desktop opens on the receiver's no-input stage instead of the QR hero.
let trust: TrustStore | null = null
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
// The virtual camera (ADR-007): created once the signaling server exists (it
// owns the vc-status sender); null-checked in the signaling callbacks because
// the server is constructed first.
let virtualCamera: VirtualCameraController | null = null

/** IPC input from the renderer is untrusted at the process boundary — one packed BGR24 frame. */
function asVcFrame(raw: unknown): { data: Uint8Array; width: number; height: number } | null {
  if (typeof raw !== 'object' || raw === null) return null
  const { data, width, height } = raw as Record<string, unknown>
  if (!(data instanceof Uint8Array)) return null
  if (!Number.isInteger(width) || !Number.isInteger(height)) return null
  if ((width as number) <= 0 || (height as number) <= 0) return null
  if (data.byteLength !== (width as number) * (height as number) * 3) return null
  return { data, width: width as number, height: height as number }
}
function pushToRenderer(
  channel: string,
  payload:
    | PairingSessionView
    | null
    | MobileStateEvent
    | { pc: PcId; sdp: string }
    | { pc: PcId; candidate: unknown }
    | VcPumpCommand
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
  // ADR-007 lifecycle: the camera dies with the cast — a dropped socket past
  // recovery or a replaced session means media is dead (a returning mobile
  // always re-offers), so the virtual camera must not outlive it.
  if (state.state === 'waiting' || state.state === 'disconnected') {
    virtualCamera?.onCastGone()
  }
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

// One instance at a time (Phase15 live finding): a second copy used to bind
// an ephemeral signaling port and show a QR advertising a port nobody
// scanned — two windows, two QRs, one very confused user (R7/R9 territory).
// The duplicate logs and quits; launching again just focuses the live window.
const gotInstanceLock = app.requestSingleInstanceLock()
if (!gotInstanceLock) {
  logger.info(LOG_SCOPE, 'another instance is already running — this copy exits')
  app.quit()
}
app.on('second-instance', () => {
  if (mainWindow !== null && !mainWindow.isDestroyed()) {
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.focus()
  }
})

app.whenReady().then(async () => {
  // A duplicate copy: quit was already requested — never bind sockets or
  // compete for the pairing port.
  if (!gotInstanceLock) return
  // Read the persistent trust before anything can serve a resume (ADR-006):
  // corrupt/unreadable degrades to "no trusted device" (trustStore.load).
  const userData = app.getPath('userData')
  trust = new TrustStore({ file: () => join(userData, 'trust.json') })
  trust.load()
  const desktopId = loadOrCreateDesktopId(() => join(userData, 'desktop-id'))
  // Order matters: bind the signaling port first (the QR must carry the actual
  // port), then build the pairing store around it, then publish the first QR.
  const signaling = new SignalingServer({
    desktopName: hostname(),
    trustStore: trust,
    desktopId,
    currentHosts: getLanIpv4Hosts,
    onMobileConnected: ({ name, resume }) => {
      logger.info(LOG_SCOPE, resume ? 'trusted phone re-connected (resume)' : 'phone paired via QR')
      pushMobileState({ state: 'connected', name })
    },
    // Phase15: the authorized socket dropped — the pairing session is still
    // live (reconnect window open until expiry), so the receiver keeps
    // showing the device instead of falling back to the QR hero. With a
    // trusted phone the no-input stage now stays up for good (ADR-006).
    onMobileDisconnected: () => {
      if (pairedMobileName !== null) {
        pushMobileState({ state: 'disconnected', name: pairedMobileName })
      } else {
        pushWaiting()
      }
    },
    onBye: (reason) => {
      logger.info(LOG_SCOPE, 'session ended', { reason })
      const trustedName = trust?.get()?.name ?? null
      pairing
        ?.createSession()
        .then(() => {
          // `bye forget` (the phone's Disconnect) revoked the trust — fresh QR.
          // A plain end with trust intact keeps the no-input stage (the phone
          // reconnects with one tap); only an untrusted end returns to the QR.
          if (reason === BYE_FORGET || trustedName === null) {
            pushWaiting()
          } else {
            pushMobileState({ state: 'disconnected', name: pairedMobileName ?? trustedName })
          }
        })
        .catch((error) => logger.error(LOG_SCOPE, 'regeneration after bye failed', { error: String(error) }))
    },
    // Media plumbing (webrtc.md): the main process relays SDP/ICE frames
    // between the socket and the renderer's ReceiverSession — it never answers
    // offers or inspects SDP/candidates itself (desktop.md process split).
    onSdpOffer: (offer) => pushToRenderer(IPC.signaling.sdpOffer, offer),
    onIceCandidate: (event) => pushToRenderer(IPC.signaling.iceCandidate, event),
    onSessionInfo: (info) => pushMobileState({ state: 'session-info', info }),
    // Virtual camera control (webrtc.md proto 2, ADR-007) — the controller
    // owns the camera; the callbacks are async fire-and-forget by shape.
    onVcQuery: () => void virtualCamera?.onVcQuery(),
    onVcStart: () => void virtualCamera?.onVcStart(),
    onVcStop: () => void virtualCamera?.onVcStop()
  })
  await signaling.start(SIGNALING_PORT_DEFAULT)

  // The virtual camera (ADR-007): the Windows softcam adapter today, the same
  // seam for PipeWire/Syphon later. Cast-scoped lifecycle: the camera dies
  // with the cast (see the teardown hooks below — every exit path).
  const softcamDirectory = softcamDir(app.isPackaged, process.resourcesPath, app.getAppPath())
  virtualCamera = new VirtualCameraController({
    softcam: loadSoftcam(softcamDirectory),
    registration: new SoftcamRegistration({
      installerPath: join(softcamDirectory, 'softcam_installer.exe'),
      dllPath: join(softcamDirectory, 'softcam.dll')
    }),
    streamSize: () => lastStreamSize,
    sendStatus: (status) => signaling.sendVcStatus(status),
    onPumpCommand: (command) => pushToRenderer(IPC.vc.pump, command)
  })
  void virtualCamera.ready()

  pairing = new PairingServer({
    port: signaling.actualPort,
    // Phase15: the expiry sweep must not kill a live cast at the QR's TTL —
    // an authorized session with a connected socket defers regeneration.
    hasLiveAuthorizedSocket: () => signaling.hasAuthorizedConnection(),
    onSessionChanged: (view) => pushToRenderer(IPC.pairing.sessionUpdated, view)
  })
  signaling.attachPairing(pairing)
  await pairing.createSession()

  // Regenerate the QR once the session has expired (the desktop clock is
  // authoritative, pairing.md) or after a failed creation attempt. A fresh
  // session means no device is paired anymore — 'waiting' (QR hero) follows,
  // unless a trusted phone keeps the receiver on the no-input stage (ADR-006).
  setInterval(() => {
    pairing
      ?.ensureFreshSession()
      .then((regenerated) => {
        if (!regenerated) return
        const trustedName = trust?.get()?.name ?? null
        if (trustedName === null) {
          pushWaiting()
        } else {
          pushMobileState({ state: 'disconnected', name: pairedMobileName ?? trustedName })
        }
      })
      .catch((error) => logger.error(LOG_SCOPE, 'expiry sweep failed', { error: String(error) }))
  }, EXPIRY_SWEEP_INTERVAL_MS)

  ipcMain.handle(IPC.pairing.getSession, () => pairing!.currentView())
  ipcMain.handle(IPC.pairing.getTrustedDevice, (): TrustedDeviceView | null => {
    const device = trust?.get() ?? null
    return device === null ? null : { name: device.name, lastConnectedAt: device.lastConnectedAt }
  })
  // The desktop Disconnect (the no-input stage's button, ADR-006): revoke the
  // persistent trust and return to the QR hero — bye 'forget' to a connected
  // phone, a silent revoke when it is offline.
  ipcMain.handle(IPC.pairing.disconnect, () => {
    if (signaling.hasAuthorizedConnection()) {
      signaling.sendBye(BYE_FORGET)
      return
    }
    trust?.clear()
    pairing
      ?.createSession()
      .then(() => pushWaiting())
      .catch((error) => logger.error(LOG_SCOPE, 'regeneration after disconnect failed', { error: String(error) }))
  })
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

  // The virtual camera's frame path (ADR-007): packed BGR24 frames from the
  // renderer pump → scSendFrame. Fire-and-forget on purpose (30–120 Hz).
  ipcMain.on(IPC.vc.frame, (_event, raw) => {
    const frame = asVcFrame(raw)
    if (frame === null) {
      logger.warn(LOG_SCOPE, 'renderer sent a malformed vc frame — dropping')
      return
    }
    virtualCamera?.onFrame(frame.data)
  })
  // The pump gave up (e.g. repeated WebGL context loss) — stop the camera
  // honestly; the phone's toggle follows the vc-status push.
  ipcMain.on(IPC.vc.pumpFailed, () => {
    logger.warn(LOG_SCOPE, 'renderer vc pump failed — stopping the virtual camera')
    virtualCamera?.onCastGone()
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
      // The cast is over — the virtual camera dies with it (ADR-007).
      virtualCamera?.onCastGone()
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

// App quit: delete the camera without protocol chatter — the socket may be
// gone (softcam also cleans up on process death, but we never rely on it).
app.on('will-quit', () => {
  virtualCamera?.dispose()
})
