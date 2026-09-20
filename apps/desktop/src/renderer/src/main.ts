import type { CastSessionInfo, MobileStateEvent, PairingSessionView } from '../../shared/types'
import { ReceiverSession, type PeerConnectionLike } from './webrtc/receiverSession'

const statusEl = document.getElementById('status') as HTMLParagraphElement
const qrCardEl = document.getElementById('qr-card') as HTMLDivElement
const qrEl = document.getElementById('qr') as HTMLImageElement
const errorEl = document.getElementById('error') as HTMLParagraphElement
const hintEl = document.getElementById('hint') as HTMLParagraphElement
const videoEl = document.getElementById('video') as HTMLVideoElement
const regenerateEl = document.getElementById('regenerate') as HTMLButtonElement

const WAITING_MESSAGE = 'Waiting for mobile device…'
// Friendly, non-technical (AGENTS.md): codes stay in the main-process logs.
const SESSION_ERROR_MESSAGE =
  "Couldn't create a pairing session. Make sure this computer is connected to your Wi-Fi network, then try again."

// Renderer-side structured logging: the main process has src/main/log.ts; these
// lines go to the devtools console with the same level-tagged shape.
const log = (level: 'debug' | 'info' | 'warn' | 'error', message: string, details?: Record<string, unknown>): void => {
  const fn = console[level]
  if (details === undefined) {
    fn(`[webrtc] ${message}`)
  } else {
    fn(`[webrtc] ${message}`, JSON.stringify(details))
  }
}

// Chromium provides the WebRTC stack here (desktop.md). iceServers: [] — host
// candidates only, no STUN/TURN (webrtc.md: LAN only). The cast is structural:
// only the DOM-lib handler signatures differ from PeerConnectionLike.
const createPeerConnection = (): PeerConnectionLike =>
  new RTCPeerConnection({ iceServers: [] }) as unknown as PeerConnectionLike

// The ReceiverSession and its <video> sink (the VideoView — desktop.md).
const receiver = new ReceiverSession({
  createPeerConnection,
  signaling: {
    sendSdpAnswer: (pc, sdp) => window.desktopApi.sendSdpAnswer(pc, sdp),
    sendIceCandidate: (pc, candidate) => window.desktopApi.sendIceCandidate(pc, candidate)
  },
  sink: {
    show: (stream) => {
      videoEl.srcObject = stream as MediaStream
      videoEl.hidden = false
      // Belt and braces next to the unmuted markup: the stream carries the
      // phone's game audio too (Phase7), and it plays through this element.
      videoEl.muted = false
      videoEl.play().catch((error) => log('warn', 'autoplay was blocked', { error: String(error) }))
      qrCardEl.hidden = true
      hintEl.hidden = true
      regenerateEl.hidden = true
    },
    clear: () => {
      videoEl.srcObject = null
      videoEl.hidden = true
      hintEl.hidden = false
      regenerateEl.hidden = false
    }
  },
  log
})

let connectedName: string | null = null
let sessionInfo: CastSessionInfo | null = null

function showSession(session: PairingSessionView): void {
  errorEl.hidden = true
  qrEl.src = session.qrDataUrl
  qrCardEl.hidden = false
}

function showSessionError(): void {
  statusEl.textContent = WAITING_MESSAGE
  qrCardEl.hidden = true
  errorEl.textContent = SESSION_ERROR_MESSAGE
  errorEl.hidden = false
}

function showMobileState(state: MobileStateEvent): void {
  if (state.state === 'connected') {
    connectedName = state.name
    sessionInfo = null
    renderStatus()
    qrCardEl.hidden = true
    errorEl.hidden = true
  } else if (state.state === 'session-info') {
    // Display-only summary from the mobile (webrtc.md) — never acted on.
    sessionInfo = state.info
    renderStatus()
  } else {
    // 'waiting': the mobile is gone — the cast is over even if the socket
    // later reconnects (a returning mobile always sends a fresh offer).
    connectedName = null
    sessionInfo = null
    receiver.handleMobileGone()
    renderStatus()
  }
}

// "Connected to Pixel 8 — 1280×720 · 30 fps · balanced · game audio · mic"
function renderStatus(): void {
  if (connectedName === null) {
    statusEl.textContent = WAITING_MESSAGE
    return
  }
  const info = sessionInfo
  if (info === null) {
    statusEl.textContent = `Connected to ${connectedName}`
    return
  }
  const sources = [info.gameAudio ? 'game audio' : null, info.mic ? 'mic' : null].filter(Boolean).join(' · ')
  const parts = [`${info.width}×${info.height}`, `${info.fps} fps`, info.profile, sources].filter(Boolean)
  statusEl.textContent = `Connected to ${connectedName} — ${parts.join(' · ')}`
}

function enableRegenerate(enabled: boolean): void {
  regenerateEl.disabled = !enabled
}

regenerateEl.addEventListener('click', () => {
  enableRegenerate(false)
  window.desktopApi
    .regeneratePairingSession()
    .then(showSession)
    .catch(showSessionError)
    .finally(() => enableRegenerate(true))
})

window.desktopApi
  .getPairingSession()
  .then((session) => (session === null ? showSessionError() : showSession(session)))
  .catch(showSessionError)

const unsubscribeSession = window.desktopApi.onPairingSessionUpdated((session) =>
  session === null ? showSessionError() : showSession(session)
)
const unsubscribeMobile = window.desktopApi.onMobileStateChanged(showMobileState)
const unsubscribeOffers = window.desktopApi.onSignalingSdpOffer((offer) => {
  void receiver.handleSdpOffer(offer)
})
const unsubscribeIce = window.desktopApi.onSignalingIceCandidate((candidate) => {
  void receiver.handleIceCandidate(candidate)
})

window.addEventListener('beforeunload', () => {
  receiver.close()
  unsubscribeSession()
  unsubscribeMobile()
  unsubscribeOffers()
  unsubscribeIce()
})
