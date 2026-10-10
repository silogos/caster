import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import type { RegisteredDevice } from '../../shared/types'

/**
 * Persistence for the device registry (ADR-005 addendum): one JSON document
 * in the Electron userData dir, read once at startup and rewritten on every
 * registry change — the two-condition start screen ("never connected → QR /
 * known devices → list") needs the registry to outlive the process.
 *
 * Same storage convention as the mobile's CastSettingsStore: anything this
 * build can't fully validate degrades to an empty registry with a logged
 * warning — a list is convenience, never trusted state (it grants nothing).
 */
export const DEVICES_FILE_NAME = 'devices.json'

/** Load persisted entries; [] for a missing or unreadable document. */
export function loadDevices(path: string): RegisteredDevice[] {
  if (!existsSync(path)) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) return []
  return parsed.filter(isDeviceEntry)
}

/** Best-effort atomic write (tmp + rename); failures are the caller's log. */
export function saveDevices(path: string, entries: RegisteredDevice[]): void {
  const tmp = `${path}.tmp`
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(tmp, JSON.stringify(entries, null, 2))
  renameSync(tmp, path)
}

function isDeviceEntry(value: unknown): value is RegisteredDevice {
  if (typeof value !== 'object' || value === null) return false
  const entry = value as Record<string, unknown>
  return (
    (entry.deviceId === null || typeof entry.deviceId === 'string') &&
    typeof entry.name === 'string' &&
    typeof entry.ua === 'string' &&
    typeof entry.remote === 'string' &&
    (entry.state === 'online' || entry.state === 'offline') &&
    typeof entry.connectedAtMs === 'number' &&
    typeof entry.lastSeenAtMs === 'number'
  )
}
