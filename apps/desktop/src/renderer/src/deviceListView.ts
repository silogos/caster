import type { RegisteredDevice } from '../../shared/types'

/**
 * The device registry's view model (ADR-005) — pure and clock-injected, so
 * the `adb devices`-style list is unit-testable without a DOM, matching
 * pairingHero.ts's pattern. One row per device: name, state, short identity.
 * Identity display is deliberately short — the full UUID stays in logs.
 */
export interface DeviceRow {
  /** Rendered as the row's state marker; both states are always shown. */
  state: RegisteredDevice['state']
  label: string
  detail: string
}

export interface DeviceListView {
  heading: string
  rows: DeviceRow[]
}

export function deviceListView(devices: RegisteredDevice[], nowMs: number = Date.now()): DeviceListView {
  return {
    heading: 'Devices',
    rows: devices.map((device) => {
      const id = shortId(device.deviceId)
      return device.state === 'online'
        ? { state: 'online', label: device.name, detail: `${id} · connected` }
        : { state: 'offline', label: device.name, detail: `${id} · last seen ${seenAgo(device.lastSeenAtMs, nowMs)} ago` }
    }),
  }
}

/** First segment of the install-scoped UUID, or a fallback for unidentified clients. */
function shortId(deviceId: string | null): string {
  return deviceId?.split('-')[0] ?? 'unidentified'
}

function seenAgo(lastSeenAtMs: number, nowMs: number): string {
  const seconds = Math.max(0, Math.round((nowMs - lastSeenAtMs) / 1000))
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes}m`
  return `${Math.round(minutes / 60)}h`
}
