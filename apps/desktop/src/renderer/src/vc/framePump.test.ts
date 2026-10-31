import { describe, expect, it } from 'vitest'
import { FramePump, type PumpCanvas, type PumpFrame, type PumpFrameSource } from './framePump'

/**
 * The pump orchestration with everything faked: a source that hands frames
 * over, canvases that "draw" and "read", and a send spy. 1:1 forwarding,
 * close() discipline, scratch reuse, restart, and context-loss recovery are
 * behavior — the WebGL/MSTP production halves are the hardware boundary
 * (verified on the Windows rig).
 */

function makeFrame(): PumpFrame & { closed: boolean } {
  return { closed: false, close() { this.closed = true } }
}

function fakeSource(): PumpFrameSource & { deliver(frame: PumpFrame): void; started: number; stopped: number } {
  let onFrame: ((frame: PumpFrame) => void) | null = null
  const state = {
    started: 0,
    stopped: 0,
    deliver: (frame: PumpFrame) => onFrame?.(frame),
    start: (handler: (frame: PumpFrame) => void) => {
      state.started += 1
      onFrame = handler
    },
    // Like the real reader.cancel() (async), frames may still arrive after
    // stop — the pump must drop them through its own guard, not rely on us.
    stop: () => {
      state.stopped += 1
    }
  }
  return state
}

function fakeCanvas(): PumpCanvas & { drawn: PumpFrame[]; reads: number; lost: boolean } {
  const state = {
    drawn: [] as PumpFrame[],
    reads: 0,
    lost: false,
    drawFrame: (frame: PumpFrame) => state.drawn.push(frame),
    readPixels: (out: Uint8Array) => {
      state.reads += 1
      out.fill(7)
    },
    isLost: () => state.lost
  }
  return state
}

function makePump() {
  const sources = [] as Array<ReturnType<typeof fakeSource>>
  const canvases = [] as Array<ReturnType<typeof fakeCanvas>>
  const sent: Array<{ bytes: Uint8Array; width: number; height: number }> = []
  const failures: string[] = []
  const pump = new FramePump({
    createSource: () => {
      const source = fakeSource()
      sources.push(source)
      return source
    },
    createCanvas: (_width, _height, _format) => {
      const canvas = fakeCanvas()
      canvases.push(canvas)
      return canvas
    },
    send: (bytes, width, height) => sent.push({ bytes: new Uint8Array(bytes), width, height }),
    reportFailure: (reason) => failures.push(reason),
    log: () => undefined
  })
  return { pump, sources, canvases, sent, failures }
}

describe('FramePump (ADR-007: decode-side source, 1:1 frames, no cap)', () => {
  it('forwards every delivered frame 1:1 and closes each one', () => {
    const { pump, sources, canvases, sent } = makePump()
    pump.start({}, 8, 4, 'bgr24')
    const frames = [makeFrame(), makeFrame(), makeFrame()]
    for (const frame of frames) sources[0].deliver(frame)
    expect(canvases[0].drawn).toEqual(frames)
    expect(sent).toHaveLength(3)
    expect(frames.every((frame) => frame.closed)).toBe(true)
  })

  it('the macOS bgra format sizes the scratch to 4 bytes/pixel', () => {
    const { pump, sources, sent } = makePump()
    pump.start({}, 8, 4, 'bgra') // 8*4*4 = 128 bytes
    sources[0].deliver(makeFrame())
    expect(sent[0].bytes).toHaveLength(128)
  })

  it('sends buffers sized to the camera dims via one reused scratch buffer', () => {
    const { pump, sources, sent } = makePump()
    pump.start({}, 8, 4, 'bgr24') // 8*4*3 = 96 bytes
    sources[0].deliver(makeFrame())
    sources[0].deliver(makeFrame())
    expect(sent[0].bytes).toHaveLength(96)
    expect(sent[0].width).toBe(8)
    expect(sent[0].height).toBe(4)
    expect(sent[0].bytes).toEqual(sent[1].bytes) // same scratch content, fresh copies
  })

  it('stop() stops the source and drops in-flight frames silently (still closed)', () => {
    const { pump, sources, sent } = makePump()
    pump.start({}, 8, 4, 'bgr24')
    pump.stop()
    expect(sources[0].stopped).toBe(1)
    expect(pump.active).toBe(false)
    const late = makeFrame()
    sources[0].deliver(late)
    expect(sent).toHaveLength(0)
    expect(late.closed).toBe(true)
  })

  it('restarting stops the previous source and pumps into a fresh canvas pair', () => {
    const { pump, sources, canvases, sent } = makePump()
    pump.start({}, 8, 4, 'bgr24')
    pump.start({}, 16, 8, 'bgr24')
    expect(sources).toHaveLength(2)
    expect(sources[0].stopped).toBe(1)
    sources[1].deliver(makeFrame())
    expect(sent).toEqual([{ bytes: expect.any(Uint8Array), width: 16, height: 8 }])
    expect(canvases).toHaveLength(2)
  })

  it('recovers from canvas context loss, bounded, then fails honestly', () => {
    const { pump, sources, canvases, sent, failures } = makePump()
    pump.start({}, 8, 4, 'bgr24')
    // Lose + recover three times (the default bound)…
    for (let attempt = 0; attempt < 3; attempt++) {
      canvases[attempt].lost = true
      sources[0].deliver(makeFrame())
      expect(failures).toEqual([]) // each loss was recoverable
    }
    expect(sent).toHaveLength(3)
    expect(canvases).toHaveLength(4) // initial + 3 recoveries
    // The fourth loss is over the bound: honest failure, pump stops.
    canvases[3].lost = true
    sources[0].deliver(makeFrame())
    expect(failures).toEqual(['canvas-lost'])
    expect(pump.active).toBe(false)
    expect(sources[0].stopped).toBe(1)
  })
})
