// The virtual camera's placeholder stage (ADR-007) — pure view copy in the
// no-input stage's language. While the camera publishes, the receiver shows no
// preview (the user asked for exactly that): this stage stands in for the
// video, says what is happening, and reminds that minimizing is fine. The
// audio mixer stays reachable from the hover overlay's Settings.

export interface VcActiveView {
  heading: string
  subline: string
}

const HEADING = 'Virtual camera active'
const SUBLINE = 'The cast is being published as a camera (OBS, Zoom) — preview is off.'
const MINIMIZE_HINT = 'You can minimize this window.'

export function vcActiveView(): VcActiveView {
  return {
    heading: HEADING,
    subline: `${SUBLINE} ${MINIMIZE_HINT}`
  }
}
