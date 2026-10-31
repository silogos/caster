import { describe, expect, it, vi } from 'vitest'
import {
  findCableOutputDevice,
  isRoutingMode,
  loadRoutingMode,
  persistRoutingMode
} from './routing'

/**
 * Behavior tests for the pure parts of the audio output routing (issue #54):
 * the device matcher that finds the virtual cable (BlackHole) among output
 * devices, and the persisted routing mode's restore rules.
 */

const device = (overrides: Partial<{ deviceId: string; kind: string; label: string }> = {}) => ({
  deviceId: 'default-id',
  kind: 'audiooutput',
  label: 'Some device',
  ...overrides
})

describe('findCableOutputDevice', () => {
  it('finds BlackHole among output devices, case-insensitive', () => {
    const blackhole = device({ deviceId: 'bh-id', label: 'BlackHole 2ch' })
    const result = findCableOutputDevice([
      device({ deviceId: 'spk', label: 'MacBook Pro Speakers' }),
      blackhole
    ])
    expect(result).toEqual(blackhole)
  })

  it('matches lowercase and suffixed labels ("blackhole-2ch")', () => {
    const result = findCableOutputDevice([device({ deviceId: 'bh', label: 'blackhole-2ch output' })])
    expect(result?.deviceId).toBe('bh')
  })

  it('ignores input devices — BlackHole appears on both sides, but only the output is ours', () => {
    const result = findCableOutputDevice([
      device({ deviceId: 'bh-in', kind: 'audioinput', label: 'BlackHole 2ch' })
    ])
    expect(result).toBeNull()
  })

  it('ignores devices without a usable deviceId', () => {
    const result = findCableOutputDevice([device({ deviceId: '', label: 'BlackHole 2ch' })])
    expect(result).toBeNull()
  })

  it('returns null when no cable device is installed', () => {
    const result = findCableOutputDevice([
      device({ deviceId: 'spk', label: 'MacBook Pro Speakers' })
    ])
    expect(result).toBeNull()
  })

  it('returns the first match so behavior stays stable across launches', () => {
    const first = device({ deviceId: 'bh-2ch', label: 'BlackHole 2ch' })
    const second = device({ deviceId: 'bh-64ch', label: 'BlackHole 64ch' })
    expect(findCableOutputDevice([first, second])?.deviceId).toBe('bh-2ch')
  })
})

describe('routing mode persistence', () => {
  it('defaults to speakers with no storage or nothing stored', () => {
    expect(loadRoutingMode(undefined, vi.fn())).toBe('speakers')
    const storage = { getItem: () => null, setItem: vi.fn() }
    expect(loadRoutingMode(storage, vi.fn())).toBe('speakers')
  })

  it('round-trips a stored mode', () => {
    const storage = { getItem: () => JSON.stringify({ mode: 'cable' }), setItem: vi.fn() }
    expect(loadRoutingMode(storage, vi.fn())).toBe('cable')
  })

  it('an invalid or corrupt stored mode falls back to speakers with a warning', () => {
    const warn = vi.fn()
    const invalid = { getItem: () => JSON.stringify({ mode: 'bluetooth' }), setItem: vi.fn() }
    expect(loadRoutingMode(invalid, warn)).toBe('speakers')
    expect(warn).toHaveBeenCalledWith('warn', 'stored routing mode was invalid — using speakers', expect.anything())

    warn.mockClear()
    const corrupt = { getItem: () => '{not json', setItem: vi.fn() }
    expect(loadRoutingMode(corrupt, warn)).toBe('speakers')
    expect(warn).toHaveBeenCalledWith('warn', 'stored routing mode was unreadable — using speakers', expect.anything())
  })

  it('isRoutingMode accepts exactly the three modes', () => {
    expect(isRoutingMode('speakers')).toBe(true)
    expect(isRoutingMode('cable')).toBe(true)
    expect(isRoutingMode('both')).toBe(true)
    expect(isRoutingMode('bluetooth')).toBe(false)
    expect(isRoutingMode(42)).toBe(false)
  })

  it('persistRoutingMode writes the mode for the next launch', () => {
    const storage = { getItem: () => null, setItem: vi.fn() }
    persistRoutingMode(storage, 'both')
    expect(storage.setItem).toHaveBeenCalledWith('zfc.audio-routing.v1', JSON.stringify({ mode: 'both' }))
  })

  it('persisting is a no-op without storage (in-memory mixer)', () => {
    expect(() => persistRoutingMode(undefined, 'cable')).not.toThrow()
  })
})
