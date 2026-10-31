import type {
  AudioRoutingMode,
  AudioRoutingReport,
  MobileStateEvent,
  PairingSessionView,
  SignalingIceMessage,
  SignalingSdpMessage,
  TrustedDeviceView,
  VcDesktopState,
  VcPumpCommand,
  VirtualAudioInstallResult,
  VirtualAudioState
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
    fitToStream: 'window:fit-to-stream',
    /**
     * Renderer → main: the frameless preference (ADR-007 addendum). Frame is
     * set at creation, so the main process recreates the window (bounds kept)
     * and pushes the effective state back — same pattern as sessionInfoOverlay.
     */
    frameless: 'window:frameless'
  },
  vc: {
    /**
     * Push: the virtual camera's renderer pump command (ADR-007) — `frames`
     * pumps at the fixed dims, `no-signal` publishes the "No input video"
     * still (Syphon stays up between casts), `off` stops. Also drives the
     * receiver's VC-active placeholder and its toggle state.
     */
    pump: 'vc:pump',
    /** Renderer → main: one packed frame (width×height×{3|4} bytes, top-down). */
    frame: 'vc:frame',
    /** Renderer → main: the pump gave up (e.g. repeated context loss) — stop the camera honestly. */
    pumpFailed: 'vc:pump-failed',
    /** Push: whether this desktop can do the virtual camera (the settings section's visibility). */
    state: 'vc:state',
    /** Renderer → main: the desktop's own toggle (receiver-environment control, ADR-007 addendum). */
    toggle: 'vc:toggle',
    /** Renderer → main / push: the always-on preference — the camera follows the app, capture-card-style. */
    autoStart: 'vc:auto-start'
  },
  audioRouting: {
    /** Renderer → main: the effective routing state changed (mode + cable presence). */
    report: 'audio-routing:report',
    /** Push: the phone asked to change the mode — the renderer applies it. */
    set: 'audio-routing:set'
  },
  virtualAudio: {
    /** Push: whether this desktop can install the Zero-Friction audio devices (ADR-008). */
    state: 'virtual-audio:state',
    /** Renderer → main: run the one-time elevated install (admin prompt on screen). */
    install: 'virtual-audio:install'
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
  /** Sends one packed frame to the virtual camera (ADR-007 frame path). */
  sendVcFrame(data: Uint8Array, width: number, height: number): void
  /** Reports the renderer pump gave up — the main process stops the camera. */
  reportVcPumpFailed(): void
  /** Subscribes to the desktop's virtual camera capability; returns an unsubscribe function. */
  onVcState(listener: (state: VcDesktopState) => void): () => void
  /** The desktop's own virtual camera toggle (receiver-environment control). */
  toggleVirtualCamera(): void
  /** Sets the always-on preference (camera starts with every cast, capture-card-style). */
  setVcAutoStart(enabled: boolean): void
  /** Subscribes to the always-on preference's effective state; returns an unsubscribe function. */
  onVcAutoStartChanged(listener: (enabled: boolean) => void): () => void
  /** Renderer → main: the effective audio routing state changed (mode + cable presence). */
  reportAudioRouting(report: AudioRoutingReport): void
  /** Subscribes to routing sets arriving from the phone; returns an unsubscribe function. */
  onAudioRoutingSet(listener: (mode: AudioRoutingMode) => void): () => void
  /** Subscribes to the virtual audio install capability; returns an unsubscribe function. */
  onVirtualAudioState(listener: (state: VirtualAudioState) => void): () => void
  /** Runs the one-time elevated install of the Zero-Friction audio devices (ADR-008). */
  installVirtualAudio(): Promise<VirtualAudioInstallResult>
  /** Turns the frameless preference on/off (the window is recreated, bounds kept). */
  setFrameless(enabled: boolean): void
  /** Subscribes to the frameless preference's effective state; returns an unsubscribe function. */
  onFramelessChanged(listener: (enabled: boolean) => void): () => void
}
