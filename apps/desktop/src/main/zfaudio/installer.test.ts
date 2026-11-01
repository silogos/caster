import { describe, expect, it } from 'vitest'
import { existsSync } from 'node:fs'
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

const STAGED_SCRIPT = '/var/folders/xx/zfc-zfaudio-install/install.sh'

const makeInstaller = (overrides: Partial<ConstructorParameters<typeof ZfaudioInstaller>[0]> = {}) =>
  // Default stage is faked (a fixed staged path) so the outcome tests below
  // exercise osascript handling, not the filesystem; the real default
  // (copying script + driver into the temp staging dir) has its own test.
  new ZfaudioInstaller({ dir: BUNDLE_DIR, stage: () => STAGED_SCRIPT, ...overrides })

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

  it('installs through osascript with administrator privileges, running the STAGED install.sh (TCC, #72)', async () => {
    const fake = runnerWith(() => ({ code: 0, stderr: '' }))
    const installer = makeInstaller({
      exists: fsWith(
        join(BUNDLE_DIR, 'install.sh'),
        join(BUNDLE_DIR, 'ZeroFrictionAudio.driver'),
        ZFAUDIO_DEST
      ),
      stage: () => '/var/folders/xx/zfc-zfaudio-install/install.sh',
      run: fake.run
    })
    expect(await installer.install()).toBe('installed')
    const call = fake.calls[0]
    expect(call).toBeDefined()
    expect(call.command).toBe('osascript')
    expect(call.args[0]).toBe('-e')
    const script = call.args[1]
    expect(script).toContain('with administrator privileges')
    // The elevated shell runs the STAGED copy — never the TCC-protected bundle.
    expect(script).toContain(`/bin/bash '/var/folders/xx/zfc-zfaudio-install/install.sh'`)
    expect(script).not.toContain(BUNDLE_DIR)
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

  it('a staging failure reports failed without touching osascript', async () => {
    const fake = runnerWith(() => ({ code: 0, stderr: '' }))
    const installer = makeInstaller({
      exists: fsWith(join(BUNDLE_DIR, 'install.sh'), join(BUNDLE_DIR, 'ZeroFrictionAudio.driver')),
      stage: () => {
        throw new Error('EACCES')
      },
      run: fake.run
    })
    expect(await installer.install()).toBe('failed')
    expect(fake.calls).toHaveLength(0)
  })

  it('the default staging copies script + driver beside each other in the temp dir', async () => {
    // The repo's real bundle staged by the REAL default: the staged tree keeps
    // install.sh's "driver next to me" contract, in a TCC-free location.
    let stagedPath = ''
    const installer = new ZfaudioInstaller({
      dir: join('resources', 'zfaudio'),
      run: runnerWith((_command, args) => {
        stagedPath = args[1].match(/'([^']+)'/)?.[1] ?? ''
        return { code: 1, stderr: '' } // don't actually install — inspect staging only
      }).run
    })
    await installer.install()
    expect(stagedPath).toContain('zfc-zfaudio-install')
    expect(existsSync(stagedPath)).toBe(true)
    expect(existsSync(join(stagedPath, '..', 'ZeroFrictionAudio.driver', 'Contents'))).toBe(true)
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
