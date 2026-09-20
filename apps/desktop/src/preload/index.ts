import { contextBridge, ipcRenderer } from 'electron'
import { IPC } from '../shared/ipc'
import type { DesktopApi } from '../shared/ipc'

const desktopApi: DesktopApi = {
  getPairingSession: () => ipcRenderer.invoke(IPC.pairing.getSession)
}

contextBridge.exposeInMainWorld('desktopApi', desktopApi)
