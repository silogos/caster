// The receiver window's state and what it shows (ADR-037), as two pure
// functions — the desktop's counterpart of the phone's linkUi():
//
//   reduce(state, event) → the next state   (every event from the Rust core, every tick)
//   view(state, now)     → what to draw     (the screen, the guide, the deck)
//
// main.js only dispatches events and draws view(); nothing else decides what
// the window says, so no two parts of it can disagree about the link, the
// cast or its status. Tested with `node --test apps/desktop-native/ui/window_state.test.mjs`.

/** How long a cast waits for its link (ADR-036), mirrored from the phone's CastDropWindow. */
export const DROP_WINDOW_MS = 5 * 60 * 1000

export const initialState = Object.freeze({
  mode: 'choice', // the running link: 'choice' | 'wireless' | 'cable'
  phone: null, // the paired phone's name
  link: 'offline', // 'connected' | 'offline' | 'reconnecting'
  live: false, // a picture is arriving
  liveSince: 0, // ms epoch the cast started (kept through a drop)
  info: null, // the phone's session-info: {width, height, fps, gameAudio, mic}
  picture: null, // what arrives, measured once a second: {width, height, fps}
  dropDeadline: null, // ms epoch ADR-036's wait ends
  cable: { state: 'waiting', name: null }, // the cable guide's progress
  videoOpen: false,
  syphonOutput: false,
})

const LINK_LABEL = { wireless: 'Wireless', cable: 'Cable' }

// ---- reduce ------------------------------------------------------------------

/**
 * One event → the next state. `event` is `{type, payload, now}`; `type` is the
 * Rust core's event name, or 'tick' (once a second) or 'syphon' (the switch's answer).
 */
export function reduce(state, { type, payload, now = Date.now() }) {
  switch (type) {
    case 'link:mode': {
      if (!payload) return state
      const mode = payload.mode ?? 'choice'
      const next = { ...state, mode, phone: payload.name ?? null }
      // Another link: nothing of the old one's cast or guide carries over.
      return mode === state.mode ? next : { ...next, link: 'offline', live: false, info: null, picture: null, dropDeadline: null, cable: initialState.cable }
    }
    case 'cable:state':
      return { ...state, cable: { state: payload?.state ?? 'waiting', name: payload?.name ?? state.cable.name } }
    case 'pairing:mobile-state':
      return mobileState(state, payload, now)
    case 'cast:state': {
      const live = payload?.live === true
      if (live) return { ...state, live, link: 'connected', dropDeadline: null, liveSince: state.live || state.dropDeadline ? state.liveSince : now }
      return { ...state, live, picture: null }
    }
    case 'cast:picture':
      return { ...state, picture: payload ?? null }
    case 'video:window':
      return { ...state, videoOpen: payload?.open === true }
    case 'syphon':
      return { ...state, syphonOutput: payload === true }
    case 'tick':
      // ADR-036's wait ran out: the cast is over, the phone simply offline.
      return state.dropDeadline && now >= state.dropDeadline ? { ...state, link: 'offline', dropDeadline: null, liveSince: 0 } : state
    default:
      return state
  }
}

function mobileState(state, payload, now) {
  switch (payload?.state) {
    case 'connected':
      return { ...state, phone: payload.name ?? 'Android device', link: 'connected', info: state.dropDeadline ? state.info : null }
    case 'disconnected':
      // A running cast waits five minutes for its link (ADR-036); an idle phone is simply gone.
      // `casting` comes from the cable thread, whose "no picture" arrives just before this event.
      if (state.live || payload.casting === true) return { ...state, link: 'reconnecting', live: false, picture: null, dropDeadline: now + DROP_WINDOW_MS }
      return { ...state, link: 'offline', live: false, dropDeadline: null }
    case 'session-info':
      return { ...state, info: payload.info ?? state.info }
    case 'forgotten':
      return { ...state, phone: null, link: 'offline', live: false, info: null, picture: null, dropDeadline: null, videoOpen: false }
    default:
      return state
  }
}

// ---- view --------------------------------------------------------------------

/** Everything the window draws. */
export function view(state, now = Date.now()) {
  return {
    stage: stageFor(state),
    guide: guideSteps(state.cable),
    deck: {
      annunciator: annunciator(state),
      card: connectionCard(state, now),
      video: videoButton(state),
      status: statusReadout(state, now),
    },
    gameSub: state.videoOpen || state.syphonOutput ? 'Playing on this computer.' : 'Plays while the video window is open or Syphon is on.',
    syphon: {
      on: state.syphonOutput,
      sub: state.syphonOutput
        ? 'On. In OBS add a Syphon Client source named "Caster", and Audio Input Captures of "Caster Audio" and "Caster Mic".'
        : 'Off. OBS can still capture the video window.',
    },
  }
}

/** Which screen: the choice (frame 15), Wireless's QR (16), the cable guide (17), or the deck (19–24). */
export function stageFor(state) {
  if (state.mode === 'choice') return 'choice'
  if (state.phone) return 'deck'
  return state.mode === 'cable' ? 'guide' : 'qr'
}

/** "1920×1200 · 60 fps": the cast as the phone set it. */
export function settingsLine(info) {
  if (!info) return 'Live'
  return `${info.width}×${info.height} · ${info.fps} fps`
}

function mmss(ms) {
  const s = Math.max(0, Math.ceil(ms / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

function hhmmss(ms) {
  const s = Math.max(0, Math.floor(ms / 1000))
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
}

/**
 * The deck's connection card (frames 19–24): the phone, the link and its
 * status, a sub line, ADR-036's countdown, and the footer (a hint and
 * Terminate). No footer while a cast runs or waits: Terminate can't end a
 * cast by a stray click.
 */
export function connectionCard(state, now = Date.now()) {
  const cable = state.mode === 'cable'
  const card = { name: state.phone ?? 'Android device', link: LINK_LABEL[state.mode] ?? '', glyph: state.mode, tone: 'muted',
    status: 'Not connected', sub: null, countdown: null, footer: null }
  if (state.link === 'reconnecting') {
    return { ...card, tone: 'caution', status: 'Reconnecting…',
      sub: cable ? 'Plug the phone back in to carry on.' : 'Waiting for the phone to come back on Wi-Fi.',
      countdown: state.dropDeadline ? mmss(state.dropDeadline - now) : null }
  }
  if (state.link === 'connected') {
    if (state.live) return { ...card, tone: 'ok', status: 'Connected', sub: settingsLine(state.info) }
    return { ...card, tone: 'ok', status: 'Connected', footer: { hint: cable ? 'Unplug the phone to disconnect' : 'To switch to the cable, Terminate' } }
  }
  return { ...card, sub: cable ? 'Plug the phone in. It reconnects by itself.' : 'Tap Connect on the phone.',
    footer: { hint: cable ? 'To switch to Wireless, Terminate' : 'To switch to the cable, Terminate' } }
}

/** The top bar's word. */
export function annunciator(state) {
  if (state.link === 'reconnecting') return { word: 'RECONNECTING', tone: 'reconnecting' }
  if (state.link === 'offline') return { word: 'OFFLINE', tone: '' }
  if (state.live) return { word: 'ON AIR', tone: 'on-air' }
  return { word: 'STANDBY', tone: '' }
}

/** Open / Close video: closed and offline, there is nothing to open. */
function videoButton(state) {
  return {
    label: state.videoOpen ? 'Close video' : 'Open video',
    primary: !state.videoOpen,
    disabled: state.link === 'offline' && !state.videoOpen,
    hint: state.link === 'offline' ? null : 'Video plays while the video window is open.',
  }
}

/**
 * The STATUS card: the cast's clock (running through ADR-036's wait), what
 * arrives right now (the measured picture, or the phone's settings until the
 * first second), and the two tracks' chips.
 */
function statusReadout(state, now) {
  const running = state.live || state.link === 'reconnecting'
  const readout = state.live
    ? (state.picture ? `${state.picture.width}×${state.picture.height} · ${state.picture.fps} fps` : settingsLine(state.info)) + (state.mode === 'cable' ? ' · USB' : '')
    : state.link === 'reconnecting' ? 'Waiting for the link' : 'Waiting for the cast'
  return {
    timecode: running && state.liveSince ? hhmmss(now - state.liveSince) : '00:00:00',
    idle: !state.live,
    readout,
    game: state.live && state.info?.gameAudio === true,
    mic: state.live && state.info?.mic === true,
  }
}

/**
 * The cable guide's four steps (frame 17), each `{title, sub, state: 'done'|'now'|'later', ask?}`,
 * plus a `note` for what went wrong. Steps tick as the cable thread reports them.
 */
export function guideSteps(cable) {
  const s = cable?.state ?? 'waiting'
  const name = cable?.name ?? 'your phone'
  const at = { waiting: 0, found: 1, asking: 2, denied: 2, connected: 3 }[s] ?? 0
  const mark = (i) => (i < at ? 'done' : i === at ? 'now' : 'later')
  const steps = [
    { title: 'Plug your phone in', sub: at > 0 && cable?.name ? `${cable.name} found on USB.` : 'Use a cable that carries data, not one for charging only.' },
    { title: 'On the phone, tap OK on “Open Caster”', sub: 'Android asks once. With “Use by default”, later plug-ins connect by themselves.' },
    { title: s === 'asking' || s === 'denied' ? `Allow ${name} to cast over the cable?` : 'Allow this phone on this Mac',
      sub: 'Allow only a phone you know. Asked once; no answer in 60 s means Don\'t Allow.' },
    { title: 'Start casting from the phone', sub: 'Tap Share screen on the phone. This Mac then shows its deck.' },
  ].map((step, i) => ({ ...step, state: mark(i), ask: s === 'asking' && i === 2 }))
  const note = { denied: `${name} wasn't allowed. Unplug the phone and plug it in again to ask again.`,
    'old-phone': 'Update Caster on the phone to cast over the cable.', busy: 'This Mac is paired with another phone.' }[s] ?? null
  return { steps, note }
}
