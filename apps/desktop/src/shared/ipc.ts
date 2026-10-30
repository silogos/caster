import type {
  MobileStateEvent,
  PairingSessionView,
  SignalingIceMessage,
  SignalingSdpMessage,
  TrustedDeviceView,
  VcPumpCommand
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
    /** Renderer asks whether a phone is trusted (startup stage, ADR-006). */
    getTrustedDevice: 'pairing:get-trusted-device',
    /** Renderer → main: revoke the trust and return to the QR hero (Disconnect). */
    disconnect: 'pairing:disconnect',
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
    castActive: 'window:cast-active',    /**
     * Renderer → main: the stream's size changed (first metadata, rotation,
     * quality steps). The window's shape is the user's — the video letterboxes
     * via CSS; the size is only cached for the on-demand "Match window to
     * video" reshape (fit).
     */
    streamSize: 'window:stream-size',
    /**
     * Renderer → main: the optional session-info overlay window is wanted or
     * not (the toggle state; main → renderer push carries the effective state
     * back so the checkbox stays in sync with the overlay's own close button).
     */
    sessionInfoOverlay: 'window:session-info-overlay',
    /** Renderer → main: re-fit the window to the current stream's aspect. */
    fitToStream: 'window:fit-to-stream'
  },
  vc: {
    /**
     * Push: the virtual camera's renderer pump command (ADR-007) — start
     * publishing at the fixed camera dims (frames then flow on `frame`), or
     * stop. Also drives the receiver's VC-active placeholder.
     */
    pump: 'vc:pump',
    /** Renderer → main: one packed BGR24 frame (width×height×3 bytes, top-down). */
    frame: 'vc:frame',
    /** Renderer → main: the pump gave up (e.g. repeated context loss) — stop the camera honestly. */
    pumpFailed: 'vc:pump-failed'
  }
} as const

export interface DesktopApi {
  getPairingSession(): Promise<PairingSessionView>
  /** The trusted phone, if any — the startup stage keys on it (ADR-006). */
  getTrustedDevice(): Promise<TrustedDeviceView | null>
  /** Revoke the trusted phone and return to the QR hero (Disconnect button). */
  disconnectDesktop(): void
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
  /** Reports a stream size change (rotation, quality step) — cached for the on-demand fit. */
  reportStreamSize(width: number, height: number): void
  /** Turns the optional off-cast session-info window on or off. */
  setSessionInfoOverlay(enabled: boolean): void
  /** Subscribes to the session-info window's effective state; returns an unsubscribe function. */
  onSessionInfoOverlayChanged(listener: (enabled: boolean) => void): () => void
  /** Re-fits the window to the current stream's aspect (undoes a manual resize). */
  fitWindowToStream(): void
  /** Subscribes to the virtual camera's pump commands; returns an unsubscribe function. */
  onVcPump(listener: (command: VcPumpCommand) => void): () => void
  /** Sends one packed BGR24 frame to the virtual camera (ADR-007 frame path). */
  sendVcFrame(data: Uint8Array, width: number, height: number): void
  /** Reports the renderer pump gave up — the main process stops the camera. */
  reportVcPumpFailed(): void
}
