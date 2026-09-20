import type { PairingSessionView } from './types'

/**
 * Typed IPC contract. The main process owns sockets and sessions; the renderer
 * owns WebRTC and media (docs/architecture/desktop.md). IPC carries only typed
 * events — this module is the single place where channel names are defined.
 */
export const IPC = {
  pairing: {
    /** Renderer asks the main process for the current pairing session. */
    getSession: 'pairing:get-session'
  }
} as const

export interface DesktopApi {
  getPairingSession(): Promise<PairingSessionView>
}
