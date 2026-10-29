import { chmodSync, readFileSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

/**
 * Persistent trust for the single paired phone (pairing.md §Persistent
 * sessions, ADR-006): one entry, written the first time a QR pairing with a
 * `deviceId` succeeds and kept until either side Disconnects (`bye forget` or
 * the desktop's own Disconnect). It grants reconnect-without-QR (the resume
 * handshake) and nothing else — a fresh QR pairing still requires physical
 * presence at the desktop.
 *
 * One JSON document (`trust.json`) in the Electron userData dir, rewritten on
 * every change and read once at startup before any socket can accept a
 * resume. Anything unreadable or wrong-shaped degrades to "no trusted
 * device" with a logged warning — the same convention as the mobile's
 * CastSettingsStore: the store is convenience state, never load-bearing for
 * anything but the resume path it itself serves. The key sits in the file
 * plainly (mode 0600): reading it requires filesystem access to the desktop,
 * which the threat model already treats as full authority (ADR-002 — the QR
 * secret is displayed on this very screen).
 *
 * Electron-import-free so unit tests run in plain Node.
 */

export interface TrustedDevice {
  /** The mobile's install-scoped UUID (hello `deviceId`, ADR-005) — the resume lookup key. */
  deviceId: string
  /** Phone model from the hello user-agent — display only. */
  name: string
  /** Derived trustKey (HKDF of the QR session's secret) — the resume HMAC key. */
  key: Buffer
  /** Unix seconds of the QR pairing that created this trust. */
  createdAt: number
  /** Unix seconds of the last successful (re)connection. */
  lastConnectedAt: number
}

interface StoredDocument {
  version: 1
  deviceId: string
  name: string
  key: string
  createdAt: number
  lastConnectedAt: number
}

export interface TrustStoreOptions {
  /** Path of the trust document — injected (userData in the app, temp dirs in tests). */
  file: () => string
  /** Injected clock for tests. */
  now?: () => number
}

export class TrustStore {
  private readonly file: () => string
  private readonly now: () => number
  private device: TrustedDevice | null = null
  private loaded = false

  constructor(options: TrustStoreOptions) {
    this.file = options.file
    this.now = options.now ?? Date.now
  }

  /**
   * Read the document once at startup (before the signaling server can accept
   * a resume). Corrupt or wrong-shaped content logs and degrades to empty.
   */
  load(): void {
    if (this.loaded) return
    this.loaded = true
    let raw: string
    try {
      raw = readFileSync(this.file(), 'utf8')
    } catch {
      return // no document yet — nothing trusted
    }
    try {
      const doc = JSON.parse(raw) as StoredDocument
      const key = Buffer.from(doc.key, 'base64url')
      if (
        doc.version !== 1 ||
        typeof doc.deviceId !== 'string' || doc.deviceId.length === 0 || doc.deviceId.length > 128 ||
        typeof doc.name !== 'string' ||
        key.length !== 32 ||
        typeof doc.createdAt !== 'number' ||
        typeof doc.lastConnectedAt !== 'number'
      ) {
        throw new Error('wrong shape')
      }
      this.device = { deviceId: doc.deviceId, name: doc.name, key, createdAt: doc.createdAt, lastConnectedAt: doc.lastConnectedAt }
    } catch (error) {
      // The store grants a convenience, never correctness: losing it costs one re-scan.
      console.warn(`[trust] unreadable trust document (${String(error)}) — treating as no trusted device`)
      this.device = null
    }
  }

  /** The trusted phone, or null — only meaningful after [load]. */
  get(): TrustedDevice | null {
    return this.device
  }

  /** Create or replace the trust (first QR pairing with a deviceId, or a re-pair rotating the key). */
  save(deviceId: string, name: string, key: Buffer): void {
    const now = Math.floor(this.now() / 1000)
    this.device = { deviceId, name, key, createdAt: this.device?.createdAt ?? now, lastConnectedAt: now }
    this.persist()
  }

  /** Stamp the last successful resume — the entry itself is unchanged. */
  touch(): void {
    if (this.device === null) return
    this.device.lastConnectedAt = Math.floor(this.now() / 1000)
    this.persist()
  }

  /** Revoke entirely (Disconnect, `bye forget`) — the phone re-pairs with a fresh QR. */
  clear(): void {
    if (this.device === null) return
    this.device = null
    this.persist()
  }

  private persist(): void {
    if (this.device === null) {
      try {
        writeFileSync(this.file(), '', { mode: 0o600 })
      } catch (error) {
        console.warn(`[trust] clearing the trust document failed (${String(error)})`)
      }
      return
    }
    const doc: StoredDocument = {
      version: 1,
      deviceId: this.device.deviceId,
      name: this.device.name,
      key: this.device.key.toString('base64url'),
      createdAt: this.device.createdAt,
      lastConnectedAt: this.device.lastConnectedAt
    }
    try {
      writeFileSync(this.file(), JSON.stringify(doc), { mode: 0o600 })
      chmodSync(this.file(), 0o600)
    } catch (error) {
      console.warn(`[trust] writing the trust document failed (${String(error)})`)
    }
  }
}

/**
 * The desktop's own persistent identity (pairing.md §Persistent sessions):
 * a random UUID generated once and sent as `auth-ok.desktopId` so the phone
 * can key and name its saved-desktop entry. Like the mobile's deviceId
 * (ADR-005) it is an identifier, never a secret, and it deliberately carries
 * nothing beyond "this install". A lost file regenerates the id — the phone
 * then treats the next pairing as a new desktop (the stale entry is
 * forgettable), an accepted cost for a machine-local file.
 */
export function loadOrCreateDesktopId(file: () => string): string {
  try {
    const stored = readFileSync(file(), 'utf8').trim()
    if (stored.length > 0 && stored.length <= 128) return stored
  } catch {
    // no file yet — generate below
  }
  const fresh = randomUUID()
  try {
    writeFileSync(file(), fresh, { mode: 0o600 })
  } catch (error) {
    console.warn(`[trust] persisting the desktop id failed (${String(error)})`)
  }
  return fresh
}
