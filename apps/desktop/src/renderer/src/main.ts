import type { CastSessionInfo, MobileStateEvent, PairingSessionView } from '../../shared/types'
import { AudioMixer, type MixerChannel, type MixerLevel } from './audio/mixer'
import { heroView } from './pairingHero'
import { sessionInfoLine } from './sessionInfoLine'
import { ReceiverSession, type PeerConnectionLike } from './webrtc/receiverSession'

const statusEl = document.getElementById('status') as HTMLParagraphElement
const heroHeadingEl = document.getElementById('hero-heading') as HTMLHeadingElement
const iconCheckEl = document.getElementById('icon-check') as HTMLDivElement
const iconWarningEl = document.getElementById('icon-warning') as HTMLDivElement
const qrCardEl = document.getElementById('qr-card') as HTMLDivElement
const qrEl = document.getElementById('qr') as HTMLImageElement
const hintEl = document.getElementById('hint') as HTMLParagraphElement
const videoEl = document.getElementById('video') as HTMLVideoElement
const micAudioEl = document.getElementById('mic-audio') as HTMLAudioElement
const regenerateEl = document.getElementById('regenerate') as HTMLButtonElement
// The cast state's UI (review-time restructure): a hover-revealed overlay
// (status + the settings trigger) over a full-window video, and the settings
// modal holding the mixer.
const castOverlayEl = document.getElementById('cast-overlay') as HTMLDivElement
const castStatusEl = document.getElementById('cast-status') as HTMLParagraphElement
const openSettingsEl = document.getElementById('open-settings') as HTMLButtonElement
const settingsBackdropEl = document.getElementById('settings-backdrop') as HTMLDivElement
const closeSettingsEl = document.getElementById('close-settings') as HTMLButtonElement
// Phase14 receiver-window controls (window behavior — the receiver's own
// domain, never a cast setting): the off-cast session-info window's toggle,
// and the one-click re-fit after a manual resize.
const sessionInfoOverlayEl = document.getElementById('session-info-overlay') as HTMLInputElement
const fitWindowEl = document.getElementById('fit-window') as HTMLButtonElement

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
      // `body.receiving` (renderer.css): the video takes over the window and
      // letterboxes into it (`contain` — the user's chosen window shape is
      // final); the stage's waiting layout disappears; the hover overlay
      // appears. The window relaxes its minimums so portrait streams fit a
      // small window too.
      document.body.classList.add('receiving')
      castOverlayEl.hidden = false
      window.desktopApi.setCastActive(true)
    },
    clear: () => {
      videoEl.srcObject = null
      videoEl.hidden = true
      mixer.detachStream('game')
      document.body.classList.remove('receiving')
      castOverlayEl.hidden = true
      hideSettings()
      window.desktopApi.setCastActive(false)
      // The pairing hero reappears (the mobile-state 'waiting' event clears
      // the paired name; body.receiving has been hiding the whole stage).
      renderHero()
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

// The stream's size (first metadata, every rotation, quality steps) feeds the
// main process's cache — the on-demand "Match window to video" reshape uses
// it. The window itself is the user's canvas: the video letterboxes via CSS
// (`contain` — full width or full height, black bars for the rest) and never
// reshapes the window (found live in the Phase14 session: the auto-reshape
// fought the user's own window sizing, especially for a portrait stream).
videoEl.addEventListener('resize', () => {
  if (videoEl.videoWidth === 0 || videoEl.videoHeight === 0) return
  window.desktopApi.reportStreamSize(videoEl.videoWidth, videoEl.videoHeight)
})

let connectedName: string | null = null
let sessionInfo: CastSessionInfo | null = null
// No LAN IP / session generation failed — the hero shows the friendly error
// until a session (re)appears or the user retries via the regenerate button.
let pairingSessionError = false

// The pairing stage's waiting/connected/error screens (Phase13): one pure
// view model (pairingHero.ts) decides what the user sees; this applies it.
function renderHero(): void {
  const view = heroView(connectedName, pairingSessionError)
  qrCardEl.hidden = !view.showQr
  iconCheckEl.hidden = view.icon !== 'check'
  iconWarningEl.hidden = view.icon !== 'warning'
  heroHeadingEl.textContent = view.heading
  statusEl.textContent = view.subline
  statusEl.classList.toggle('is-error', view.icon === 'warning')
  hintEl.hidden = !view.showHint
  regenerateEl.hidden = !view.showRegenerate
  regenerateEl.textContent = view.regenerateLabel
}

function showSession(session: PairingSessionView): void {
  pairingSessionError = false
  qrEl.src = session.qrDataUrl
  renderHero()
}

function showSessionError(): void {
  pairingSessionError = true
  renderHero()
}

function showMobileState(state: MobileStateEvent): void {
  if (state.state === 'connected') {
    connectedName = state.name
    sessionInfo = null
    renderHero()
    renderStatus()
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
    renderHero()
    renderStatus()
  }
}

// "Connected to Pixel8 — 1280×720 · 30 fps · balanced · game audio · mic"
// — the hover overlay's line over the video while receiving, and the same
// line the off-cast session-info window shows (sessionInfoLine.ts). The
// pairing stage's own status is owned by renderHero (Phase13); the stage is
// hidden during a cast anyway.
function renderStatus(): void {
  castStatusEl.textContent = sessionInfoLine(connectedName, sessionInfo)
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

// The settings modal (receiver/environment controls only — overview.md):
// opened from the hover overlay's trigger; closed via the button, Escape,
// or a click on the backdrop outside the dialog.
function hideSettings(): void {
  settingsBackdropEl.hidden = true
  if (!castOverlayEl.hidden) openSettingsEl.focus()
}

openSettingsEl.addEventListener('click', () => {
  settingsBackdropEl.hidden = false
  closeSettingsEl.focus()
})
closeSettingsEl.addEventListener('click', hideSettings)
settingsBackdropEl.addEventListener('click', (event) => {
  if (event.target === settingsBackdropEl) hideSettings()
})
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && !settingsBackdropEl.hidden) hideSettings()
})

// The off-cast session-info window's preference (Phase14): persisted like the
// mixer levels (renderer localStorage — per-receiver-machine trivia, desktop.md
// process model) and owned by the main process at runtime, because the window
// is created at cast start. The pushed state keeps this checkbox honest when
// the overlay itself is closed via its ✕. Missing/corrupt storage degrades to
// off — a second window must never surprise anyone.
const SESSION_INFO_OVERLAY_KEY = 'zfc.session-info-overlay.v1'
const storedOverlayEnabled = window.localStorage.getItem(SESSION_INFO_OVERLAY_KEY) === 'true'
sessionInfoOverlayEl.checked = storedOverlayEnabled
window.desktopApi.setSessionInfoOverlay(storedOverlayEnabled)

sessionInfoOverlayEl.addEventListener('change', () => {
  window.localStorage.setItem(SESSION_INFO_OVERLAY_KEY, String(sessionInfoOverlayEl.checked))
  window.desktopApi.setSessionInfoOverlay(sessionInfoOverlayEl.checked)
})

const unsubscribeOverlay = window.desktopApi.onSessionInfoOverlayChanged((enabled) => {
  sessionInfoOverlayEl.checked = enabled
  window.localStorage.setItem(SESSION_INFO_OVERLAY_KEY, String(enabled))
})

// One click back to edge-to-edge after a manual window resize (Phase14): the
// same reshape the cast start/rotation path applies, re-run on demand.
fitWindowEl.addEventListener('click', () => {
  window.desktopApi.fitWindowToStream()
})

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
  unsubscribeOverlay()
})
