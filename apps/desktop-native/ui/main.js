// The receiver's stage machine (port of the Electron receiver's stages):
// QR hero → no-input → receiving. Driven by real events from the Rust core:
//  - pairing:session-updated → QR hero
//  - pairing:mobile-state    → connected/disconnected (no-input stage)
//  - video:jpeg              → receiving stage + the cast preview
// Keys 1/2/3 remain for visual verification without a phone.

const stages = {
  waiting: document.getElementById('stage-waiting'),
  noinput: document.getElementById('stage-no-input'),
  receiving: document.getElementById('stage-receiving'),
}
const app = document.getElementById('app')
const qrImg = document.getElementById('qr-img')
const noInputSubline = document.getElementById('no-input-subline')
const statusChip = document.getElementById('status-chip')
const frame = document.getElementById('video-frame')

function showStage(stage) {
  for (const [name, el] of Object.entries(stages)) {
    el.classList.toggle('hidden', name !== stage)
  }
  app.classList.toggle('receiving', stage === 'receiving')
}

// Session-info line — the exact separators of the Electron receiver
// (sessionInfoLine.ts): × U+00D7, · U+00B7, — U+2014.
function sessionInfoLine(name, info) {
  if (!name) return ''
  if (!info) return `Connected to ${name}`
  const sources = [info.gameAudio && 'game audio', info.mic && 'mic'].filter(Boolean).join(' · ')
  const parts = [`${info.width}×${info.height}`, `${info.fps} fps`, info.profile, sources].filter(Boolean)
  return `Connected to ${name} — ${parts.join(' · ')}`
}

const tauri = window.__TAURI__ ?? null

tauri?.event.listen('pairing:session-updated', (event) => {
  const session = event.payload
  if (!session) return
  qrImg.src = session.qrDataUrl
  showStage('waiting')
})

let phoneName = null
let sessionInfo = null

tauri?.event.listen('pairing:mobile-state', (event) => {
  const state = event.payload?.state
  if (state === 'connected') {
    phoneName = event.payload.name ?? 'Android device'
    noInputSubline.textContent = `Connected to ${phoneName} — start casting from your phone.`
    showStage('noinput')
  } else if (state === 'disconnected') {
    noInputSubline.textContent = `Waiting for ${phoneName ?? 'the phone'} to reconnect…`
    showStage('noinput')
  } else if (state === 'session-info') {
    sessionInfo = event.payload.info ?? sessionInfo
    statusChip.textContent = sessionInfoLine(phoneName, sessionInfo)
  }
})

tauri?.event.listen('video:jpeg', (event) => {
  if (event.payload?.dataUrl) {
    frame.src = event.payload.dataUrl
    showStage('receiving')
  }
})

// Dev stage keys — 1 waiting, 2 no-input, 3 receiving.
window.addEventListener('keydown', (event) => {
  if (event.key === '1') showStage('waiting')
  if (event.key === '2') {
    noInputSubline.textContent = 'Waiting for TB321FU to reconnect…'
    showStage('noinput')
  }
  if (event.key === '3') {
    statusChip.textContent = sessionInfoLine('TB321FU', {
      width: 1280, height: 720, fps: 30, profile: 'balanced', gameAudio: true, mic: false,
    })
    showStage('receiving')
  }
})

showStage('waiting')
