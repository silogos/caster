import type { PairingSessionView } from '../../shared/types'

const statusEl = document.getElementById('status') as HTMLParagraphElement
const qrCardEl = document.getElementById('qr-card') as HTMLDivElement
const qrEl = document.getElementById('qr') as HTMLImageElement
const errorEl = document.getElementById('error') as HTMLParagraphElement

async function showPairingSession(session: PairingSessionView): Promise<void> {
  qrEl.src = session.qrDataUrl
  qrCardEl.hidden = false
}

function showError(message: string): void {
  statusEl.hidden = true
  qrCardEl.hidden = true
  errorEl.textContent = message
  errorEl.hidden = false
}

window.desktopApi
  .getPairingSession()
  .then(showPairingSession)
  .catch(() => showError("Couldn't create a pairing session. Please restart the app."))
