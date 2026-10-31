import { describe, expect, it } from 'vitest'
import { SoftcamRegistration, SOFTCAM_CLSID, UAC_CANCELLED_EXIT_CODE } from './registration'

/** Scripted runner: records calls, answers per command. */
function runnerWith(script: (command: string, args: string[]) => number) {
  const calls: Array<{ command: string; args: string[] }> = []
  return {
    calls,
    run: async (command: string, args: string[]) => {
      calls.push({ command, args })
      return script(command, args)
    }
  }
}

describe('SoftcamRegistration (ADR-007 one-time registration)', () => {
  it('detects registration by querying the softcam CLSID', async () => {
    const fake = runnerWith((command, args) => (command === 'reg.exe' && args[1]?.includes(SOFTCAM_CLSID) ? 0 : 1))
    const registration = new SoftcamRegistration({
      installerPath: 'softcam_installer.exe',
      dllPath: 'softcam.dll',
      run: fake.run
    })
    expect(await registration.isRegistered()).toBe(true)
    expect(fake.calls[0]).toEqual({
      command: 'reg.exe',
      args: ['query', `HKCR\\CLSID\\${SOFTCAM_CLSID}`, '/ve']
    })
  })

  it('reports unregistered when the registry key is missing (non-zero exit)', async () => {
    const fake = runnerWith(() => 1)
    const registration = new SoftcamRegistration({
      installerPath: 'softcam_installer.exe',
      dllPath: 'softcam.dll',
      run: fake.run
    })
    expect(await registration.isRegistered()).toBe(false)
  })

  it('registers via an elevated PowerShell Start-Process and verifies the registry after', async () => {
    let registered = false
    const fake = runnerWith((command) => {
      if (command === 'powershell.exe') {
        registered = true
        return 0
      }
      return registered ? 0 : 1
    })
    const registration = new SoftcamRegistration({
      installerPath: 'C:\\softcam\\softcam_installer.exe',
      dllPath: 'C:\\softcam\\softcam.dll',
      run: fake.run
    })
    expect(await registration.register()).toBe('registered')
    const powershell = fake.calls.find((call) => call.command === 'powershell.exe')
    expect(powershell).toBeDefined()
    expect(powershell!.args[0]).toBe('-NoProfile')
    const script = powershell!.args[2]
    expect(script).toContain('Start-Process')
    expect(script).toContain('-Verb RunAs')
    expect(script).toContain("'register'")
    expect(script).toContain('C:\\softcam\\softcam.dll')
  })

  it('reports declined when UAC was cancelled (exit 1223)', async () => {
    const fake = runnerWith((command) => (command === 'powershell.exe' ? UAC_CANCELLED_EXIT_CODE : 1))
    const registration = new SoftcamRegistration({
      installerPath: 'installer',
      dllPath: 'dll',
      run: fake.run
    })
    expect(await registration.register()).toBe('declined')
  })

  it('reports failed when the installer exits non-zero or the registry still misses the CLSID', async () => {
    const failing = new SoftcamRegistration({
      installerPath: 'installer',
      dllPath: 'dll',
      run: runnerWith((command) => (command === 'powershell.exe' ? 1 : 1)).run
    })
    expect(await failing.register()).toBe('failed')

    // Installer claims success but the registry disagrees — trust the registry.
    const lying = new SoftcamRegistration({
      installerPath: 'installer',
      dllPath: 'dll',
      run: runnerWith((command) => (command === 'powershell.exe' ? 0 : 1)).run
    })
    expect(await lying.register()).toBe('failed')
  })

  it('reports failed when spawning throws', async () => {
    const registration = new SoftcamRegistration({
      installerPath: 'installer',
      dllPath: 'dll',
      run: async () => {
        throw new Error('spawn failed')
      }
    })
    expect(await registration.register()).toBe('failed')
  })
})
