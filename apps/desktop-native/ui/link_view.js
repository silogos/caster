// What the receiver's window shows for its link (ADR-037), as pure functions
// of the window's state — the DOM code in main.js only draws the result, so
// no two parts of the window can disagree about the link or its status.
// Tested with `node --test apps/desktop-native/ui/` (link_view.test.mjs).
//
// state = {
//   mode: 'choice' | 'wireless' | 'cable',   the running link (link_state / link:mode)
//   phone: string | null,                    the paired phone's name
//   link: 'connected' | 'offline' | 'reconnecting',
//   live: boolean, info, picture,            the cast (cast:state, session-info, cast:picture)
//   dropDeadline: number | null,             ms epoch: ADR-036's five-minute wait ends
//   cable: { state, name },                  the cable guide's progress (cable:state)
// }

/** How long a cast waits for its link (ADR-036), mirrored from the phone's CastDropWindow. */
export const DROP_WINDOW_MS = 5 * 60 * 1000

const LINK_LABEL = { wireless: 'Wireless', cable: 'Cable' }

/** Which screen: the choice (frame 15), Wireless's QR (16), the cable guide (17), or the deck (19–24). */
export function stageFor(state) {
  if (state.mode === 'choice') return 'choice'
  if (state.phone) return 'deck'
  return state.mode === 'cable' ? 'guide' : 'qr'
}

/** "1920×1200 · 60 fps": the cast as the phone set it. */
export function settingsLine(info) {
  if (!info) return 'Live'
  return [`${info.width}×${info.height}`, `${info.fps} fps`].join(' · ')
}

function mmss(ms) {
  const s = Math.max(0, Math.ceil(ms / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/**
 * The deck's connection card (frames 19–24): the phone, the link and its
 * status, a sub line, ADR-036's countdown, and the footer (a hint and
 * Terminate). No footer while a cast runs or waits: Terminate can't end a
 * cast by a stray click.
 */
export function connectionCard(state, now = Date.now()) {
  const cable = state.mode === 'cable'
  const link = LINK_LABEL[state.mode] ?? ''
  const card = { name: state.phone ?? 'Android device', link, glyph: state.mode, tone: 'muted', status: 'Not connected',
    sub: null, countdown: null, footer: null }
  if (state.link === 'reconnecting') {
    card.tone = 'caution'
    card.status = 'Reconnecting…'
    card.sub = cable ? 'Plug the phone back in to carry on.' : 'Waiting for the phone to come back on Wi-Fi.'
    if (state.dropDeadline) card.countdown = mmss(state.dropDeadline - now)
    return card
  }
  if (state.link === 'connected') {
    card.tone = 'ok'
    card.status = 'Connected'
    if (state.live) {
      card.sub = settingsLine(state.info)
      return card
    }
    card.footer = { hint: cable ? 'Unplug the phone to disconnect' : 'To switch to the cable, Terminate' }
    return card
  }
  card.sub = cable ? 'Plug the phone in. It reconnects by itself.' : 'Tap Connect on the phone.'
  card.footer = { hint: cable ? 'To switch to Wireless, Terminate' : 'To switch to the cable, Terminate' }
  return card
}

/** The top bar's word. */
export function annunciator(state) {
  if (state.link === 'reconnecting') return { word: 'RECONNECTING', tone: 'reconnecting' }
  if (state.link === 'offline') return { word: 'OFFLINE', tone: '' }
  if (state.live) return { word: 'ON AIR', tone: 'on-air' }
  return { word: 'STANDBY', tone: '' }
}

/**
 * The cable guide's four steps (frame 17), each `{title, sub, state: 'done'|'now'|'later', ask?}`,
 * plus a `note` for what went wrong. Steps tick as the cable thread reports them.
 */
export function guideSteps(cable) {
  const s = cable?.state ?? 'waiting'
  const name = cable?.name ?? 'your phone'
  const order = { waiting: 0, found: 1, asking: 2, denied: 2, connected: 3 }
  const at = order[s] ?? 0
  const mark = (i) => (i < at ? 'done' : i === at ? 'now' : 'later')
  const steps = [
    { title: 'Plug your phone in', sub: at > 0 && cable?.name ? `${cable.name} found on USB.` : 'Use a cable that carries data, not one for charging only.' },
    { title: 'On the phone, tap OK on “Open Caster”', sub: 'Android asks once. With “Use by default”, later plug-ins connect by themselves.' },
    { title: s === 'asking' || s === 'denied' ? `Allow ${name} to cast over the cable?` : 'Allow this phone on this Mac',
      sub: 'Allow only a phone you know. Asked once; no answer in 60 s means Don\'t Allow.' },
    { title: 'Start casting from the phone', sub: 'Tap Share screen on the phone. This Mac then shows its deck.' },
  ].map((step, i) => ({ ...step, state: mark(i) }))
  if (s === 'asking') steps[2].ask = true
  let note = null
  if (s === 'denied') note = `${name} wasn't allowed. Unplug the phone and plug it in again to ask again.`
  else if (s === 'old-phone') note = 'Update Caster on the phone to cast over the cable.'
  else if (s === 'busy') note = 'This Mac is paired with another phone.'
  return { steps, note }
}

/** A phone's state from either link, applied to the window's state (returns the next state). */
export function applyMobileState(state, payload, now = Date.now()) {
  const s = payload?.state
  if (s === 'connected') {
    return { ...state, phone: payload.name ?? 'Android device', link: 'connected', info: null, dropDeadline: null }
  }
  if (s === 'disconnected') {
    // A running cast waits five minutes for its link (ADR-036); an idle phone is simply gone.
    if (state.live) return { ...state, link: 'reconnecting', live: false, dropDeadline: now + DROP_WINDOW_MS }
    return { ...state, link: 'offline', live: false, dropDeadline: null }
  }
  if (s === 'session-info') return { ...state, info: payload.info ?? state.info }
  if (s === 'forgotten') return { ...state, phone: null, link: 'offline', live: false, info: null, dropDeadline: null }
  return state
}
