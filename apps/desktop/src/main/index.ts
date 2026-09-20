import { app, BrowserWindow, ipcMain } from 'electron'
import { IPC } from '../shared/ipc'
import { createMainWindow } from './window'
import { buildStaticSession } from './pairing/staticSession'

// All cast configuration lives on the mobile app; this process owns sockets,
// sessions, and the window (docs/architecture/desktop.md).

app.whenReady().then(() => {
  ipcMain.handle(IPC.pairing.getSession, () => buildStaticSession())

  createMainWindow()

  app.on('activate', () => {
    // macOS: re-create the window when the dock icon is clicked with no windows.
    if (BrowserWindow.getAllWindows().length === 0) {
      createMainWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
