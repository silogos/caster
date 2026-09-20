import type { MobileStateEvent, PairingSessionView } from './types'

/**
 * Typed IPC contract. The main process owns sockets and sessions; the renderer
 * owns WebRTC and media (docs/architecture/desktop.md). IPC carries only typed
 * events — this module is the single place where channel names are defined.
 */
export const IPC = {
  pairing: {
    /** Renderer asks the main process for the current pairing session. */
    getSession: 'pairing:get-session',
    /** Renderer asks the main process to regenerate the session (new QR). */
    regenerate: 'pairing:regenerate',
    /** Push: the pairing session changed (fresh QR), or null when creation failed. */
    sessionUpdated: 'pairing:session-updated',
    /** Push: the paired mobile's connection state changed. */
    mobileState: 'pairing:mobile-state'
  }
} as const

export interface DesktopApi {
  getPairingSession(): Promise<PairingSessionView>
  regeneratePairingSession(): Promise<PairingSessionView>
  /** Subscribes to session changes; returns an unsubscribe function. */
  onPairingSessionUpdated(listener: (session: PairingSessionView | null) => void): () => void
  /** Subscribes to mobile connection state changes; returns an unsubscribe function. */
  onMobileStateChanged(listener: (state: MobileStateEvent) => void): () => void
}
