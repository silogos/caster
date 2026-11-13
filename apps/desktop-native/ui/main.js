// The receiver's main window — the mobile app's "Signal Deck" look:
//  - QR hero while no phone is paired;
//  - the deck once one is: source, ON AIR / STANDBY, the cast's status, and
//    "Open video", which opens the native video window. Video plays only while
//    that window is open; sound while it is open or the Syphon output is on
//    (ADR-029), on the device chosen under "Plays on".
// Events from the Rust core:
//  - pairing:session-updated → QR hero
//  - pairing:mobile-state    → connected / disconnected / session-info / forgotten
//  - cast:state {live}       → ON AIR + timecode
//  - cast:picture {width,height,fps} | null → the live readout: the size and frame rate that arrive
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
  picture: null, // what the receiver is getting, measured once a second
  videoOpen: false,
  syphonOutput: false, // the receiver's Syphon switch (ADR-021)
}

function pad(n) {
  return String(n).padStart(2, '0')
}

function timecode(ms) {
  const s = Math.floor(ms / 1000)
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
}

/** "1920×1200 · 60 fps · balanced": the cast as the phone set it (its session-info). */
function settingsLine(info) {
  if (!info) return 'Live'
  return [`${info.width}×${info.height}`, `${info.fps} fps`, info.profile].filter(Boolean).join(' · ')
}

/**
 * "640×400 · 59 fps": the picture that is arriving right now. The sender
 * scales the frame and drops frames under pressure, so it is not always
 * what the phone set. Until the first measured second: the settings.
 */
function liveLine(info, picture) {
  if (!picture) return settingsLine(info)
  return `${picture.width}×${picture.height} · ${picture.fps} fps`
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
    pill.textContent = `Casting · ${settingsLine(state.info)}`
    pill.classList.add('live')
  } else {
    pill.textContent = 'Connected — start casting from your phone'
    pill.classList.add('connected')
  }

  $('readout').textContent = state.live ? liveLine(state.info, state.picture) : 'Waiting for the cast'
  $('timecode').classList.toggle('idle', !state.live)
  if (!state.live) $('timecode').textContent = '00:00:00'
  $('chip-game').classList.toggle('on', state.live && state.info?.gameAudio === true)
  $('chip-mic').classList.toggle('on', state.live && state.info?.mic === true)

  const toggle = $('video-toggle')
  toggle.textContent = state.videoOpen ? 'Close video' : 'Open video'
  toggle.className = `btn ${state.videoOpen ? 'btn-secondary' : 'btn-primary'}`
  renderGameSub()
}

// Sound plays while something shows the cast: the window or Syphon (ADR-029).
function renderGameSub() {
  $('game-sub').textContent = state.videoOpen || state.syphonOutput
    ? 'Playing on this computer.'
    : 'Plays while the video window is open or Syphon is on.'
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

tauri?.event.listen('cast:picture', (event) => {
  state.picture = event.payload ?? null
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

// The two tracks the receiver plays, each with its own level, mute and
// output (ADR-003: never mixed; ADR-029: "Plays on").
const tracks = {
  audio: { id: 'game', command: 'set_game_audio', volume: 'gameVolume', muted: 'gameMuted', playsOn: 'gamePlaysOn', device: 'Caster Audio' },
  mic: { id: 'mic', command: 'set_mic_audio', volume: 'micVolume', muted: 'micMuted', playsOn: 'micPlaysOn', device: 'Caster Mic' },
}
let saved = { gameVolume: 1, gameMuted: false, gamePlaysOn: 'speaker', micVolume: 1, micMuted: true, micPlaysOn: 'speaker' }
let casterDevices = { audio: null, mic: null } // the installed virtual devices, null = not installed

function renderTrack(name) {
  const t = tracks[name]
  $(`${t.id}-volume`).value = String(Math.round(saved[t.volume] * 100))
  $(`${t.id}-volume-value`).textContent = saved[t.muted] ? 'off' : `${$(`${t.id}-volume`).value}%`
  $(`${t.id}-switch`).classList.toggle('on', !saved[t.muted])
  const installed = casterDevices[name]
  for (const button of $(`${t.id}-plays-on`).querySelectorAll('button')) {
    const on = button.dataset.playsOn === saved[t.playsOn]
    button.classList.toggle('on', on)
    button.setAttribute('aria-checked', String(on))
    button.disabled = button.dataset.playsOn === 'caster' && !installed
  }
  // Until the Caster devices ship with the app, the Electron receiver's
  // devices stand in (ADR-029) — name the one OBS will list.
  $(`${t.id}-plays-on-note`).textContent = !installed
    ? `${t.device} isn't installed on this Mac.`
    : installed !== t.device ? `In OBS: "${installed}".` : ''
}
function renderTracks() {
  renderTrack('audio')
  renderTrack('mic')
}
function applyLevel(name, volume, muted, persist) {
  invoke(tracks[name].command, { volume, muted, persist })
    .then((next) => { saved = next; renderTracks() })
    .catch((err) => console.error(`${tracks[name].command} failed:`, err))
}
for (const name of Object.keys(tracks)) {
  const t = tracks[name]
  const level = () => Number($(`${t.id}-volume`).value) / 100
  // Live while dragging; saved once on release.
  $(`${t.id}-volume`).addEventListener('input', () => applyLevel(name, level(), false, false))
  $(`${t.id}-volume`).addEventListener('change', () => applyLevel(name, level(), false, true))
  $(`${t.id}-switch`).addEventListener('click', () => applyLevel(name, saved[t.volume], !saved[t.muted], true))
  $(`${t.id}-plays-on`).addEventListener('click', (event) => {
    const playsOn = event.target.closest('button')?.dataset.playsOn
    if (!playsOn || playsOn === saved[t.playsOn]) return
    invoke('set_plays_on', { track: name, playsOn })
      .then((next) => { saved = next; renderTracks() })
      .catch((err) => { console.error('set_plays_on failed:', err); loadCasterDevices() })
  })
}
// Re-read when this window gains focus: a device installed meanwhile.
function loadCasterDevices() {
  invoke('caster_devices')
    .then((devices) => { casterDevices = devices; renderTracks(); renderSyphon() })
    .catch((err) => console.error('caster_devices failed:', err))
}
window.addEventListener('focus', loadCasterDevices)

// The Syphon output for OBS: a receiver control, saved with the others.
function renderSyphon() {
  $('syphon-switch').classList.toggle('on', state.syphonOutput)
  $('syphon-sub').textContent = state.syphonOutput
    ? `On. In OBS add a Syphon Client source named "Caster", and Audio Input Captures of "${casterDevices.audio ?? 'Caster Audio'}" and "${casterDevices.mic ?? 'Caster Mic'}".`
    : 'Off. OBS can still capture the video window.'
  renderGameSub()
}
$('syphon-switch').addEventListener('click', () => {
  invoke('set_syphon_output', { enabled: !state.syphonOutput })
    .then((saved) => { state.syphonOutput = saved.syphonOutput === true; renderSyphon() })
    .catch((err) => console.error('set_syphon_output failed:', err))
})

invoke('receiver_settings')
  .then((settings) => {
    saved = settings
    state.syphonOutput = settings.syphonOutput === true
    renderTracks()
    renderSyphon()
  })
  .catch(() => {})

loadCasterDevices()

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

renderTracks()
renderDeck()
showStage('waiting')
