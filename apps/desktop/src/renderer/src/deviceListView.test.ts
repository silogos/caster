import { describe, expect, it } from 'vitest'
import { deviceListView } from './deviceListView'
import type { RegisteredDevice } from '../../shared/types'

const online: RegisteredDevice = {
  deviceId: 'aaaa1111-2222-3333-4444-555566667777',
  name: 'Pixel 8',
  ua: 'ua',
  remote: '192.168.1.5',
  state: 'online',
  connectedAtMs: 60_000,
  lastSeenAtMs: 60_000,
}

const offline: RegisteredDevice = {
  deviceId: null,
  name: 'Android device',
  ua: 'ua',
  remote: '192.168.1.9',
  state: 'offline',
  connectedAtMs: 30_000,
  lastSeenAtMs: 30_000,
}

describe('device list view (ADR-005)', () => {
  it('shows an online device with its short identity', () => {
    const view = deviceListView([online], 60_000)
    expect(view.heading).toBe('Devices')
    expect(view.rows).toEqual([
      { state: 'online', label: 'Pixel 8', detail: 'aaaa1111 · connected' },
    ])
  })

  it('shows an offline device with how long ago it was seen', () => {
    const view = deviceListView([offline], 150_000)
    expect(view.rows[0]?.state).toBe('offline')
    expect(view.rows[0]?.label).toBe('Android device')
    // Unidentified client (no deviceId) — the address-keyed fallback.
    expect(view.rows[0]?.detail).toBe('unidentified · last seen 2m ago')
  })

  it('orders rows as the registry handed them (most recent first)', () => {
    const view = deviceListView([offline, online], 60_000)
    expect(view.rows.map((row) => row.label)).toEqual(['Android device', 'Pixel 8'])
  })
})
