import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'
import { IPC } from '../shared/ipc'
import type { DesktopApi } from '../shared/ipc'
import type { MobileStateEvent, PairingSessionView } from '../shared/types'

const desktopApi: DesktopApi = {
  getPairingSession: () => ipcRenderer.invoke(IPC.pairing.getSession),
  regeneratePairingSession: () => ipcRenderer.invoke(IPC.pairing.regenerate),
  onPairingSessionUpdated: (listener) => {
    const wrapped = (_event: IpcRendererEvent, session: PairingSessionView | null): void => listener(session)
    ipcRenderer.on(IPC.pairing.sessionUpdated, wrapped)
    return () => ipcRenderer.removeListener(IPC.pairing.sessionUpdated, wrapped)
  },
  onMobileStateChanged: (listener) => {
    const wrapped = (_event: IpcRendererEvent, state: MobileStateEvent): void => listener(state)
    ipcRenderer.on(IPC.pairing.mobileState, wrapped)
    return () => ipcRenderer.removeListener(IPC.pairing.mobileState, wrapped)
  }
}

contextBridge.exposeInMainWorld('desktopApi', desktopApi)
