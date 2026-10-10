import type { RegisteredDevice } from '../../shared/types'

/**
 * The device home's view models (ADR-005 addendum) — pure and clock-injected
 * so both the stage decision and the `adb devices`-style rows are
 * unit-testable without a DOM, matching pairingHero.ts's pattern.
 */

/** Which initial screen applies: the QR (never connected / explicitly requested) or the device list. */
export type DeviceHomeStage = 'qr' | 'devices'

/**
 * Two conditions, exactly the product sketch: no device has ever connected →
 * straight to the QR; known devices → the list, with the QR one
 * "Add new device" tap away (qrRequested).
 */
export function deviceHomeStage(knownDeviceCount: number, qrRequested: boolean): DeviceHomeStage {
  return knownDeviceCount === 0 || qrRequested ? 'qr' : 'devices'
}

export interface DeviceRow {
  /** The registry key for the row's Forget button (deviceId, else the address). */
  key: string
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
      const key = device.deviceId ?? `ip:${device.remote}`
      const id = shortId(device.deviceId)
      return device.state === 'online'
        ? { key, state: 'online', label: device.name, detail: `${id} · connected` }
        : { key, state: 'offline', label: device.name, detail: `${id} · last seen ${seenAgo(device.lastSeenAtMs, nowMs)} ago` }
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
