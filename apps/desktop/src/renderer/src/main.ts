import type { CastSessionInfo, MobileStateEvent, PairingSessionView } from '../../shared/types'
import { AudioMixer, type MixerChannel, type MixerLevel } from './audio/mixer'
import { ReceiverSession, type PeerConnectionLike } from './webrtc/receiverSession'

const statusEl = document.getElementById('status') as HTMLParagraphElement
const qrCardEl = document.getElementById('qr-card') as HTMLDivElement
const qrEl = document.getElementById('qr') as HTMLImageElement
const errorEl = document.getElementById('error') as HTMLParagraphElement
const hintEl = document.getElementById('hint') as HTMLParagraphElement
const videoEl = document.getElementById('video') as HTMLVideoElement
const mixerEl = document.getElementById('mixer') as HTMLDivElement
const micAudioEl = document.getElementById('mic-audio') as HTMLAudioElement
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

// Phase9's mixer (audio.md): each remote stream → its own GainNode → the one
// AudioContext.destination. The receiver's only allowed audio control — no
// further processing. Levels persist via localStorage; the context is created
// lazily on the first stream (mixer.ts).
const mixer = new AudioMixer({
  createAudioContext: () => new AudioContext(),
  storage: window.localStorage,
  log
})

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
      // The element stays muted (also in the markup): the stream's game
      // audio plays through the mixer now — an unmuted element would play
      // it twice. The muted element also plays under any autoplay policy.
      videoEl.muted = true
      videoEl.play().catch((error) => log('warn', 'autoplay was blocked', { error: String(error) }))
      mixer.attachStream('game', stream)
      mixerEl.hidden = false
      qrCardEl.hidden = true
      hintEl.hidden = true
      regenerateEl.hidden = true
    },
    clear: () => {
      videoEl.srcObject = null
      videoEl.hidden = true
      mixer.detachStream('game')
      mixerEl.hidden = true
      hintEl.hidden = false
      regenerateEl.hidden = false
    }
  },
  // The mic pc's stream (Phase8) lands in the mixer (Phase9) — its audible
  // path is the Web Audio graph. It is ALSO held on a muted <audio> element:
  // not for playback, but as a keep-alive — Chromium stops pulling a
  // MediaStream with no media element when the window is hidden (found live:
  // minimized window → mic silent, game audio fine — the <video> holds it).
  micSink: {
    show: (stream) => {
      micAudioEl.srcObject = stream as MediaStream
      micAudioEl.muted = true
      micAudioEl.play().catch((error) => log('warn', 'mic keep-alive play was blocked', { error: String(error) }))
      mixer.attachStream('mic', stream)
    },
    clear: () => {
      micAudioEl.srcObject = null
      mixer.detachStream('mic')
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

// The mixer panel (StatusView's volume controls — desktop.md): one row per
// channel. Values initialized from persisted levels; every change goes
// through the mixer (which persists it), so the panel and the audio graph
// cannot drift apart.
const VOLUME_SLIDER_MAX = 100

const renderMuteButton = (button: HTMLButtonElement, muted: boolean): void => {
  button.textContent = muted ? 'Unmute' : 'Mute'
  button.setAttribute('aria-pressed', String(muted))
}

const wireMixerChannel = (channel: MixerChannel, sliderId: string, muteButtonId: string): void => {
  const slider = document.getElementById(sliderId) as HTMLInputElement
  const muteButton = document.getElementById(muteButtonId) as HTMLButtonElement
  const level: MixerLevel = mixer.getLevels()[channel]
  slider.value = String(Math.round(level.volume * VOLUME_SLIDER_MAX))
  renderMuteButton(muteButton, level.muted)
  slider.addEventListener('input', () => {
    mixer.setVolume(channel, Number(slider.value) / VOLUME_SLIDER_MAX)
  })
  muteButton.addEventListener('click', () => {
    const muted = !mixer.getLevels()[channel].muted
    mixer.setMuted(channel, muted)
    renderMuteButton(muteButton, muted)
  })
}

wireMixerChannel('game', 'game-volume', 'game-mute')
wireMixerChannel('mic', 'mic-volume', 'mic-mute')

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
