// The receiver's main window — the mobile app's "Signal Deck" look:
//  - QR hero while no phone is paired;
//  - the deck once one is: source, ON AIR / STANDBY, the cast's status, and
//    "Open video", which opens the native video window. Video AND sound play
//    only while that window is open (the phone keeps sending either way).
// Events from the Rust core:
//  - pairing:session-updated → QR hero
//  - pairing:mobile-state    → connected / disconnected / session-info / forgotten
//  - cast:state {live}       → ON AIR + timecode
//  - video:window {open}     → the Open/Close video button
// Only receiver/environment controls live here — cast settings are the phone's.

const tauri = window.__TAURI__ ?? null
if (!tauri) console.error('window.__TAURI__ missing — events will not arrive')
const invoke = (cmd, args) => (tauri ? tauri.core.invoke(cmd, args) : Promise.reject(new Error('no tauri')))
const $ = (id) => document.getElementById(id)

const stages = { waiting: $('stage-waiting'), deck: $('stage-deck') }
function showStage(stage) {
  for (const [name, el] of Object.entries(stages)) el.classList.toggle('hidden', name !== stage)
}

// ---- deck state ------------------------------------------------------------

const state = {
  phone: null,
  link: 'connected', // 'connected' | 'reconnecting'
  live: false,
  liveSince: 0,
  info: null,
  videoOpen: false,
}

function pad(n) {
  return String(n).padStart(2, '0')
}

function timecode(ms) {
  const s = Math.floor(ms / 1000)
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
}

/** "1920×1200 · 60 fps · balanced" — the phone's own session-info values. */
function readout(info) {
  if (!info) return 'Live'
  return [`${info.width}×${info.height}`, `${info.fps} fps`, info.profile].filter(Boolean).join(' · ')
}

function renderDeck() {
  $('phone-name').textContent = state.phone ?? 'Android device'

  const ann = $('annunciator')
  ann.className = 'annunciator'
  if (state.link === 'reconnecting') {
    ann.textContent = 'RECONNECTING'
    ann.classList.add('reconnecting')
  } else if (state.live) {
    ann.textContent = 'ON AIR'
    ann.classList.add('on-air')
  } else {
    ann.textContent = 'STANDBY'
  }

  const pill = $('status-pill')
  pill.className = 'pill'
  if (state.link === 'reconnecting') {
    pill.textContent = `Waiting for ${state.phone ?? 'the phone'} to reconnect…`
    pill.classList.add('reconnecting')
  } else if (state.live) {
    pill.textContent = `Casting · ${readout(state.info)}`
    pill.classList.add('live')
  } else {
    pill.textContent = 'Connected — start casting from your phone'
    pill.classList.add('connected')
  }

  $('readout').textContent = state.live ? readout(state.info) : 'Waiting for the cast'
  $('timecode').classList.toggle('idle', !state.live)
  if (!state.live) $('timecode').textContent = '00:00:00'
  $('chip-game').classList.toggle('on', state.live && state.info?.gameAudio === true)
  $('chip-mic').classList.toggle('on', state.live && state.info?.mic === true)

  const toggle = $('video-toggle')
  toggle.textContent = state.videoOpen ? 'Close video' : 'Open video'
  toggle.className = `btn ${state.videoOpen ? 'btn-secondary' : 'btn-primary'}`
  $('game-sub').textContent = state.videoOpen
    ? 'Playing on this computer.'
    : 'Plays while the video window is open.'
}

setInterval(() => {
  if (state.live) $('timecode').textContent = timecode(Date.now() - state.liveSince)
}, 1000)

// ---- events ----------------------------------------------------------------

tauri?.event.listen('pairing:session-updated', (event) => {
  if (!event.payload) return
  $('qr-img').src = event.payload.qrDataUrl
  resetDisconnect()
  showStage('waiting')
})

tauri?.event.listen('pairing:mobile-state', (event) => {
  const s = event.payload?.state
  if (s === 'connected') {
    state.phone = event.payload.name ?? 'Android device'
    state.link = 'connected'
    state.info = null
    showStage('deck')
  } else if (s === 'disconnected') {
    state.link = 'reconnecting'
    state.live = false
  } else if (s === 'session-info') {
    state.info = event.payload.info ?? state.info
  } else if (s === 'forgotten') {
    Object.assign(state, { phone: null, link: 'connected', live: false, info: null })
  }
  renderDeck()
})

tauri?.event.listen('cast:state', (event) => {
  const live = event.payload?.live === true
  if (live && !state.live) state.liveSince = Date.now()
  state.live = live
  renderDeck()
})

tauri?.event.listen('video:window', (event) => {
  state.videoOpen = event.payload?.open === true
  renderDeck()
})

// ---- controls ----------------------------------------------------------------

$('video-toggle').addEventListener('click', () => {
  invoke(state.videoOpen ? 'close_video' : 'open_video').catch((err) => console.error('video toggle failed:', err))
})

let gameAudio = { gameVolume: 1, gameMuted: false }
function renderGameAudio() {
  $('game-volume').value = String(Math.round(gameAudio.gameVolume * 100))
  $('game-volume-value').textContent = gameAudio.gameMuted ? 'off' : `${$('game-volume').value}%`
  $('game-switch').classList.toggle('on', !gameAudio.gameMuted)
}
function applyGameAudio(volume, muted, persist) {
  invoke('set_game_audio', { volume, muted, persist })
    .then((saved) => { gameAudio = saved; renderGameAudio() })
    .catch((err) => console.error('set_game_audio failed:', err))
}
// Live while dragging; saved once on release.
$('game-volume').addEventListener('input', () => applyGameAudio(Number($('game-volume').value) / 100, false, false))
$('game-volume').addEventListener('change', () => applyGameAudio(Number($('game-volume').value) / 100, false, true))
$('game-switch').addEventListener('click', () => applyGameAudio(gameAudio.gameVolume, !gameAudio.gameMuted, true))

// The Syphon output for OBS: a receiver control, saved with the others.
let syphonOutput = false
function renderSyphon() {
  $('syphon-switch').classList.toggle('on', syphonOutput)
  $('syphon-sub').textContent = syphonOutput
    ? 'On. In OBS add a Syphon Client source named "Zero-Friction Local Cast". It keeps working with the video window closed.'
    : 'Off. OBS can still capture the video window.'
}
$('syphon-switch').addEventListener('click', () => {
  invoke('set_syphon_output', { enabled: !syphonOutput })
    .then((saved) => { syphonOutput = saved.syphonOutput === true; renderSyphon() })
    .catch((err) => console.error('set_syphon_output failed:', err))
})

invoke('receiver_settings')
  .then((saved) => {
    gameAudio = saved
    syphonOutput = saved.syphonOutput === true
    renderGameAudio()
    renderSyphon()
  })
  .catch(() => {})

// Disconnect forgets the phone (it must scan again) — a two-step link
// instead of a dialog, so a stray click cannot end a cast.
const CONFIRM_WINDOW_MS = 4000
let confirmTimer = null
function resetDisconnect() {
  clearTimeout(confirmTimer)
  confirmTimer = null
  $('disconnect').textContent = 'Disconnect'
  $('disconnect').classList.remove('confirming')
}
$('disconnect').addEventListener('click', () => {
  if (confirmTimer === null) {
    $('disconnect').textContent = 'Forget this phone?'
    $('disconnect').classList.add('confirming')
    confirmTimer = setTimeout(resetDisconnect, CONFIRM_WINDOW_MS)
    return
  }
  resetDisconnect()
  invoke('forget_phone').catch((err) => console.error('forget_phone failed:', err))
})

$('new-qr').addEventListener('click', () => {
  invoke('regenerate_pairing').catch((err) => console.error('regenerate_pairing failed:', err))
})

// Pull the session on load: the setup-time emit can race the webview load.
invoke('pairing_payload')
  .then((session) => { if (session?.qrDataUrl) $('qr-img').src = session.qrDataUrl })
  .catch((err) => console.error('pairing_payload failed:', err))

renderGameAudio()
renderDeck()
showStage('waiting')
