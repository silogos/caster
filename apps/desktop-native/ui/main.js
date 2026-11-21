// The receiver's main window — the mobile app's "Signal Deck" look (ADR-037):
//  - the choice, Wireless or Cable, while nothing is paired (frame 15);
//  - Wireless: the QR hero (16); Cable: the cable guide with the one-time
//    Allow (17); Back returns to the choice;
//  - once paired, the deck (19–24): one connection card (the phone, the link,
//    its status, Terminate), "Open video", the cast's status, the sound.
//    Video plays only while the video window is open; sound while it is open,
//    the Syphon output is on (ADR-029) or the OBS camera is on (ADR-039), on
//    the device chosen under "Plays on".
// What the window says is decided by window_state.js — reduce(state, event) and view(state),
// pure and tested; this file only dispatches events and draws view().
// Events from the Rust core:
//  - link:mode {mode, name}  → which link runs (choice | wireless | cable)
//  - cable:state {state, name} → the cable guide's progress
//  - pairing:session-updated → the QR
//  - pairing:mobile-state    → connected / disconnected / session-info / forgotten
//  - cast:state {live}       → ON AIR + timecode
//  - cast:picture {width,height,fps} | null → the live readout: the size and frame rate that arrive
//  - video:window {open}     → the Open/Close video button
// Only receiver/environment controls live here — cast settings are the phone's.

import { initialState, reduce, view } from './window_state.js'

const tauri = window.__TAURI__ ?? null
if (!tauri) console.error('window.__TAURI__ missing — events will not arrive')
const invoke = (cmd, args) => (tauri ? tauri.core.invoke(cmd, args) : Promise.reject(new Error('no tauri')))
const $ = (id) => document.getElementById(id)

const stages = { choice: $('stage-choice'), qr: $('stage-qr'), guide: $('stage-guide'), deck: $('stage-deck') }

// ---- state: one reducer, one view (window_state.js) ------------------------

let state = initialState

/** Every change to the window's state goes through here. */
function dispatch(type, payload) {
  state = reduce(state, { type, payload, now: Date.now() })
  render()
}

function render() {
  const v = view(state)
  for (const [name, el] of Object.entries(stages)) el.classList.toggle('hidden', name !== v.stage)
  if (v.stage === 'guide') drawGuide(v.guide)
  if (v.stage === 'deck') drawDeck(v.deck)
  $('game-sub').textContent = v.gameSub
  $('syphon-switch').classList.toggle('on', v.syphon.on)
  $('syphon-sub').textContent = v.syphon.sub
  $('camera-switch').classList.toggle('on', v.camera.on)
  $('camera-switch').disabled = !v.camera.enabled
  $('camera-sub').textContent = v.camera.sub
  $('camera-message').textContent = v.camera.message
}

function drawGuide({ steps, note }) {
  $('guide-steps').replaceChildren(...steps.map((step, i) => {
    const el = document.createElement('div')
    el.className = `step ${step.state}`
    el.innerHTML = `<div class="n">${step.state === 'done' ? '✓' : i + 1}</div><div><div class="t"></div><div class="s"></div></div>`
    el.querySelector('.t').textContent = step.title
    el.querySelector('.s').textContent = step.sub
    if (step.ask) {
      const ask = document.createElement('div')
      ask.className = 'ask'
      ask.innerHTML = '<button class="btn btn-secondary" data-allow="false">Don\'t Allow</button><button class="btn btn-primary" data-allow="true">Allow</button>'
      el.lastElementChild.appendChild(ask)
    }
    return el
  }))
  $('guide-note').textContent = note ?? ''
  $('guide-note').classList.toggle('hidden', !note)
}

function drawDeck({ annunciator, card, video, status }) {
  $('annunciator').textContent = annunciator.word
  $('annunciator').className = `annunciator ${annunciator.tone}`.trim()

  $('phone-name').textContent = card.name
  $('conn').className = `card conn ${card.tone}`
  const glyph = $(`glyph-${card.glyph}`)
  if (glyph && $('conn-glyph').dataset.glyph !== card.glyph) {
    $('conn-glyph').replaceChildren(glyph.content.cloneNode(true))
    $('conn-glyph').dataset.glyph = card.glyph
  }
  $('conn-status').textContent = `${card.link} · ${card.status}`
  $('conn-sub').textContent = card.sub ?? ''
  $('conn-sub').classList.toggle('hidden', !card.sub)
  $('conn-count').classList.toggle('hidden', !card.countdown)
  $('conn-count-v').textContent = card.countdown ?? ''
  $('conn-foot').classList.toggle('hidden', !card.footer)
  $('conn-hint').textContent = card.footer?.hint ?? ''
  if (!card.footer) resetTerminate()

  const toggle = $('video-toggle')
  toggle.textContent = video.label
  toggle.className = `btn ${video.primary ? 'btn-primary' : 'btn-secondary'}`
  toggle.disabled = video.disabled
  $('video-hint').textContent = video.hint ?? ''
  $('video-hint').classList.toggle('hidden', !video.hint)

  $('timecode').textContent = status.timecode
  $('timecode').classList.toggle('idle', status.idle)
  $('readout').textContent = status.readout
  $('chip-game').classList.toggle('on', status.game)
  $('chip-mic').classList.toggle('on', status.mic)
}

// The timecode and ADR-036's countdown move once a second; the tick also ends a wait that ran out.
setInterval(() => dispatch('tick'), 1000)

// ---- events ----------------------------------------------------------------

for (const name of ['link:mode', 'cable:state', 'pairing:mobile-state', 'cast:state', 'cast:picture', 'video:window', 'obs-camera:failed']) {
  tauri?.event.listen(name, (event) => dispatch(name, event.payload))
}
tauri?.event.listen('pairing:session-updated', (event) => {
  if (event.payload?.qrDataUrl) $('qr-img').src = event.payload.qrDataUrl
})
const applyMode = (view) => dispatch('link:mode', view)
invoke('link_state').then(applyMode).catch((err) => console.error('link_state failed:', err))

// ---- the link: choose, Back, Allow ------------------------------------------

function choose(link) {
  invoke('choose_link', { link }).then(applyMode).catch((err) => console.error('choose_link failed:', err))
}
$('choose-wireless').addEventListener('click', () => choose('wireless'))
$('choose-cable').addEventListener('click', () => choose('cable'))
for (const back of document.querySelectorAll('[data-back]')) {
  back.addEventListener('click', () => invoke('back_to_choice').then(applyMode).catch((err) => console.error('back_to_choice failed:', err)))
}
$('guide-steps').addEventListener('click', (event) => {
  const allow = event.target.closest('[data-allow]')?.dataset.allow
  if (allow === undefined) return
  invoke('cable_answer', { allow: allow === 'true' }).catch((err) => console.error('cable_answer failed:', err))
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
// `audio`/`mic`: the installed Caster device (null: not installed);
// `canInstall`: this build carries the plug-in (ADR-030).
let casterDevices = { audio: null, mic: null, canInstall: false }
// Installing the Caster devices from a "Plays on" click (ADR-030): the
// track whose click started it, and the track whose install failed.
let installingFor = null
let installFailedFor = null

function renderTrack(name) {
  const t = tracks[name]
  $(`${t.id}-volume`).value = String(Math.round(saved[t.volume] * 100))
  $(`${t.id}-volume-value`).textContent = saved[t.muted] ? 'off' : `${$(`${t.id}-volume`).value}%`
  $(`${t.id}-switch`).classList.toggle('on', !saved[t.muted])
  const own = casterDevices[name] !== null
  const offerInstall = !own && casterDevices.canInstall
  const installing = installingFor === name
  for (const button of $(`${t.id}-plays-on`).querySelectorAll('button')) {
    const caster = button.dataset.playsOn === 'caster'
    // While installing, Speaker keeps playing and stays selected; so does a
    // saved Caster choice whose device is gone (the receiver plays on the
    // speaker then).
    const shown = installing || !own ? 'speaker' : saved[t.playsOn]
    const on = button.dataset.playsOn === shown
    button.classList.toggle('on', on)
    button.setAttribute('aria-checked', String(on))
    if (!caster) continue
    button.disabled = !own && !casterDevices.canInstall
    button.classList.toggle('needs-install', offerInstall && !installing && !on)
    button.classList.toggle('installing', installing)
    button.setAttribute('aria-busy', String(installing))
    button.querySelector('.seg-label').textContent = installing ? 'Installing…' : t.device
    if (offerInstall && !installing && !on) button.setAttribute('aria-describedby', `${t.id}-caster-tip`)
    else button.removeAttribute('aria-describedby')
  }
  const note = $(`${t.id}-plays-on-note`)
  note.classList.toggle('fault', installFailedFor === name)
  if (installing) note.textContent = 'macOS is asking for your password.'
  else if (installFailedFor === name) note.textContent = `Couldn't install ${t.device}. Try again.`
  // A build without the driver: say what is missing.
  else if (!own && !casterDevices.canInstall) note.textContent = `${t.device} isn't installed on this Mac.`
  else note.textContent = ''
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
    if (!playsOn || installingFor) return
    installFailedFor = null
    if (playsOn === 'caster' && casterDevices[name] === null && casterDevices.canInstall) {
      installCasterDevices(name)
      return
    }
    if (playsOn === saved[t.playsOn]) { renderTracks(); return }
    setPlaysOn(name, playsOn)
  })
}
function setPlaysOn(name, playsOn) {
  return invoke('set_plays_on', { track: name, playsOn })
    .then((next) => { saved = next; renderTracks() })
    .catch((err) => { console.error('set_plays_on failed:', err); loadCasterDevices() })
}
// One install brings both devices; the clicked track then moves to Caster.
// Cancelling the password is the user's choice: back to rest, no message.
function installCasterDevices(name) {
  installingFor = name
  renderTracks()
  invoke('install_caster_devices')
    .then(async (outcome) => {
      installingFor = null
      if (outcome === 'installed') {
        await loadCasterDevices()
loadCameraStatus()
        await setPlaysOn(name, 'caster')
      } else if (outcome === 'failed') {
        installFailedFor = name
      }
      renderTracks()
    })
    .catch((err) => {
      console.error('install_caster_devices failed:', err)
      installingFor = null
      installFailedFor = name
      renderTracks()
    })
}
// Re-read when this window gains focus: a device installed meanwhile.
function loadCasterDevices() {
  return invoke('caster_devices')
    .then((devices) => { casterDevices = devices; renderTracks(); render() })
    .catch((err) => console.error('caster_devices failed:', err))
}
window.addEventListener('focus', loadCasterDevices)

// The Syphon output for OBS: a receiver control, saved with the others.
$('syphon-switch').addEventListener('click', () => {
  invoke('set_syphon_output', { enabled: !state.syphonOutput })
    .then((saved) => dispatch('syphon', saved.syphonOutput === true))
    .catch((err) => console.error('set_syphon_output failed:', err))
})

// The OBS Virtual Camera output (ADR-039): its own switch, independent of
// Syphon. Whether OBS's camera is there is asked again whenever this window
// comes to the front, so installing or setting up OBS meanwhile shows at once.
function loadCameraStatus() {
  return invoke('obs_camera_status')
    .then((availability) => dispatch('camera:availability', availability))
    .catch((err) => console.error('obs_camera_status failed:', err))
}
window.addEventListener('focus', loadCameraStatus)
// The camera switched itself off: save that, so it is off next launch too.
tauri?.event.listen('obs-camera:failed', () => {
  invoke('set_obs_camera_output', { enabled: false }).catch(() => {})
})
$('camera-switch').addEventListener('click', () => {
  const wanted = !state.camera.on
  invoke('set_obs_camera_output', { enabled: wanted })
    .then((saved) => {
      dispatch('camera', saved.obsCameraOutput === true)
      // Asked for on, still off: OBS went away meanwhile, or the camera could not start.
      if (wanted && saved.obsCameraOutput !== true) {
        return invoke('obs_camera_status').then((availability) => {
          dispatch('camera:availability', availability)
          if (availability === 'ready') dispatch('obs-camera:failed')
        })
      }
    })
    .catch((err) => console.error('set_obs_camera_output failed:', err))
})

invoke('receiver_settings')
  .then((settings) => {
    saved = settings
    dispatch('syphon', settings.syphonOutput === true)
    dispatch('camera', settings.obsCameraOutput === true)
    renderTracks()
    render()
  })
  .catch(() => {})

loadCasterDevices()

// Terminate ends the pairing on both sides and returns to the choice — a
// two-step link instead of a dialog, so a stray click cannot end it.
const CONFIRM_WINDOW_MS = 4000
let confirmTimer = null
function resetTerminate() {
  clearTimeout(confirmTimer)
  confirmTimer = null
  $('terminate').textContent = 'Terminate'
  $('terminate').classList.remove('confirming')
}
$('terminate').addEventListener('click', () => {
  if (confirmTimer === null) {
    $('terminate').textContent = 'Forget this phone?'
    $('terminate').classList.add('confirming')
    confirmTimer = setTimeout(resetTerminate, CONFIRM_WINDOW_MS)
    return
  }
  resetTerminate()
  invoke('terminate').then(applyMode).catch((err) => console.error('terminate failed:', err))
})

$('new-qr').addEventListener('click', () => {
  invoke('regenerate_pairing').catch((err) => console.error('regenerate_pairing failed:', err))
})

// Pull the session on load: the setup-time emit can race the webview load.
invoke('pairing_payload')
  .then((session) => { if (session?.qrDataUrl) $('qr-img').src = session.qrDataUrl })
  .catch(() => {}) // none outside Wireless mode

renderTracks()
render()
