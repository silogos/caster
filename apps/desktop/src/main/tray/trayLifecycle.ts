/**
 * The receiver's window/tray lifecycle state machine (issue #94, ADR-010):
 * closing the receiver window HIDES it — the app (signaling server + trust
 * store) stays alive in the tray so a saved phone's one-tap Connect always
 * finds it, and a cast that starts while the window is closed re-opens it.
 * Quit is the only exit, and it walks the clean `bye` teardown first.
 *
 * Pure by design (the `heroView` precedent): events in → state + effect out.
 * The Electron shell (index.ts) applies the effects; tests drive the machine
 * directly. The one rule learned from the reverted first attempt (2026-10-31,
 * "the app didn't open"): visibility is an explicit intent field here — NEVER
 * inferred from BrowserWindow.isVisible(), which is false mid-startup before
 * ready-to-show and caused a recreated window to come up hidden.
 */

export interface ReceiverLifecycleState {
  /**
   * Visibility INTENT for the receiver window. Starts true (first launch
   * always shows the window — first-run QR pairing needs physical presence
   * at the desktop, ADR-002). False only after the user closed the window
   * (close-to-tray) until something shows it again.
   */
  receiverVisible: boolean
  /** A real quit is in flight — the close-to-hide guard steps aside for it. */
  quitting: boolean
}

export type ReceiverLifecycleEvent =
  /** The user closed the receiver window (its ✕ / red traffic light / Alt+F4). */
  | { type: 'window-close' }
  /** Anything that should bring the receiver back: tray Open window, the Dock, a relaunch attempt. */
  | { type: 'open-receiver' }
  /** A cast went live (renderer's window:cast-active push) — re-open a closed window (issue #94). */
  | { type: 'cast-started' }
  /** Tray Quit, Cmd+Q, OS logout/shutdown. */
  | { type: 'quit-requested' }

export type ReceiverLifecycleEffect =
  | { kind: 'none' }
  /** Hide the window (macOS: also the Dock icon) — the tray carries the app. */
  | { kind: 'hide-receiver' }
  /** Show + focus the window (macOS: also restore the Dock icon). */
  | { kind: 'show-receiver' }
  /** Send the clean `bye` (trust survives, ADR-006), let the window close, and exit. */
  | { kind: 'bye-then-quit' }

export const initialReceiverLifecycleState: ReceiverLifecycleState = {
  receiverVisible: true,
  quitting: false
}

/**
 * One transition. Unconditional close-to-tray (the issue's recommendation —
 * TwinDesk's shape): every window close hides while the app lives; only a
 * quit in flight lets the close destroy the window, and only the quit paths
 * end the app.
 */
export function receiverLifecycle(
  state: ReceiverLifecycleState,
  event: ReceiverLifecycleEvent
): { state: ReceiverLifecycleState; effect: ReceiverLifecycleEffect } {
  switch (event.type) {
    case 'window-close':
      if (state.quitting) return { state, effect: { kind: 'none' } }
      if (!state.receiverVisible) return { state, effect: { kind: 'none' } }
      return {
        state: { ...state, receiverVisible: false },
        effect: { kind: 'hide-receiver' }
      }
    case 'open-receiver':
      // Idempotent — showing an already-visible window just focuses it.
      return {
        state: { ...state, receiverVisible: true },
        effect: { kind: 'show-receiver' }
      }
    case 'cast-started':
      // The one-tap Connect promise: a cast that begins against a tray-only
      // desktop opens the receiver with no desktop interaction. An already
      // visible window does nothing (no focus steal mid-game on the desktop).
      if (state.receiverVisible || state.quitting) return { state, effect: { kind: 'none' } }
      return {
        state: { ...state, receiverVisible: true },
        effect: { kind: 'show-receiver' }
      }
    case 'quit-requested':
      if (state.quitting) return { state, effect: { kind: 'none' } }
      return {
        state: { ...state, quitting: true },
        effect: { kind: 'bye-then-quit' }
      }
  }
}
