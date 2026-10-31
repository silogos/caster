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
 * Find the virtual cable output (BlackHole on macOS). Matched on the label,
 * case-insensitive, among output devices only — BlackHole also appears as an
 * input device (that's the one OBS reads), and that side is not ours.
 */
export const findCableOutputDevice = (
  devices: readonly AudioDeviceInfo[]
): AudioDeviceInfo | null => {
  const matches = devices.filter(
    (device) =>
      device.kind === 'audiooutput' &&
      /blackhole/i.test(device.label) &&
      device.deviceId !== ''
  )
  // Multiple channels variants (2ch/64ch) are equivalent for our purpose;
  // the first match keeps behavior stable between launches.
  return matches[0] ?? null
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
