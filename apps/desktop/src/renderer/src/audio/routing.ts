/**
 * Audio output routing (issue #54, ADR-005 Phase D pulled forward): where the
 * mixed cast audio goes. Today's receiver plays everything on the speakers;
 * "cable" mode sends the mix to a virtual cable device (BlackHole) instead —
 * so OBS can record it while the speakers stay silent, and "both" monitors
 * locally at the same time. The level math is untouched: these are routes,
 * not gains.
 *
 * Everything here is pure so the device matching and mode rules are unit
 * testable in plain Node (no Chromium).
 */

export type RoutingMode = 'speakers' | 'cable' | 'both'

export const DEFAULT_ROUTING_MODE: RoutingMode = 'speakers'

export const ROUTING_MODES: readonly RoutingMode[] = ['speakers', 'cable', 'both']

/** A subset of MediaDeviceInfo, so tests don't need the DOM type. */
export interface AudioDeviceInfo {
  deviceId: string
  kind: string
  label: string
}

export const isRoutingMode = (value: unknown): value is RoutingMode =>
  typeof value === 'string' && (ROUTING_MODES as readonly string[]).includes(value)

/**
 * Find the virtual cable output. Matched on the label, case-insensitive,
 * among output devices only — cables also appear as input devices (that's
 * the side OBS reads), and that side is not ours. Tier order (issue #74):
 * caster's own Zero-Friction devices first (per-track, ADR-008), then
 * BlackHole (macOS), then VB-Cable (Windows) — the platforms don't compete,
 * the order only decides the rare machine with several installed.
 */
export const findCableOutputDevice = (
  devices: readonly AudioDeviceInfo[]
): AudioDeviceInfo | null => {
  // Prefer caster's own devices (batch A, ADR-008) — they're per-track.
  const ours = devices.filter(
    (device) =>
      device.kind === 'audiooutput' &&
      /zero-friction (game audio|mic)/i.test(device.label) &&
      device.deviceId !== ''
  )
  if (ours.length > 0) return ours[0]

  const cableLabels = [/blackhole/i, /vb-audio|cable output/i]
  for (const label of cableLabels) {
    const matches = devices.filter(
      (device) => device.kind === 'audiooutput' && label.test(device.label) && device.deviceId !== ''
    )
    // Multiple channels variants (2ch/64ch) are equivalent for our purpose;
    // the first match keeps behavior stable between launches.
    if (matches.length > 0) return matches[0]
  }
  return null
}

/**
 * Find caster's own per-track devices (batch A, ADR-008): the game audio and
 * mic loopback devices from the ZeroFrictionAudio HAL plugin. Returns both,
 * either may be null (plugin not installed / not detected).
 */
export const findPerTrackDevices = (
  devices: readonly AudioDeviceInfo[]
): { gameAudio: AudioDeviceInfo | null; mic: AudioDeviceInfo | null } => {
  const find = (label: RegExp): AudioDeviceInfo | null =>
    devices.find(
      (device) => device.kind === 'audiooutput' && label.test(device.label) && device.deviceId !== ''
    ) ?? null
  return {
    gameAudio: find(/zero-friction game audio/i),
    mic: find(/zero-friction mic/i)
  }
}

const STORAGE_KEY = 'zfc.audio-routing.v1'

/** Read the persisted routing mode; anything unreadable falls back to speakers. */
export const loadRoutingMode = (
  storage: { getItem(key: string): string | null } | undefined,
  log: (level: 'warn', message: string, details?: Record<string, unknown>) => void
): RoutingMode => {
  if (storage === undefined) return DEFAULT_ROUTING_MODE
  const raw = storage.getItem(STORAGE_KEY)
  if (raw === null) return DEFAULT_ROUTING_MODE
  try {
    const parsed = JSON.parse(raw) as { mode?: unknown }
    if (isRoutingMode(parsed.mode)) return parsed.mode
    log('warn', 'stored routing mode was invalid — using speakers', { stored: String(parsed.mode) })
    return DEFAULT_ROUTING_MODE
  } catch (error) {
    log('warn', 'stored routing mode was unreadable — using speakers', { error: String(error) })
    return DEFAULT_ROUTING_MODE
  }
}

export const persistRoutingMode = (
  storage: { setItem(key: string, value: string): void } | undefined,
  mode: RoutingMode
): void => {
  storage?.setItem(STORAGE_KEY, JSON.stringify({ mode }))
}
