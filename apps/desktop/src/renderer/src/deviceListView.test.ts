import { describe, expect, it } from 'vitest'
import { deviceHomeStage, deviceListView } from './deviceListView'
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

describe('device home stage (ADR-005 addendum)', () => {
  it('shows the qr when no device has ever connected', () => {
    expect(deviceHomeStage(0, false)).toBe('qr')
  })

  it('shows the devices list once a device is known', () => {
    expect(deviceHomeStage(1, false)).toBe('devices')
  })

  it("lets the user request the qr explicitly ('Add new device')", () => {
    expect(deviceHomeStage(3, true)).toBe('qr')
  })
})

describe('device list view (ADR-005)', () => {
  it('shows an online device with its short identity and forget key', () => {
    const view = deviceListView([online], 60_000)
    expect(view.heading).toBe('Devices')
    expect(view.rows).toEqual([
      { key: 'aaaa1111-2222-3333-4444-555566667777', state: 'online', label: 'Pixel 8', detail: 'aaaa1111 · connected' },
    ])
  })

  it('shows an offline device with how long ago it was seen, addressed by ip when unidentified', () => {
    const view = deviceListView([offline], 150_000)
    expect(view.rows[0]?.state).toBe('offline')
    expect(view.rows[0]?.label).toBe('Android device')
    // Unidentified client (no deviceId) — the address-keyed fallback.
    expect(view.rows[0]?.detail).toBe('unidentified · last seen 2m ago')
    expect(view.rows[0]?.key).toBe('ip:192.168.1.9')
  })

  it('orders rows as the registry handed them (most recent first)', () => {
    const view = deviceListView([offline, online], 60_000)
    expect(view.rows.map((row) => row.label)).toEqual(['Android device', 'Pixel 8'])
  })
})
