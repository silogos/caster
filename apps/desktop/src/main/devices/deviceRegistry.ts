import type { RegisteredDevice } from '../../shared/types'

/**
 * The desktop's device registry (ADR-005): an `adb devices`-style list of
 * the mobiles this desktop has seen — identity (from `hello`'s optional
 * `deviceId`), name, state and last-seen time. Pure and clock-injected, so
 * the state transitions are unit-testable.
 *
 * Deliberately display plumbing, never security: entries grant nothing and
 * remember nothing about authorization — pairing still requires a fresh QR
 * and the HMAC handshake (ADR-002). The registry lives in the main process
 * only; Phase A keeps it in memory (a restart clears it — disk persistence
 * is the announced next phase).
 */
export class DeviceRegistry {
  private readonly entries = new Map<string, RegisteredDevice>()

  /**
   * A device completed the handshake — bring its entry online (or create
   * it). Keyed by `deviceId` when the client identified itself, else by the
   * remote address (the only identity an older client has).
   */
  markConnected(event: { deviceId?: string; name: string; ua: string; remote: string }, nowMs: number): RegisteredDevice {
    const key = registryKey(event.deviceId, event.remote)
    const entry: RegisteredDevice = {
      deviceId: event.deviceId ?? null,
      name: event.name,
      ua: event.ua,
      remote: event.remote,
      state: 'online',
      connectedAtMs: nowMs,
      lastSeenAtMs: nowMs,
    }
    // The fresh visit replaces the stored entry wholesale — name/UA may have
    // changed between app versions (Phase A keeps the registry in memory;
    // visit history arrives with disk persistence, the next phase).
    this.entries.set(key, entry)
    return entry
  }

  /** The device's socket is gone (bye/failure/heartbeat drop) — mark, never delete. */
  markOffline(event: { deviceId?: string; remote: string }, nowMs: number): void {
    const entry = this.entries.get(registryKey(event.deviceId, event.remote))
    if (entry === undefined) return
    entry.state = 'offline'
    entry.lastSeenAtMs = nowMs
  }

  /** All known devices — most recently seen first (the adb-devices surface). */
  list(): RegisteredDevice[] {
    return [...this.entries.values()].sort((a, b) => b.lastSeenAtMs - a.lastSeenAtMs)
  }
}

function registryKey(deviceId: string | undefined, remote: string): string {
  return deviceId ?? `ip:${remote}`
}
