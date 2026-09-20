import type { MobileStateEvent, PairingSessionView } from '../../shared/types'

const statusEl = document.getElementById('status') as HTMLParagraphElement
const qrCardEl = document.getElementById('qr-card') as HTMLDivElement
const qrEl = document.getElementById('qr') as HTMLImageElement
const errorEl = document.getElementById('error') as HTMLParagraphElement
const regenerateEl = document.getElementById('regenerate') as HTMLButtonElement

const WAITING_MESSAGE = 'Waiting for mobile device…'
// Friendly, non-technical (AGENTS.md): codes stay in the main-process logs.
const SESSION_ERROR_MESSAGE =
  "Couldn't create a pairing session. Make sure this computer is connected to your Wi-Fi network, then try again."

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
    statusEl.textContent = `Connected to ${state.name}`
    qrCardEl.hidden = true
    errorEl.hidden = true
  } else {
    statusEl.textContent = WAITING_MESSAGE
  }
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
