import { describe, expect, it } from 'vitest'
import { PER_TRACK_DEVICES, PipewireDeviceManager } from './pipewireDevices'

/**
 * The Linux per-track device manager (ADR-008 follow-up) with pactl faked:
 * two module-null-sink loads with our sink names/labels (the .monitor source
 * comes along for the input side), idempotence across crashed runs, honest
 * failure when pactl is missing, and unload of exactly what we loaded. The
 * real pactl shapes are exercised on a Linux rig — issue #75's checklist.
 */

function runnerWith(script: (command: string, args: string[]) => { code: number; stdout: string }) {
  const calls: Array<{ command: string; args: string[] }> = []
  return {
    calls,
    run: async (command: string, args: string[]) => {
      calls.push({ command, args })
      return script(command, args)
    }
  }
}

const emptySinks = { code: 0, stdout: '' }

describe('PipewireDeviceManager (Linux per-track audio, ADR-008 follow-up)', () => {
  it('loads one null-sink per track with our sink names and labels', async () => {
    let index = 40
    const fake = runnerWith((command, args) =>
      command === 'pactl' && args[0] === 'load-module' ? { code: 0, stdout: `${index++}\n` } : emptySinks
    )
    const manager = new PipewireDeviceManager({ run: fake.run })
    expect(await manager.ensureDevices()).toBe(true)
    const loads = fake.calls.filter((call) => call.args[0] === 'load-module')
    expect(loads).toHaveLength(2)
    expect(loads[0].args.join(' ')).toContain('module-null-sink')
    expect(loads[0].args.join(' ')).toContain('sink_name=zero_friction_game_audio')
    expect(loads[0].args.join(' ')).toContain("device.description='Zero-Friction Game Audio'")
    expect(loads[1].args.join(' ')).toContain('sink_name=zero_friction_mic')
    expect(loads[1].args.join(' ')).toContain("device.description='Zero-Friction Mic'")
  })

  it('adopts sinks a crashed previous run left behind — no duplicate loads', async () => {
    const fake = runnerWith((command, args) => {
      if (command === 'pactl' && args[0] === 'list') {
        return {
          code: 0,
          stdout: '0\tzero_friction_game_audio\tmodule-null-sink.c\tSINK_STATE\tZero-Friction Game Audio\n'
        }
      }
      return { code: 0, stdout: '99\n' }
    })
    const manager = new PipewireDeviceManager({ run: fake.run })
    expect(await manager.ensureDevices()).toBe(true)
    const loads = fake.calls.filter((call) => call.args[0] === 'load-module')
    expect(loads).toHaveLength(1) // only the mic — the game sink was adopted
    expect(loads[0].args.join(' ')).toContain('zero_friction_mic')
  })

  it('reports false honestly when pactl is missing (spawn failure)', async () => {
    const manager = new PipewireDeviceManager({
      run: async () => {
        throw new Error('spawn pactl ENOENT')
      }
    })
    expect(await manager.ensureDevices()).toBe(false)
  })

  it('reports false when a load-module fails or prints no index', async () => {
    const failing = new PipewireDeviceManager({
      run: runnerWith((_command, args) =>
        args[0] === 'load-module' ? { code: 1, stdout: '' } : emptySinks
      ).run
    })
    expect(await failing.ensureDevices()).toBe(false)

    const silent = new PipewireDeviceManager({
      run: runnerWith((_command, _args) => ({ code: 0, stdout: '' })).run
    })
    expect(await silent.ensureDevices()).toBe(false)
  })

  it('unloadAll removes exactly the modules we loaded — adopted ones stay', async () => {
    let index = 7
    const fake = runnerWith((_command, args) => {
      if (args[0] === 'list') {
        return { code: 0, stdout: '3\tzero_friction_mic\tmodule-null-sink.c\tSINK_STATE\tZero-Friction Mic\n' }
      }
      return args[0] === 'load-module' ? { code: 0, stdout: `${index++}\n` } : emptySinks
    })
    const manager = new PipewireDeviceManager({ run: fake.run })
    await manager.ensureDevices() // mic adopted, game loaded as module 7
    fake.calls.length = 0
    await manager.unloadAll()
    const unloads = fake.calls.filter((call) => call.args[0] === 'unload-module')
    expect(unloads).toHaveLength(1)
    expect(unloads[0].args).toContain('7')
    // A second unload is a no-op.
    fake.calls.length = 0
    await manager.unloadAll()
    expect(fake.calls.filter((call) => call.args[0] === 'unload-module')).toHaveLength(0)
  })

  it('the device labels match what findPerTrackDevices looks for', () => {
    // The contract with the renderer's existing detection (routing.ts):
    // /zero-friction game audio/i and /zero-friction mic/i on the output side.
    expect(PER_TRACK_DEVICES.map((device) => device.label)).toEqual([
      'Zero-Friction Game Audio',
      'Zero-Friction Mic'
    ])
  })
})
