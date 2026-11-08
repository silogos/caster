// The receiver's stage machine (port of the Electron receiver's stages):
// QR hero → no-input → receiving. Driven by real events from the Rust core:
//  - pairing:session-updated → QR hero
//  - pairing:mobile-state    → connected/disconnected (no-input stage)
//  - video:jpeg              → receiving stage + the cast preview
// The controls bar carries receiver-only settings (volume, Disconnect).

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
if (!tauri) console.error('window.__TAURI__ missing — events will not arrive')

tauri?.event.listen('pairing:session-updated', (event) => {
  const session = event.payload
  if (!session) return
  qrImg.src = session.qrDataUrl
  app.classList.remove('connected')
  resetDisconnect()
  showStage('waiting')
})

let phoneName = null
let sessionInfo = null

tauri?.event.listen('pairing:mobile-state', (event) => {
  const state = event.payload?.state
  if (state === 'connected') {
    phoneName = event.payload.name ?? 'Android device'
    noInputSubline.textContent = `Connected to ${phoneName} — start casting from your phone.`
    app.classList.add('connected')
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
    // Imperative fill (the Electron fillVideoWindow precedent): never
    // depend on object-fit resolving — pin the element to the exact
    // window pixels every frame, letterboxed via aspect-ratio math.
    if (!frame.style.position) {
      frame.style.position = 'fixed'
      frame.style.inset = '0'
      frame.style.margin = '0'
      frame.style.display = 'block'
      frame.style.background = '#000'
    }
    const img = new Image()
    img.onload = () => {
      const vw = window.innerWidth
      const vh = window.innerHeight
      const scale = Math.min(vw / img.width, vh / img.height)
      frame.style.width = `${Math.round(img.width * scale)}px`
      frame.style.height = `${Math.round(img.height * scale)}px`
      frame.style.left = `${Math.round((vw - img.width * scale) / 2)}px`
      frame.style.top = `${Math.round((vh - img.height * scale) / 2)}px`
      frame.src = event.payload.dataUrl
      showStage('receiving')
    }
    img.src = event.payload.dataUrl
  }
})

// Pull the session on load. The setup-time emit races the webview load
// (no listener attached yet) and is dropped — the QR never showed in-app,
// which is why a Preview PNG was used as a workaround. The command is
// the reliable path: the app window is the only QR surface.
tauri?.core.invoke('pairing_payload').then((session) => {
  if (session?.qrDataUrl) {
    qrImg.src = session.qrDataUrl
    showStage('waiting')
  }
}).catch((err) => console.error('pairing_payload failed:', err))

// ---- receiver controls -------------------------------------------------

const volumeSlider = document.getElementById('game-volume')
const volumeValue = document.getElementById('game-volume-value')
const muteButton = document.getElementById('game-mute')
const disconnectButton = document.getElementById('disconnect')
const newQrButton = document.getElementById('new-qr')
let gameAudio = { gameVolume: 1, gameMuted: false }

function renderGameAudio() {
  volumeSlider.value = String(Math.round(gameAudio.gameVolume * 100))
  volumeValue.textContent = gameAudio.gameMuted ? 'muted' : `${volumeSlider.value}%`
  muteButton.textContent = gameAudio.gameMuted ? 'Unmute' : 'Mute'
}

function applyGameAudio(volume, muted, persist) {
  tauri?.core
    .invoke('set_game_audio', { volume, muted, persist })
    .then((saved) => { gameAudio = saved; renderGameAudio() })
    .catch((err) => console.error('set_game_audio failed:', err))
}

// Live while dragging; saved once on release.
volumeSlider.addEventListener('input', () => applyGameAudio(Number(volumeSlider.value) / 100, false, false))
volumeSlider.addEventListener('change', () => applyGameAudio(Number(volumeSlider.value) / 100, false, true))
muteButton.addEventListener('click', () => applyGameAudio(gameAudio.gameVolume, !gameAudio.gameMuted, true))

tauri?.core.invoke('receiver_settings').then((saved) => { gameAudio = saved; renderGameAudio() })

// Disconnect forgets the phone (it must scan again) — a two-step button
// instead of a dialog, so a stray click cannot end a cast.
const CONFIRM_WINDOW_MS = 4000
let confirmTimer = null
function resetDisconnect() {
  clearTimeout(confirmTimer)
  confirmTimer = null
  disconnectButton.textContent = 'Disconnect'
  disconnectButton.classList.remove('confirming')
}
disconnectButton.addEventListener('click', () => {
  if (confirmTimer === null) {
    disconnectButton.textContent = 'Forget this phone?'
    disconnectButton.classList.add('confirming')
    confirmTimer = setTimeout(resetDisconnect, CONFIRM_WINDOW_MS)
    return
  }
  resetDisconnect()
  tauri?.core.invoke('forget_phone').catch((err) => console.error('forget_phone failed:', err))
})

newQrButton.addEventListener('click', () => {
  tauri?.core.invoke('regenerate_pairing').catch((err) => console.error('regenerate_pairing failed:', err))
})

showStage('waiting')
