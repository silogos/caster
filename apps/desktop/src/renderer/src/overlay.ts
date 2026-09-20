import type { CastSessionInfo, MobileStateEvent } from '../../shared/types'
import { sessionInfoLine } from './sessionInfoLine'

// The off-cast session-info window (Phase14) — a pure display surface: the
// same line the receiver's hover overlay shows, rendered in its own window so
// it can never pollute the captured feed. State semantics mirror the
// receiver's: 'connected' names the phone, 'session-info' carries the display
// summary, 'waiting' ends the cast (the main process closes this window).

const lineEl = document.getElementById('overlay-line') as HTMLParagraphElement
const closeEl = document.getElementById('overlay-close') as HTMLButtonElement

let connectedName: string | null = null
let sessionInfo: CastSessionInfo | null = null

function render(): void {
  lineEl.textContent = sessionInfoLine(connectedName, sessionInfo)
}

const unsubscribeMobileState = window.desktopApi.onMobileStateChanged((state: MobileStateEvent) => {
  if (state.state === 'connected') {
    connectedName = state.name
    sessionInfo = null
  } else if (state.state === 'session-info') {
    sessionInfo = state.info
  } else {
    connectedName = null
    sessionInfo = null
  }
  render()
})

// Same preference the Settings checkbox writes — closing here is "turn it
// off", and the pushed state keeps that checkbox honest.
closeEl.addEventListener('click', () => {
  window.desktopApi.setSessionInfoOverlay(false)
})

window.addEventListener('beforeunload', () => {
  unsubscribeMobileState()
})
