import { describe, expect, it } from 'vitest'
import { asIceMessage, asSdpMessage, asStreamSize, asVcFrame } from './messages'

/** The IPC boundary rules (messages.ts): untrusted input is shaped or rejected — never trusted. */

describe('asVcFrame', () => {
  const frame = (w: number, h: number, bpp: number) => ({
    data: new Uint8Array(w * h * bpp),
    width: w,
    height: h
  })

  it('accepts a frame whose byte length agrees with dims × pixel format', () => {
    expect(asVcFrame(frame(8, 4, 3), 3)).toEqual({ data: expect.any(Uint8Array), width: 8, height: 4 })
    expect(asVcFrame(frame(8, 4, 4), 4)).not.toBeNull()
  })

  it('rejects a byte length that matches the other pixel format (3 vs 4 bpp)', () => {
    expect(asVcFrame(frame(8, 4, 3), 4)).toBeNull()
  })

  it('rejects non-integer, non-positive dims, wrong payload shape', () => {
    expect(asVcFrame(frame(8.5, 4, 3), 3)).toBeNull()
    expect(asVcFrame(frame(0, 4, 3), 3)).toBeNull()
    expect(asVcFrame({ data: 'not bytes', width: 8, height: 4 }, 3)).toBeNull()
    expect(asVcFrame(null, 3)).toBeNull()
  })
})

describe('asSdpMessage', () => {
  it('accepts a known pc id with a non-empty sdp', () => {
    expect(asSdpMessage({ pc: 'media', sdp: 'v=0' })).toEqual({ pc: 'media', sdp: 'v=0' })
  })

  it('rejects unknown pc ids and empty sdp strings', () => {
    expect(asSdpMessage({ pc: 'unknown', sdp: 'v=0' })).toBeNull()
    expect(asSdpMessage({ pc: 'media', sdp: '' })).toBeNull()
    expect(asSdpMessage({ pc: 'media' })).toBeNull()
  })
})

describe('asIceMessage', () => {
  it('accepts any candidate value — null means end-of-gathering', () => {
    expect(asIceMessage({ pc: 'mic', candidate: { candidate: 'x' } })).not.toBeNull()
    expect(asIceMessage({ pc: 'mic', candidate: null })).toEqual({ pc: 'mic', candidate: null })
  })

  it('rejects a missing candidate field or unknown pc', () => {
    expect(asIceMessage({ pc: 'mic' })).toBeNull()
    expect(asIceMessage({ pc: 'nope', candidate: null })).toBeNull()
  })
})

describe('asStreamSize', () => {
  it('accepts positive integer dims', () => {
    expect(asStreamSize({ width: 1920, height: 1080 })).toEqual({ width: 1920, height: 1080 })
  })

  it('rejects zero/negative/float dims and non-objects', () => {
    expect(asStreamSize({ width: 0, height: 1080 })).toBeNull()
    expect(asStreamSize({ width: 1920.5, height: 1080 })).toBeNull()
    expect(asStreamSize('1920x1080')).toBeNull()
  })
})
