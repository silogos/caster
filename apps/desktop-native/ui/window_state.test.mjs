// node --test apps/desktop-native/ui/window_state.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { initialState, reduce, view, DROP_WINDOW_MS } from './window_state.js'

/** Runs events through the reducer: [type, payload, now?]. */
const run = (events, state = initialState) =>
  events.reduce((s, [type, payload, now = 0]) => reduce(s, { type, payload, now }), state)

const cablePaired = [['link:mode', { mode: 'cable', name: 'Lenovo TB321FU' }]]
const connected = [...cablePaired, ['pairing:mobile-state', { state: 'connected', name: 'Lenovo TB321FU', link: 'usb' }]]
const onAir = [...connected,
  ['pairing:mobile-state', { state: 'session-info', info: { width: 1920, height: 1200, fps: 120, gameAudio: true, mic: false } }],
  ['cast:state', { live: true }, 1000]]

test('the screen follows the mode and the pairing', () => {
  assert.equal(view(initialState).stage, 'choice')
  assert.equal(view(run([['link:mode', { mode: 'wireless', name: null }]])).stage, 'qr')
  assert.equal(view(run([['link:mode', { mode: 'cable', name: null }]])).stage, 'guide')
  assert.equal(view(run(cablePaired)).stage, 'deck')
})

test('frame 23: paired, not connected — the card says what to do; offline, nothing to open', () => {
  const v = view(run(cablePaired)).deck
  assert.deepEqual([v.card.link, v.card.status, v.card.sub], ['Cable', 'Not connected', 'Plug the phone in. It reconnects by itself.'])
  assert.equal(v.card.footer.hint, 'To switch to Wireless, Terminate')
  assert.equal(v.annunciator.word, 'OFFLINE')
  assert.equal(v.video.disabled, true)
  assert.equal(v.video.hint, null)
  assert.deepEqual([v.status.timecode, v.status.readout], ['00:00:00', 'Waiting for the cast'])
})

test('frame 20: connected, idle — Terminate in the footer, STANDBY', () => {
  const v = view(run(connected)).deck
  assert.deepEqual([v.card.status, v.card.tone, v.card.footer.hint], ['Connected', 'ok', 'Unplug the phone to disconnect'])
  assert.equal(v.annunciator.word, 'STANDBY')
  assert.equal(v.video.disabled, false)
})

test('frame 21: on air — the clock runs, the measured picture replaces the settings, no Terminate', () => {
  let s = run(onAir)
  let v = view(s, 61_000).deck
  assert.equal(v.annunciator.word, 'ON AIR')
  assert.equal(v.card.footer, null)
  assert.equal(v.status.timecode, '00:01:00')
  assert.equal(v.status.readout, '1920×1200 · 120 fps · USB')
  assert.deepEqual([v.status.game, v.status.mic], [true, false])
  s = reduce(s, { type: 'cast:picture', payload: { width: 1280, height: 800, fps: 59 }, now: 62_000 })
  assert.equal(view(s, 62_000).deck.status.readout, '1280×800 · 59 fps · USB')
})

test('frame 22: unplugged mid-cast — Reconnecting, the countdown, and the clock keeps running', () => {
  // The receiver's order on an unplug: the picture stops, then the link reports it went mid-cast.
  const s = run([...onAir, ['cast:state', { live: false }, 9000], ['pairing:mobile-state', { state: 'disconnected', link: 'usb', casting: true }, 9000]])
  const v = view(s, 17_000).deck
  assert.deepEqual([v.card.status, v.card.countdown, v.card.footer], ['Reconnecting…', '4:52', null])
  assert.equal(v.annunciator.word, 'RECONNECTING')
  assert.equal(v.status.timecode, '00:00:16', 'the cast is still the one that started at 1 s')
  assert.equal(v.status.readout, 'Waiting for the link')
})

test('back within the window, the same cast carries on with its clock', () => {
  const s = run([...onAir, ['pairing:mobile-state', { state: 'disconnected' }, 9000],
    ['pairing:mobile-state', { state: 'connected', name: 'Lenovo TB321FU' }, 20_000], ['cast:state', { live: true }, 20_500]])
  assert.equal(s.dropDeadline, null)
  assert.equal(view(s, 31_000).deck.status.timecode, '00:00:30')
})

test('the five minutes run out: offline, the clock stops', () => {
  const dropped = run([...onAir, ['pairing:mobile-state', { state: 'disconnected' }, 9000]])
  const s = reduce(dropped, { type: 'tick', now: 9000 + DROP_WINDOW_MS })
  assert.equal(s.link, 'offline')
  assert.equal(view(s).deck.status.timecode, '00:00:00')
})

test('an idle phone that leaves is offline, not reconnecting', () => {
  const s = run([...connected, ['pairing:mobile-state', { state: 'disconnected' }]])
  assert.equal(s.link, 'offline')
  assert.equal(s.dropDeadline, null)
})

test('Wireless words: Connect is on the phone, the cable is the other link', () => {
  const s = run([['link:mode', { mode: 'wireless', name: 'P' }]])
  assert.deepEqual([view(s).deck.card.sub, view(s).deck.card.footer.hint], ['Tap Connect on the phone.', 'To switch to the cable, Terminate'])
})

test('forgotten clears the phone and the video window', () => {
  const s = run([...connected, ['video:window', { open: true }], ['pairing:mobile-state', { state: 'forgotten' }]])
  assert.deepEqual([s.phone, s.videoOpen], [null, false])
})

test('another link starts clean', () => {
  const s = run([...onAir, ['link:mode', { mode: 'wireless', name: null }]])
  assert.deepEqual([s.live, s.link, s.info, s.cable.state], [false, 'offline', null, 'waiting'])
})

test('the cable guide ticks: plug in → open Caster → Allow', () => {
  const steps = (cable) => view({ ...initialState, mode: 'cable', cable }).guide
  assert.deepEqual(steps({ state: 'waiting' }).steps.map((s) => s.state), ['now', 'later', 'later', 'later'])
  assert.deepEqual(steps({ state: 'found', name: 'Lenovo TB321FU' }).steps.map((s) => s.state), ['done', 'now', 'later', 'later'])
  const asking = steps({ state: 'asking', name: 'Lenovo TB321FU' })
  assert.deepEqual(asking.steps.map((s) => s.ask), [false, false, true, false])
  assert.equal(asking.steps[2].title, 'Allow Lenovo TB321FU to cast over the cable?')
  assert.equal(asking.steps[0].sub, 'Lenovo TB321FU found on USB.')
})

test('the cable guide says what went wrong', () => {
  const g = (state) => view({ ...initialState, mode: 'cable', cable: { state, name: 'Lenovo TB321FU' } }).guide
  assert.match(g('denied').note, /wasn't allowed/)
  assert.equal(g('denied').steps[2].ask, false, 'no buttons once refused')
  assert.equal(g('old-phone').note, 'Update Caster on the phone to cast over the cable.')
})

test('the sound line and Syphon follow the receiver controls', () => {
  assert.equal(view(initialState).gameSub, 'Plays while the video window is open or Syphon is on.')
  const s = reduce(initialState, { type: 'syphon', payload: true })
  assert.equal(view(s).gameSub, 'Playing on this computer.')
  assert.match(view(s).syphon.sub, /^On\./)
})
