import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'
import { IPC } from '../shared/ipc'
import type { DesktopApi } from '../shared/ipc'
import type {
  MobileStateEvent,
  PairingSessionView,
  SignalingIceMessage,
  SignalingSdpMessage,
  VcDesktopState,
  VcPumpCommand
} from '../shared/types'

function subscribe<T>(channel: string, listener: (payload: T) => void): () => void {
  const wrapped = (_event: IpcRendererEvent, payload: T): void => listener(payload)
  ipcRenderer.on(channel, wrapped)
  return () => ipcRenderer.removeListener(channel, wrapped)
}

const desktopApi: DesktopApi = {
  getPairingSession: () => ipcRenderer.invoke(IPC.pairing.getSession),
  getTrustedDevice: () => ipcRenderer.invoke(IPC.pairing.getTrustedDevice),
  disconnectDesktop: () => {
    ipcRenderer.invoke(IPC.pairing.disconnect)
  },
  regeneratePairingSession: () => ipcRenderer.invoke(IPC.pairing.regenerate),
  onPairingSessionUpdated: (listener) => subscribe<PairingSessionView | null>(IPC.pairing.sessionUpdated, listener),
  onMobileStateChanged: (listener) => subscribe<MobileStateEvent>(IPC.pairing.mobileState, listener),
  onSignalingSdpOffer: (listener) => subscribe<SignalingSdpMessage>(IPC.signaling.sdpOffer, listener),
  onSignalingIceCandidate: (listener) => subscribe<SignalingIceMessage>(IPC.signaling.iceCandidate, listener),
  sendSdpAnswer: (pc, sdp) => {
    ipcRenderer.invoke(IPC.signaling.sendSdpAnswer, { pc, sdp } satisfies SignalingSdpMessage)
  },
  sendIceCandidate: (pc, candidate) => {
    ipcRenderer.invoke(IPC.signaling.sendIceCandidate, { pc, candidate } satisfies SignalingIceMessage)
  },
  setCastActive: (active) => {
    ipcRenderer.invoke(IPC.window.castActive, active)
  },
  reportStreamSize: (width, height) => {
    ipcRenderer.invoke(IPC.window.streamSize, { width, height })
  },
  setSessionInfoOverlay: (enabled) => {
    ipcRenderer.invoke(IPC.window.sessionInfoOverlay, enabled)
  },
  onSessionInfoOverlayChanged: (listener) => subscribe<boolean>(IPC.window.sessionInfoOverlay, listener),
  fitWindowToStream: () => {
    ipcRenderer.invoke(IPC.window.fitToStream)
  },
  onVcPump: (listener) => subscribe<VcPumpCommand>(IPC.vc.pump, listener),
  sendVcFrame: (data, width, height) => {
    // Plain send (fire-and-forget): frames are 30–120 Hz — invoke()'s reply
    // round-trips would only queue promises. Structured clone copies the bytes.
    ipcRenderer.send(IPC.vc.frame, { data, width, height })
  },
  reportVcPumpFailed: () => {
    ipcRenderer.send(IPC.vc.pumpFailed)
  },
  onVcState: (listener) => subscribe<VcDesktopState>(IPC.vc.state, listener),
  toggleVirtualCamera: () => {
    ipcRenderer.invoke(IPC.vc.toggle)
  }
}

contextBridge.exposeInMainWorld('desktopApi', desktopApi)
