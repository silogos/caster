// node --test apps/desktop-native/ui/
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { stageFor, connectionCard, annunciator, guideSteps, applyMobileState, DROP_WINDOW_MS } from './link_view.js'

const base = { mode: 'choice', phone: null, link: 'offline', live: false, info: null, dropDeadline: null, cable: null }

test('the screen follows the mode and the pairing', () => {
  assert.equal(stageFor(base), 'choice')
  assert.equal(stageFor({ ...base, mode: 'wireless' }), 'qr')
  assert.equal(stageFor({ ...base, mode: 'cable' }), 'guide')
  assert.equal(stageFor({ ...base, mode: 'cable', phone: 'Lenovo TB321FU' }), 'deck')
  assert.equal(stageFor({ ...base, mode: 'wireless', phone: 'Lenovo TB321FU' }), 'deck')
})

test('frame 19/20: connected, idle — the footer with its hint and Terminate', () => {
  const wifi = connectionCard({ ...base, mode: 'wireless', phone: 'P', link: 'connected' })
  assert.deepEqual([wifi.link, wifi.status, wifi.tone, wifi.footer.hint], ['Wireless', 'Connected', 'ok', 'To switch to the cable, Terminate'])
  const cable = connectionCard({ ...base, mode: 'cable', phone: 'P', link: 'connected' })
  assert.deepEqual([cable.link, cable.footer.hint], ['Cable', 'Unplug the phone to disconnect'])
})

test('frame 21: on air — the cast line, and no Terminate', () => {
  const card = connectionCard({ ...base, mode: 'cable', phone: 'P', link: 'connected', live: true, info: { width: 1920, height: 1200, fps: 60 } })
  assert.equal(card.sub, '1920×1200 · 60 fps')
  assert.equal(card.footer, null)
  assert.equal(annunciator({ ...base, link: 'connected', live: true }).word, 'ON AIR')
})

test('frame 22: unplugged mid-cast — Reconnecting with the five-minute countdown, no Terminate', () => {
  const live = { ...base, mode: 'cable', phone: 'P', link: 'connected', live: true }
  const dropped = applyMobileState(live, { state: 'disconnected', link: 'usb' }, 1000)
  assert.equal(dropped.dropDeadline, 1000 + DROP_WINDOW_MS)
  const card = connectionCard(dropped, 1000 + 8000)
  assert.deepEqual([card.status, card.tone, card.countdown, card.footer], ['Reconnecting…', 'caution', '4:52', null])
  assert.equal(annunciator(dropped).word, 'RECONNECTING')
})

test('frames 23/24: not connected — what to do, and how to switch', () => {
  const cable = connectionCard({ ...base, mode: 'cable', phone: 'P' })
  assert.deepEqual([cable.status, cable.sub, cable.footer.hint], ['Not connected', 'Plug the phone in. It reconnects by itself.', 'To switch to Wireless, Terminate'])
  const wifi = connectionCard({ ...base, mode: 'wireless', phone: 'P' })
  assert.deepEqual([wifi.sub, wifi.footer.hint], ['Tap Connect on the phone.', 'To switch to the cable, Terminate'])
})

test('an idle phone that leaves is offline, not reconnecting', () => {
  const s = applyMobileState({ ...base, mode: 'wireless', phone: 'P', link: 'connected' }, { state: 'disconnected' })
  assert.equal(s.link, 'offline')
  assert.equal(s.dropDeadline, null)
})

test('forgotten clears the phone', () => {
  const s = applyMobileState({ ...base, mode: 'cable', phone: 'P', link: 'connected' }, { state: 'forgotten' })
  assert.equal(s.phone, null)
})

test('the cable guide ticks: plug in → open Caster → Allow', () => {
  const states = (c) => guideSteps(c).steps.map((s) => s.state)
  assert.deepEqual(states({ state: 'waiting' }), ['now', 'later', 'later', 'later'])
  assert.deepEqual(states({ state: 'found', name: 'Lenovo TB321FU' }), ['done', 'now', 'later', 'later'])
  const asking = guideSteps({ state: 'asking', name: 'Lenovo TB321FU' })
  assert.deepEqual(asking.steps.map((s) => s.state), ['done', 'done', 'now', 'later'])
  assert.equal(asking.steps[2].ask, true)
  assert.equal(asking.steps[2].title, 'Allow Lenovo TB321FU to cast over the cable?')
  assert.equal(asking.steps[0].sub, 'Lenovo TB321FU found on USB.')
})

test('the cable guide says what went wrong', () => {
  assert.match(guideSteps({ state: 'denied', name: 'Lenovo TB321FU' }).note, /wasn't allowed/)
  assert.equal(guideSteps({ state: 'denied' }).steps[2].ask, undefined, 'no buttons once refused')
  assert.equal(guideSteps({ state: 'old-phone' }).note, 'Update Caster on the phone to cast over the cable.')
})
