import type { CastSessionInfo } from '../../shared/types'

/**
 * The one status line both surfaces render (Phase14): the receiver's hover
 * overlay over the video, and the off-cast session-info window (outside the
 * captured rect, so window capture stays a pure video feed). Pure — the copy
 * is unit-tested here, the two windows just display it.
 */
export function sessionInfoLine(name: string | null, info: CastSessionInfo | null): string {
  if (name === null) return ''
  if (info === null) return `Connected to ${name}`
  const sources = [info.gameAudio ? 'game audio' : null, info.mic ? 'mic' : null].filter(Boolean).join(' · ')
  const parts = [`${info.width}×${info.height}`, `${info.fps} fps`, info.profile, sources].filter(Boolean)
  return `Connected to ${name} — ${parts.join(' · ')}`
}
