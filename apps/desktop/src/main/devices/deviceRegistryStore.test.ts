import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { loadDevices, saveDevices } from './deviceRegistryStore'
import type { RegisteredDevice } from '../../shared/types'

/**
 * The registry store's persistence contract (ADR-005 addendum): entries
 * round-trip verbatim; a missing or unreadable document degrades to an empty
 * registry — the list is convenience, never trusted state.
 */
describe('device registry store', () => {
  const dirs: string[] = []

  afterEach(() => {
    for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true })
  })

  const tempFile = (name: string): string => {
    const dir = mkdtempSync(join(tmpdir(), 'zfc-devices-'))
    dirs.push(dir)
    return join(dir, name)
  }

  const entry: RegisteredDevice = {
    deviceId: 'aaaa1111-2222-3333-4444-555566667777',
    name: 'Pixel 8',
    ua: 'ZeroFrictionCast/0.1.0',
    remote: '192.168.1.5',
    state: 'offline',
    connectedAtMs: 1_000,
    lastSeenAtMs: 2_000,
  }

  it('round-trips entries verbatim', () => {
    const path = tempFile('devices.json')
    saveDevices(path, [entry])
    expect(loadDevices(path)).toEqual([entry])
    // Human-readable document (repo convention: readable storage shapes).
    expect(JSON.parse(readFileSync(path, 'utf8'))).toEqual([entry])
  })

  it('loads nothing when the file does not exist', () => {
    expect(loadDevices(tempFile('absent.json'))).toEqual([])
  })

  it('degrades a corrupt document to an empty registry', () => {
    const path = tempFile('broken.json')
    writeFileSync(path, '{not json')
    expect(loadDevices(path)).toEqual([])
  })

  it('degrades a wrong-shape document to an empty registry', () => {
    const path = tempFile('wrong.json')
    writeFileSync(path, JSON.stringify({ oops: true }))
    expect(loadDevices(path)).toEqual([])
  })

  it('drops malformed entries but keeps valid ones', () => {
    const path = tempFile('mixed.json')
    writeFileSync(path, JSON.stringify([entry, { name: 'not a device' }, null]))
    expect(loadDevices(path)).toEqual([entry])
  })
})
