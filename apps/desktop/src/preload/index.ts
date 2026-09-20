import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'
import { IPC } from '../shared/ipc'
import type { DesktopApi } from '../shared/ipc'
import type {
  MobileStateEvent,
  PairingSessionView,
  SignalingIceMessage,
  SignalingSdpMessage
} from '../shared/types'

function subscribe<T>(channel: string, listener: (payload: T) => void): () => void {
  const wrapped = (_event: IpcRendererEvent, payload: T): void => listener(payload)
  ipcRenderer.on(channel, wrapped)
  return () => ipcRenderer.removeListener(channel, wrapped)
}

const desktopApi: DesktopApi = {
  getPairingSession: () => ipcRenderer.invoke(IPC.pairing.getSession),
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
  resizeWindowToStream: (width, height) => {
    ipcRenderer.invoke(IPC.window.resizeToStream, { width, height })
  },
  setSessionInfoOverlay: (enabled) => {
    ipcRenderer.invoke(IPC.window.sessionInfoOverlay, enabled)
  },
  onSessionInfoOverlayChanged: (listener) => subscribe<boolean>(IPC.window.sessionInfoOverlay, listener),
  fitWindowToStream: () => {
    ipcRenderer.invoke(IPC.window.fitToStream)
  }
}

contextBridge.exposeInMainWorld('desktopApi', desktopApi)
