import type {
  MobileStateEvent,
  PairingSessionView,
  SignalingIceMessage,
  SignalingSdpMessage
} from './types'

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
  },
  signaling: {
    /** Push: the mobile's SDP offer arrived — the renderer answers it (desktop.md). */
    sdpOffer: 'signaling:sdp-offer',
    /** Push: an ICE candidate arrived from the mobile (null = end-of-gathering). */
    iceCandidate: 'signaling:ice-candidate',
    /** Renderer → main: send the answer for one pc back over the socket. */
    sendSdpAnswer: 'signaling:send-sdp-answer',
    /** Renderer → main: trickle a desktop candidate (null = end-of-gathering) for one pc. */
    sendIceCandidate: 'signaling:send-ice-candidate'
  },
  window: {
    /** Renderer → main: a cast is live/over — cast-window vs waiting-window minimums. */
    castActive: 'window:cast-active',
    /** Renderer → main: reshape the window to the stream's aspect (follows rotation). */
    resizeToStream: 'window:resize-to-stream'
  }
} as const

export interface DesktopApi {
  getPairingSession(): Promise<PairingSessionView>
  regeneratePairingSession(): Promise<PairingSessionView>
  /** Subscribes to session changes; returns an unsubscribe function. */
  onPairingSessionUpdated(listener: (session: PairingSessionView | null) => void): () => void
  /** Subscribes to mobile connection state changes; returns an unsubscribe function. */
  onMobileStateChanged(listener: (state: MobileStateEvent) => void): () => void
  /** Subscribes to inbound SDP offers (mobile is always the offerer — webrtc.md). */
  onSignalingSdpOffer(listener: (message: SignalingSdpMessage) => void): () => void
  /** Subscribes to inbound ICE candidates from the mobile. */
  onSignalingIceCandidate(listener: (message: SignalingIceMessage) => void): () => void
  /** Sends the desktop's SDP answer for one pc over the signaling socket. */
  sendSdpAnswer(pc: SignalingSdpMessage['pc'], sdp: string): void
  /** Trickles one desktop candidate (null = end-of-gathering) for one pc. */
  sendIceCandidate(pc: SignalingIceMessage['pc'], candidate: unknown): void
  /** Tells the main process a cast is live (relaxed window minimums) or over. */
  setCastActive(active: boolean): void
  /** Reshapes the window to a stream's aspect so the video fills it edge-to-edge. */
  resizeWindowToStream(width: number, height: number): void
}
