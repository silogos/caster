import { describe, expect, it } from 'vitest'
import { VirtualCameraController, type PumpCommand, type VcStatusPush } from './virtualCamera'
import type { SoftcamCamera, SoftcamLibrary } from './softcamLibrary'
import type { SoftcamRegistration } from './registration'

/**
 * The virtual camera state machine (ADR-007) with every collaborator faked:
 * platform support, registration (UAC), the softcam instance, and the push
 * channels. The real DLL path is exercised on the Windows rig only.
 */

type RegisterResult = 'registered' | 'declined' | 'failed'

function fakeSoftcam(createSucceeds = true): SoftcamLibrary & { created: string[]; closed: string[]; frames: Uint8Array[] } {
  const state = { created: [] as string[], closed: [] as string[], frames: [] as Uint8Array[] }
  let n = 0
  return {
    support: 'ok',
    ...state,
    createCamera(width, height): SoftcamCamera | null {
      if (!createSucceeds) return null
      n += 1
      const id = `cam-${n}:${width}x${height}`
      state.created.push(id)
      return {
        sendFrame: (bgr) => state.frames.push(bgr),
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
  softcam?: SoftcamLibrary
  registration?: SoftcamRegistration
  streamSize?: { width: number; height: number } | null
} = {}) {
  const statuses: VcStatusPush[] = []
  const pumps: PumpCommand[] = []
  const softcam = options.softcam ?? fakeSoftcam()
  const controller = new VirtualCameraController({
    softcam,
    registration: options.registration ?? fakeRegistration(true),
    // Explicit null must survive (no ?? fallback — it would resurrect a default size).
    streamSize: () => (options.streamSize === undefined ? { width: 1280, height: 720 } : options.streamSize),
    sendStatus: (status) => statuses.push(status),
    onPumpCommand: (command) => pumps.push(command),
    log: () => undefined
  })
  return { controller, statuses, pumps, softcam }
}

describe('VirtualCameraController (ADR-007 state machine)', () => {
  it('reports unavailable when the platform or files are missing, and never registers', async () => {
    const registration = fakeRegistration(false)
    const { controller, statuses } = makeController({
      softcam: { support: 'unsupported-platform', createCamera: () => null },
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
    const { controller, statuses, pumps, softcam } = makeController({ registration })
    await controller.onVcStart()
    expect(registration.registerCalls).toBe(1)
    expect((softcam as ReturnType<typeof fakeSoftcam>).created).toEqual(['cam-1:1280x720'])
    expect(pumps).toEqual([{ active: true, width: 1280, height: 720 }])
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
    const { controller, pumps, softcam } = makeController({ streamSize: { width: 2560, height: 1440 } })
    await controller.onVcStart()
    expect((softcam as ReturnType<typeof fakeSoftcam>).created).toEqual(['cam-1:1920x1080'])
    expect(pumps).toEqual([{ active: true, width: 1920, height: 1080 }])
  })

  it('refuses to start without a stream size (toggle is cast-scoped)', async () => {
    const { controller, statuses } = makeController({ streamSize: null })
    await controller.onVcStart()
    expect(statuses.at(-1)).toEqual({ available: true, needsRegistration: false, active: false })
  })

  it('feeds frames while active, drops mismatched ones, ignores them otherwise', async () => {
    const { controller, softcam } = makeController()
    controller.onFrame(new Uint8Array(1280 * 720 * 3)) // before start — ignored
    await controller.onVcStart()
    const cam = softcam as ReturnType<typeof fakeSoftcam>
    controller.onFrame(new Uint8Array(1280 * 720 * 3))
    controller.onFrame(new Uint8Array(42)) // wrong size — dropped, never reaches native
    expect(cam.frames).toHaveLength(1)
    expect(cam.frames[0]).toHaveLength(1280 * 720 * 3)
  })

  it('vc-stop tears the camera down and returns to available (status pushed, pump stopped)', async () => {
    const { controller, statuses, pumps, softcam } = makeController()
    await controller.onVcStart()
    await controller.onVcStop()
    const cam = softcam as ReturnType<typeof fakeSoftcam>
    expect(cam.closed).toEqual(['cam-1:1280x720'])
    expect(pumps.at(-1)).toEqual({ active: false, width: null, height: null })
    expect(statuses.at(-1)).toEqual({ available: true, needsRegistration: false, active: false })
    await controller.onVcStop() // idempotent
    expect(cam.closed).toHaveLength(1)
  })

  it('cast-gone and dispose both delete the camera (every exit path, no leak)', async () => {
    const a = makeController()
    await a.controller.onVcStart()
    a.controller.onCastGone()
    expect((a.softcam as ReturnType<typeof fakeSoftcam>).closed).toHaveLength(1)
    expect(a.statuses.at(-1)?.active).toBe(false)

    const b = makeController()
    await b.controller.onVcStart()
    b.controller.dispose() // app quit — no status push needed
    expect((b.softcam as ReturnType<typeof fakeSoftcam>).closed).toHaveLength(1)
    expect(b.pumps.at(-1)).toEqual({ active: false, width: null, height: null })
  })

  it('ignores re-entrant starts while the UAC registration is in flight', async () => {
    // A holder object so TS's control-flow analysis sees the closure assignment.
    const gate: { release: null | (() => void) } = { release: null }
    const registration = fakeRegistration(false)
    registration.register = () =>
      new Promise<RegisterResult>((resolve) => {
        gate.release = () => resolve('registered')
      })
    const { controller, softcam } = makeController({ registration })
    const first = controller.onVcStart()
    const second = controller.onVcStart()
    // The register() call only happens after ready()'s microtasks — wait for
    // the pending promise to actually be armed before releasing it.
    while (gate.release === null) {
      await new Promise((resolve) => setTimeout(resolve, 1))
    }
    gate.release()
    await Promise.all([first, second])
    expect((softcam as ReturnType<typeof fakeSoftcam>).created).toHaveLength(1)
  })

  it('vc-start while already active is a no-op', async () => {
    const { controller, softcam } = makeController()
    await controller.onVcStart()
    await controller.onVcStart()
    expect((softcam as ReturnType<typeof fakeSoftcam>).created).toHaveLength(1)
  })
})
