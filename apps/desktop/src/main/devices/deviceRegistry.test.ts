import { describe, expect, it } from 'vitest'
import { DeviceRegistry } from './deviceRegistry'

/**
 * The registry's state transitions (ADR-005): connect marks online, any
 * disconnect marks offline (never deletes), unidentified clients key on
 * their address, and the list is most-recently-seen first. Pure — the clock
 * is injected.
 */
describe('device registry (ADR-005)', () => {
  it('marks a connected device online with its visit time', () => {
    const registry = new DeviceRegistry()
    const entry = registry.markConnected(
      { deviceId: 'aaaa1111-2222-3333-4444-555566667777', name: 'Pixel 8', ua: 'ua', remote: '192.168.1.5' },
      1_000,
    )
    expect(entry.state).toBe('online')
    expect(entry.connectedAtMs).toBe(1_000)
    expect(entry.deviceId).toBe('aaaa1111-2222-3333-4444-555566667777')
  })

  it('marks the same device offline without deleting it', () => {
    const registry = new DeviceRegistry()
    registry.markConnected({ deviceId: 'aaaa1111-2222-3333-4444-555566667777', name: 'Pixel 8', ua: 'ua', remote: '192.168.1.5' }, 1_000)
    registry.markOffline({ deviceId: 'aaaa1111-2222-3333-4444-555566667777', remote: '192.168.1.5' }, 2_000)

    const [entry] = registry.list()
    expect(entry.state).toBe('offline')
    expect(entry.lastSeenAtMs).toBe(2_000)
    expect(registry.list()).toHaveLength(1)
  })

  it('brings a returning device back online with a fresh visit', () => {
    const registry = new DeviceRegistry()
    registry.markConnected({ deviceId: 'aaaa1111-2222-3333-4444-555566667777', name: 'Pixel 8', ua: 'old', remote: '192.168.1.5' }, 1_000)
    registry.markOffline({ deviceId: 'aaaa1111-2222-3333-4444-555566667777', remote: '192.168.1.5' }, 2_000)

    const entry = registry.markConnected(
      { deviceId: 'aaaa1111-2222-3333-4444-555566667777', name: 'Pixel 8', ua: 'new', remote: '192.168.1.9' },
      3_000,
    )
    expect(entry.state).toBe('online')
    expect(entry.connectedAtMs).toBe(3_000)
    expect(registry.list()).toHaveLength(1)
  })

  it('keys unidentified clients on their address', () => {
    const registry = new DeviceRegistry()
    registry.markConnected({ name: 'Android device', ua: 'ua', remote: '192.168.1.5' }, 1_000)
    registry.markOffline({ remote: '192.168.1.5' }, 2_000)

    const [entry] = registry.list()
    expect(entry.deviceId).toBeNull()
    expect(entry.state).toBe('offline')
  })

  it('lists the most recently seen device first', () => {
    const registry = new DeviceRegistry()
    registry.markConnected({ deviceId: 'aaaa1111-2222-3333-4444-555566667777', name: 'First', ua: 'ua', remote: '192.168.1.5' }, 1_000)
    registry.markConnected({ deviceId: 'bbbb2222-2222-3333-4444-555566667777', name: 'Second', ua: 'ua', remote: '192.168.1.6' }, 2_000)

    expect(registry.list().map((entry) => entry.name)).toEqual(['Second', 'First'])
  })

  it('ignores an offline for a device it never saw', () => {
    const registry = new DeviceRegistry()
    registry.markOffline({ deviceId: 'cccc3333-2222-3333-4444-555566667777', remote: '192.168.1.7' }, 1_000)
    expect(registry.list()).toHaveLength(0)
  })
})
