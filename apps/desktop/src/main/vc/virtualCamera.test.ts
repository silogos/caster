import { describe, expect, it } from 'vitest'
import { VirtualCameraController, type PumpCommand, type VcStatusPush } from './virtualCamera'
import type { VcCamera, VcCameraBackend } from './softcamLibrary'
import type { SoftcamRegistration } from './registration'

/**
 * The virtual camera state machine (ADR-007) with every collaborator faked:
 * platform support, registration (UAC), the camera backend, and the push
 * channels. The real backend paths (softcam DLL, Syphon helper) are exercised
 * on their platforms' rigs only.
 */

type RegisterResult = 'registered' | 'declined' | 'failed'

function fakeBackend(createSucceeds = true, pixelFormat: 'bgr24' | 'bgra' = 'bgr24', idleNoSignal = false): VcCameraBackend & { created: string[]; closed: string[]; frames: Uint8Array[] } {
  const state = { created: [] as string[], closed: [] as string[], frames: [] as Uint8Array[] }
  let n = 0
  return {
    support: 'ok',
    pixelFormat,
    idleNoSignal,
    ...state,
    createCamera(width, height): VcCamera | null {
      if (!createSucceeds) return null
      n += 1
      const id = `cam-${n}:${width}x${height}`
      state.created.push(id)
      return {
        sendFrame: (bytes) => state.frames.push(bytes),
        close: () => state.closed.push(id)
      }
    }
  }
}

function fakeRegistration(initial: boolean, result: RegisterResult = 'registered'): SoftcamRegistration & { registerCalls: number } {
  let registered = initial
  const fake = {
    registerCalls: 0,
    get filesPresent(): boolean {
      return true
    },
    isRegistered: async (): Promise<boolean> => registered,
    register: async (): Promise<RegisterResult> => {
      fake.registerCalls += 1
      if (result === 'registered') registered = true
      return result
    }
  }
  return fake as unknown as SoftcamRegistration & { registerCalls: number }
}

function makeController(options: {
  backend?: VcCameraBackend
  registration?: SoftcamRegistration
  streamSize?: { width: number; height: number } | null
} = {}) {
  const statuses: VcStatusPush[] = []
  const pumps: PumpCommand[] = []
  const backend = options.backend ?? fakeBackend()
  const controller = new VirtualCameraController({
    backend,
    registration: options.registration ?? fakeRegistration(true),
    // Explicit null must survive (no ?? fallback — it would resurrect a default size).
    streamSize: () => (options.streamSize === undefined ? { width: 1280, height: 720 } : options.streamSize),
    sendStatus: (status) => statuses.push(status),
    onPumpCommand: (command) => pumps.push(command),
    log: () => undefined
  })
  return { controller, statuses, pumps, backend }
}

describe('VirtualCameraController (ADR-007 state machine)', () => {
  it('reports unavailable when the platform or files are missing, and never registers', async () => {
    const registration = fakeRegistration(false)
    const { controller, statuses } = makeController({
      backend: { support: 'unsupported-platform', pixelFormat: 'bgr24', idleNoSignal: false, createCamera: () => null },
      registration
    })
    await controller.onVcQuery()
    expect(statuses).toEqual([{ available: false, needsRegistration: false, active: false }])
    await controller.onVcStart()
    expect(registration.registerCalls).toBe(0)
    expect(statuses).toHaveLength(2)
  })

  it('answers vc-query with needsRegistration before the one-time setup', async () => {
    const { controller, statuses } = makeController({ registration: fakeRegistration(false) })
    await controller.onVcQuery()
    expect(statuses).toEqual([{ available: true, needsRegistration: true, active: false }])
  })

  it('vc-start on needsRegistration registers (UAC) then creates the camera and reports active', async () => {
    const registration = fakeRegistration(false)
    const { controller, statuses, pumps, backend } = makeController({ registration })
    await controller.onVcStart()
    expect(registration.registerCalls).toBe(1)
    expect((backend as ReturnType<typeof fakeBackend>).created).toEqual(['cam-1:1280x720'])
    expect(pumps).toEqual([{ mode: 'frames', width: 1280, height: 720, pixelFormat: 'bgr24' }])
    expect(statuses.at(-1)).toEqual({ available: true, needsRegistration: false, active: true })
  })

  it('declined UAC stays needsRegistration — an honest user decision, no error', async () => {
    const { controller, statuses, pumps } = makeController({
      registration: fakeRegistration(false, 'declined')
    })
    await controller.onVcStart()
    expect(pumps).toEqual([])
    expect(statuses.at(-1)).toEqual({ available: true, needsRegistration: true, active: false })
  })

  it('caps stream dims to the softcam rule at creation (1920×1080, multiples of 4)', async () => {
    const { controller, pumps, backend } = makeController({ streamSize: { width: 2560, height: 1440 } })
    await controller.onVcStart()
    expect((backend as ReturnType<typeof fakeBackend>).created).toEqual(['cam-1:1920x1080'])
    expect(pumps).toEqual([{ mode: 'frames', width: 1920, height: 1080, pixelFormat: 'bgr24' }])
  })

  it('refuses to start without a stream size (toggle is cast-scoped)', async () => {
    const { controller, statuses } = makeController({ streamSize: null })
    await controller.onVcStart()
    expect(statuses.at(-1)).toEqual({ available: true, needsRegistration: false, active: false })
  })

  it('feeds frames while active, drops mismatched ones, ignores them otherwise', async () => {
    const { controller, backend } = makeController()
    controller.onFrame(new Uint8Array(1280 * 720 * 3)) // before start — ignored
    await controller.onVcStart()
    const cam = backend as ReturnType<typeof fakeBackend>
    controller.onFrame(new Uint8Array(1280 * 720 * 3))
    controller.onFrame(new Uint8Array(42)) // wrong size — dropped, never reaches native
    expect(cam.frames).toHaveLength(1)
    expect(cam.frames[0]).toHaveLength(1280 * 720 * 3)
  })

  it('vc-stop tears the camera down and returns to available (status pushed, pump stopped)', async () => {
    const { controller, statuses, pumps, backend } = makeController()
    await controller.onVcStart()
    await controller.onVcStop()
    const cam = backend as ReturnType<typeof fakeBackend>
    expect(cam.closed).toEqual(['cam-1:1280x720'])
    expect(pumps.at(-1)).toEqual({ mode: 'off' })
    expect(statuses.at(-1)).toEqual({ available: true, needsRegistration: false, active: false })
    await controller.onVcStop() // idempotent
    expect(cam.closed).toHaveLength(1)
  })

  it('cast-gone and dispose both delete the camera (every exit path, no leak)', async () => {
    const a = makeController()
    await a.controller.onVcStart()
    a.controller.onCastGone()
    expect((a.backend as ReturnType<typeof fakeBackend>).closed).toHaveLength(1)
    expect(a.statuses.at(-1)?.active).toBe(false)

    const b = makeController()
    await b.controller.onVcStart()
    b.controller.dispose() // app quit — no status push needed
    expect((b.backend as ReturnType<typeof fakeBackend>).closed).toHaveLength(1)
    expect(b.pumps.at(-1)).toEqual({ mode: 'off' })
  })

  it('ignores re-entrant starts while the UAC registration is in flight', async () => {
    // A holder object so TS's control-flow analysis sees the closure assignment.
    const gate: { release: null | (() => void) } = { release: null }
    const registration = fakeRegistration(false)
    registration.register = () =>
      new Promise<RegisterResult>((resolve) => {
        gate.release = () => resolve('registered')
      })
    const { controller, backend } = makeController({ registration })
    const first = controller.onVcStart()
    const second = controller.onVcStart()
    // The register() call only happens after ready()'s microtasks — wait for
    // the pending promise to actually be armed before releasing it.
    while (gate.release === null) {
      await new Promise((resolve) => setTimeout(resolve, 1))
    }
    gate.release()
    await Promise.all([first, second])
    expect((backend as ReturnType<typeof fakeBackend>).created).toHaveLength(1)
  })

  it('vc-start while already active is a no-op', async () => {
    const { controller, backend } = makeController()
    await controller.onVcStart()
    await controller.onVcStart()
    expect((backend as ReturnType<typeof fakeBackend>).created).toHaveLength(1)
  })

  it('a bgra backend (macOS/Syphon) validates 4-byte frames and rides the pump command', async () => {
    const syphon = fakeBackend(true, 'bgra')
    const { controller, statuses, pumps } = makeController({ backend: syphon })
    await controller.onVcStart()
    expect(pumps).toEqual([{ mode: 'frames', width: 1280, height: 720, pixelFormat: 'bgra' }])
    expect(controller.activePixelFormat).toBe('bgra')
    controller.onFrame(new Uint8Array(1280 * 720 * 4)) // the format's frame size
    controller.onFrame(new Uint8Array(1280 * 720 * 3)) // a bgr24 frame — dropped
    const cam = syphon as ReturnType<typeof fakeBackend>
    expect(cam.frames).toHaveLength(1)
    await controller.onVcStop()
    expect(statuses.at(-1)?.active).toBe(false)
    expect(controller.activePixelFormat).toBeNull()
  })

  it('a dead backend tears the camera down honestly (the Syphon helper case)', async () => {
    let onDead: (() => void) | undefined
    const backend: VcCameraBackend = {
      support: 'ok',
      pixelFormat: 'bgra',
      idleNoSignal: true,
      createCamera: (_w, _h, handlers) => {
        onDead = handlers?.onDead
        return { sendFrame: () => undefined, close: () => undefined }
      }
    }
    const { controller, statuses, pumps } = makeController({ backend })
    await controller.onVcStart()
    expect(statuses.at(-1)?.active).toBe(true)
    onDead?.()
    expect(statuses.at(-1)).toEqual({ available: true, needsRegistration: false, active: false })
    expect(pumps.at(-1)).toEqual({ mode: 'off' })
    expect(controller.activePixelFormat).toBeNull()
  })

  it('an idleNoSignal backend keeps the camera up with a no-signal still, then resumes on the next cast', async () => {
    const syphon = fakeBackend(true, 'bgra', true)
    const { controller, statuses, pumps } = makeController({ backend: syphon })
    await controller.onVcStart()
    expect(pumps[0].mode).toBe('frames')

    // The cast drops: no teardown — the still replaces the live feed, the
    // status stays active (the phone's card is hidden without a cast anyway).
    controller.onCastGone()
    expect((syphon as ReturnType<typeof fakeBackend>).closed).toHaveLength(0)
    expect(pumps.at(-1)).toEqual({ mode: 'no-signal', width: 1280, height: 720, pixelFormat: 'bgra' })
    expect(statuses.at(-1)?.active).toBe(true)
    // Stale frames while idling are dropped (the still must not be overwritten).
    controller.onFrame(new Uint8Array(1280 * 720 * 4))
    expect((syphon as ReturnType<typeof fakeBackend>).frames).toHaveLength(0)

    // A fresh cast resumes frames without recreating the camera.
    controller.onCastActive()
    expect(pumps.at(-1)).toEqual({ mode: 'frames', width: 1280, height: 720, pixelFormat: 'bgra' })

    // vc-stop still tears everything down.
    await controller.onVcStop()
    expect((syphon as ReturnType<typeof fakeBackend>).closed).toHaveLength(1)
    expect(pumps.at(-1)).toEqual({ mode: 'off' })
  })

  it('a non-idle backend (hypothetical) still dies with the cast — both shapes are supported', async () => {
    const { controller, pumps, backend } = makeController()
    await controller.onVcStart()
    controller.onCastGone()
    expect((backend as ReturnType<typeof fakeBackend>).closed).toHaveLength(1)
    expect(pumps.at(-1)).toEqual({ mode: 'off' })
  })

  it('reassertPump re-sends the current command for a fresh renderer (frameless recreate)', async () => {
    const syphon = fakeBackend(true, 'bgra', true)
    const { controller, pumps } = makeController({ backend: syphon })
    await controller.onVcStart()
    controller.onCastGone() // idle on the no-signal still
    const before = pumps.length
    controller.reassertPump() // a new renderer missed the earlier pushes
    expect(pumps.length).toBe(before + 1)
    expect(pumps.at(-1)).toEqual({ mode: 'no-signal', width: 1280, height: 720, pixelFormat: 'bgra' })
    controller.onCastActive()
    controller.reassertPump()
    expect(pumps.at(-1)).toEqual({ mode: 'frames', width: 1280, height: 720, pixelFormat: 'bgra' })
    await controller.onVcStop()
    controller.reassertPump() // inactive — nothing to re-assert
    expect(pumps.at(-1)).toEqual({ mode: 'off' })
  })

  it('toggle() drives both directions (the desktop switch, ADR-007 addendum)', async () => {
    const { controller, statuses, backend } = makeController()
    await controller.toggle()
    expect((backend as ReturnType<typeof fakeBackend>).created).toHaveLength(1)
    expect(statuses.at(-1)?.active).toBe(true)
    await controller.toggle()
    expect((backend as ReturnType<typeof fakeBackend>).closed).toHaveLength(1)
  })
})
