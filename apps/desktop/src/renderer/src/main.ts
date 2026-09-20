import type { CastSessionInfo, MobileStateEvent, PairingSessionView } from '../../shared/types'

const statusEl = document.getElementById('status') as HTMLParagraphElement
const qrCardEl = document.getElementById('qr-card') as HTMLDivElement
const qrEl = document.getElementById('qr') as HTMLImageElement
const errorEl = document.getElementById('error') as HTMLParagraphElement
const regenerateEl = document.getElementById('regenerate') as HTMLButtonElement

const WAITING_MESSAGE = 'Waiting for mobile device…'
// Friendly, non-technical (AGENTS.md): codes stay in the main-process logs.
const SESSION_ERROR_MESSAGE =
  "Couldn't create a pairing session. Make sure this computer is connected to your Wi-Fi network, then try again."

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
    connectedName = null
    sessionInfo = null
    renderStatus()
  }
}

// "Connected to MacBook — 1280×720 · 30 fps · game audio · mic · balanced"
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

window.addEventListener('beforeunload', () => {
  unsubscribeSession()
  unsubscribeMobile()
})
