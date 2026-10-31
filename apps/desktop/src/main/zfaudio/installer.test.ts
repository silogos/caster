import { describe, expect, it } from 'vitest'
import { ZfaudioInstaller, ZFAUDIO_DEST, zfaudioDir } from './installer'
import { join } from 'node:path'

/** Scripted runner: records calls, answers per command. */
function runnerWith(script: (command: string, args: string[]) => { code: number; stderr: string }) {
  const calls: Array<{ command: string; args: string[] }> = []
  return {
    calls,
    run: async (command: string, args: string[]) => {
      calls.push({ command, args })
      return script(command, args)
    }
  }
}

/** Fake filesystem: a Set of paths that "exist". */
const fsWith = (...paths: string[]) => (path: string) => paths.includes(path)

const BUNDLE_DIR = '/Applications/Zero-Friction Local Cast.app/Contents/Resources/zfaudio'

const makeInstaller = (overrides: Partial<ConstructorParameters<typeof ZfaudioInstaller>[0]> = {}) =>
  new ZfaudioInstaller({ dir: BUNDLE_DIR, ...overrides })

describe('ZfaudioInstaller (ADR-008 one-time install)', () => {
  it('detects an existing install by the system HAL path', () => {
    const installer = makeInstaller({ exists: fsWith(ZFAUDIO_DEST) })
    expect(installer.isInstalled()).toBe(true)
    expect(makeInstaller({ exists: fsWith() }).isInstalled()).toBe(false)
  })

  it('filesPresent requires both the script and the bundled driver', () => {
    const exists = fsWith(join(BUNDLE_DIR, 'install.sh'))
    expect(makeInstaller({ exists }).filesPresent).toBe(false)
    expect(
      makeInstaller({ exists: fsWith(join(BUNDLE_DIR, 'install.sh'), join(BUNDLE_DIR, 'ZeroFrictionAudio.driver')) })
        .filesPresent
    ).toBe(true)
  })

  it('installs through osascript with administrator privileges, running the bundled install.sh', async () => {
    const fake = runnerWith(() => ({ code: 0, stderr: '' }))
    const installer = makeInstaller({
      exists: fsWith(
        join(BUNDLE_DIR, 'install.sh'),
        join(BUNDLE_DIR, 'ZeroFrictionAudio.driver'),
        ZFAUDIO_DEST
      ),
      run: fake.run
    })
    expect(await installer.install()).toBe('installed')
    const call = fake.calls[0]
    expect(call).toBeDefined()
    expect(call.command).toBe('osascript')
    expect(call.args[0]).toBe('-e')
    const script = call.args[1]
    expect(script).toContain('with administrator privileges')
    // The path (with its space) survives the shell + AppleScript quoting.
    expect(script).toContain(`/bin/bash '${BUNDLE_DIR}/install.sh'`)
  })

  it('reports declined when the password prompt was cancelled (exit 1 + "User canceled")', async () => {
    const fake = runnerWith(() => ({
      code: 1,
      stderr: '36:72: execution error: User canceled. (-128)'
    }))
    const installer = makeInstaller({
      exists: fsWith(join(BUNDLE_DIR, 'install.sh'), join(BUNDLE_DIR, 'ZeroFrictionAudio.driver')),
      run: fake.run
    })
    expect(await installer.install()).toBe('declined')
  })

  it('reports failed when osascript exits non-zero without a cancel', async () => {
    const fake = runnerWith(() => ({ code: 1, stderr: 'cp: /Library/Audio/Plug-Ins/HAL: Operation not permitted' }))
    const installer = makeInstaller({
      exists: fsWith(join(BUNDLE_DIR, 'install.sh'), join(BUNDLE_DIR, 'ZeroFrictionAudio.driver')),
      run: fake.run
    })
    expect(await installer.install()).toBe('failed')
  })

  it('reports failed when the runner itself throws (osascript missing)', async () => {
    const installer = makeInstaller({
      exists: fsWith(join(BUNDLE_DIR, 'install.sh'), join(BUNDLE_DIR, 'ZeroFrictionAudio.driver')),
      run: async () => {
        throw new Error('spawn osascript ENOENT')
      }
    })
    expect(await installer.install()).toBe('failed')
  })

  it('reports failed when osascript exits 0 but the driver is not at the destination', async () => {
    const installer = makeInstaller({
      exists: fsWith(join(BUNDLE_DIR, 'install.sh'), join(BUNDLE_DIR, 'ZeroFrictionAudio.driver')),
      run: runnerWith(() => ({ code: 0, stderr: '' })).run
    })
    expect(await installer.install()).toBe('failed')
  })

  it('refuses to run when the bundled files are missing', async () => {
    const fake = runnerWith(() => ({ code: 0, stderr: '' }))
    const installer = makeInstaller({ exists: fsWith(), run: fake.run })
    expect(await installer.install()).toBe('failed')
    expect(fake.calls).toHaveLength(0)
  })
})

describe('zfaudioDir', () => {
  it('maps packaged apps to resourcesPath and dev to the repo resources folder', () => {
    expect(zfaudioDir(true, '/Applications/app.app/Contents/Resources', '/repo/apps/desktop')).toBe(
      '/Applications/app.app/Contents/Resources/zfaudio'
    )
    expect(zfaudioDir(false, '/irrelevant', '/repo/apps/desktop')).toBe('/repo/apps/desktop/resources/zfaudio')
  })
})
