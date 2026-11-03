import { describe, expect, it } from 'vitest'
import {
  initialReceiverLifecycleState,
  receiverLifecycle,
  type ReceiverLifecycleEvent,
  type ReceiverLifecycleState
} from './trayLifecycle'

// The acceptance matrix of issue #94's window/tray lifecycle, driven as the
// pure machine (heroView precedent): every close hides while the app lives,
// only Quit exits, and a cast starting against a tray-only desktop re-opens
// the window — the one-tap Connect promise that motivated the feature.

const step = (state: ReceiverLifecycleState, event: ReceiverLifecycleEvent) => receiverLifecycle(state, event)

describe('receiverLifecycle', () => {
  it('starts visible — first launch must show the QR window', () => {
    expect(initialReceiverLifecycleState).toEqual({ receiverVisible: true, quitting: false })
  })

  it('closing the window hides it — the app stays alive (close-to-tray)', () => {
    const { state, effect } = step(initialReceiverLifecycleState, { type: 'window-close' })
    expect(effect).toEqual({ kind: 'hide-receiver' })
    expect(state.receiverVisible).toBe(false)
    expect(state.quitting).toBe(false)
  })

  it('a second close while already hidden is a no-op', () => {
    const hidden = step(initialReceiverLifecycleState, { type: 'window-close' }).state
    const again = step(hidden, { type: 'window-close' })
    expect(again.effect).toEqual({ kind: 'none' })
    expect(again.state.receiverVisible).toBe(false)
  })

  it('a cast starting while hidden re-opens the window (one-tap Connect, no desktop interaction)', () => {
    const hidden = step(initialReceiverLifecycleState, { type: 'window-close' }).state
    const { state, effect } = step(hidden, { type: 'cast-started' })
    expect(effect).toEqual({ kind: 'show-receiver' })
    expect(state.receiverVisible).toBe(true)
  })

  it('a cast starting while visible does not steal focus', () => {
    const { state, effect } = step(initialReceiverLifecycleState, { type: 'cast-started' })
    expect(effect).toEqual({ kind: 'none' })
    expect(state.receiverVisible).toBe(true)
  })

  it('open-receiver shows and focuses (idempotent)', () => {
    const hidden = step(initialReceiverLifecycleState, { type: 'window-close' }).state
    const shown = step(hidden, { type: 'open-receiver' })
    expect(shown.effect).toEqual({ kind: 'show-receiver' })
    expect(shown.state.receiverVisible).toBe(true)
    const again = step(shown.state, { type: 'open-receiver' })
    expect(again.effect).toEqual({ kind: 'show-receiver' })
  })

  it('quit sends the clean bye and flips quitting — the close guard steps aside', () => {
    const { state, effect } = step(initialReceiverLifecycleState, { type: 'quit-requested' })
    expect(effect).toEqual({ kind: 'bye-then-quit' })
    expect(state.quitting).toBe(true)
    // A window close during the quit walk must NOT be re-routed to hide —
    // the window needs to actually close so the app can exit.
    const close = step(state, { type: 'window-close' })
    expect(close.effect).toEqual({ kind: 'none' })
    expect(close.state.receiverVisible).toBe(true)
  })

  it('a quit while quitting is idempotent (one bye walk, one exit)', () => {
    const quitting = step(initialReceiverLifecycleState, { type: 'quit-requested' }).state
    const again = step(quitting, { type: 'quit-requested' })
    expect(again.effect).toEqual({ kind: 'none' })
  })

  it('a cast starting during the quit walk never re-opens the window', () => {
    const quitting = step(initialReceiverLifecycleState, { type: 'quit-requested' }).state
    const { state, effect } = step(quitting, { type: 'cast-started' })
    expect(effect).toEqual({ kind: 'none' })
    expect(state.receiverVisible).toBe(true)
    expect(state.quitting).toBe(true)
  })

  it('close-mid-cast keeps the cast: hiding is the close effect even while casting', () => {
    // The cast-continues guarantee is the effect choice itself — a mid-cast
    // close is an ordinary close-to-tray, never a quit or destroy.
    const hidden = step(initialReceiverLifecycleState, { type: 'window-close' })
    expect(hidden.effect).toEqual({ kind: 'hide-receiver' })
  })

  it('a hidden window hides again after a show → close cycle', () => {
    const hidden = step(initialReceiverLifecycleState, { type: 'window-close' }).state
    const shown = step(hidden, { type: 'open-receiver' }).state
    const rehidden = step(shown, { type: 'window-close' })
    expect(rehidden.effect).toEqual({ kind: 'hide-receiver' })
    expect(rehidden.state.receiverVisible).toBe(false)
  })
})
