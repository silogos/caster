import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { TrustStore, loadOrCreateDesktopId } from './trustStore'

/**
 * The persistent trust (pairing.md §Persistent sessions, ADR-006): one
 * document on disk, degraded (never crashed) on anything unreadable, and the
 * desktop's own stable id. File-backed via injected paths — no Electron.
 */

let dir = ''
/** Injectable clock returns milliseconds (Date.now-shaped); tests read seconds. */
const nowMs: () => number = () => 1_800_000_000_000

beforeAll(() => {
  dir = mkdtempSync(join(tmpdir(), 'zfc-trust-'))
})

afterAll(() => {
  rmSync(dir, { recursive: true, force: true })
})

const trustFile = (): string => join(dir, 'trust.json')

describe('TrustStore', () => {
  it('starts empty and saves/loads one trusted device across instances', () => {
    const store = new TrustStore({ file: trustFile, now: nowMs })
    store.load()
    expect(store.get()).toBeNull()

    const key = Buffer.alloc(32, 0xab)
    store.save('device-uuid-1', 'Pixel 8', key)
    expect(store.get()).toMatchObject({ deviceId: 'device-uuid-1', name: 'Pixel 8' })
    expect(store.get()?.key.equals(key)).toBe(true)

    // A fresh instance (next launch) reads the same entry back.
    const relaunched = new TrustStore({ file: trustFile, now: nowMs })
    relaunched.load()
    expect(relaunched.get()?.deviceId).toBe('device-uuid-1')
  })

  it('keeps createdAt across a re-pair and rotates the key', () => {
    const store = new TrustStore({ file: trustFile, now: nowMs })
    store.load()
    const created = store.get()?.createdAt
    store.save('device-uuid-1', 'Pixel 8', Buffer.alloc(32, 0xcd))
    expect(store.get()?.createdAt).toBe(created)
    expect(store.get()?.key.equals(Buffer.alloc(32, 0xcd))).toBe(true)
  })

  it('touches lastConnectedAt without changing the entry', () => {
    const store = new TrustStore({ file: trustFile, now: () => 1_800_000_500_000 })
    store.load()
    store.touch()
    expect(store.get()?.lastConnectedAt).toBe(1_800_000_500)
    expect(store.get()?.deviceId).toBe('device-uuid-1')
  })

  it('clears trust and persists the empty state', () => {
    const store = new TrustStore({ file: trustFile, now: nowMs })
    store.load()
    store.clear()
    expect(store.get()).toBeNull()

    const relaunched = new TrustStore({ file: trustFile, now: nowMs })
    relaunched.load()
    expect(relaunched.get()).toBeNull()
  })

  it('degrades to empty with a corrupt or wrong-shaped document', () => {
    for (const content of ['not json', '{}', '{"version":2}', '{"version":1,"deviceId":"","name":"x","key":"AA","createdAt":1,"lastConnectedAt":1}']) {
      writeFileSync(trustFile(), content)
      const store = new TrustStore({ file: trustFile, now: nowMs })
      store.load()
      expect(store.get()).toBeNull()
    }
  })

  it('writes the document readable only by the owner', () => {
    const store = new TrustStore({ file: trustFile, now: nowMs })
    store.load()
    store.save('device-uuid-2', 'Pixel 9', Buffer.alloc(32, 1))
    const mode = statSync(trustFile()).mode & 0o777
    expect(mode).toBe(0o600)
    expect(JSON.parse(readFileSync(trustFile(), 'utf8')).deviceId).toBe('device-uuid-2')
  })
})

describe('loadOrCreateDesktopId', () => {
  it('creates once and returns the same id afterwards', () => {
    const file = (): string => join(dir, 'desktop-id')
    const first = loadOrCreateDesktopId(file)
    expect(first).toMatch(/^[0-9a-f-]{36}$/)
    expect(loadOrCreateDesktopId(file)).toBe(first)
    expect(readFileSync(file(), 'utf8').trim()).toBe(first)
  })

  it('regenerates when the file is unreadable', () => {
    const file = (): string => join(dir, 'desktop-id-bad')
    writeFileSync(file(), '')
    const fresh = loadOrCreateDesktopId(file)
    expect(fresh).toMatch(/^[0-9a-f-]{36}$/)
  })
})
