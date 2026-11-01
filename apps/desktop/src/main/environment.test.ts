import { describe, expect, it } from 'vitest'
import { join } from 'node:path'
import { desktopPlatform, loadDesktopEnvironment } from './environment'

/**
 * The platform map in one module (refactor of index.ts's if/else chains):
 * each platform resolves to its own camera adapter + virtual-audio story,
 * and the wiring above this seam never asks which platform it runs on. The
 * platform gate is faked per test (the withPlatform pattern from the backend
 * tests); the real adapters' behavior is covered by their own suites — here
 * we pin WHO runs and WHAT the environment exposes.
 */

function withPlatform<T>(platform: string, fn: () => T): T {
  const real = Object.getOwnPropertyDescriptor(process, 'platform')
  Object.defineProperty(process, 'platform', { value: platform, configurable: true })
  try {
    return fn()
  } finally {
    if (real !== undefined) Object.defineProperty(process, 'platform', real)
  }
}

/** Dev-shaped paths: resources resolve from the repo (real vendored artifacts). */
const DEV_PATHS = {
  isPackaged: false,
  resourcesPath: '/irrelevant-in-dev',
  appPath: process.cwd()
}

describe('desktopPlatform', () => {
  it('narrows Node platforms to the ones the app distinguishes', () => {
    expect(desktopPlatform('darwin')).toBe('darwin')
    expect(desktopPlatform('win32')).toBe('win32')
    expect(desktopPlatform('linux')).toBe('linux')
    expect(desktopPlatform('freebsd')).toBe('other')
  })
})

describe('loadDesktopEnvironment (the ADR-007/008 platform map)', () => {
  it('macOS: Syphon camera + the one-click installer, no app-run audio lifecycle', () => {
    withPlatform('darwin', () => {
      const env = loadDesktopEnvironment(DEV_PATHS)
      expect(env.platform).toBe('darwin')
      // The repo's vendored helper + framework load — the real mac path.
      expect(env.vcBackend.support).toBe('ok')
      expect(env.vcBackend.pixelFormat).toBe('bgra')
      expect(env.vcRegistration.filesPresent).toBe(true)
      expect(env.virtualAudioInstall).not.toBeNull()
      expect(env.virtualAudioInstall!.supported).toBe(true) // the bundled driver is present
      expect(typeof env.virtualAudioInstall!.install).toBe('function')
      expect(env.startAppAudio).toBeUndefined()
      expect(env.quitAppAudio).toBeUndefined()
    })
  })

  it('Linux: PipeWire camera + auto-created devices with the app run, nothing to install', () => {
    withPlatform('linux', () => {
      const env = loadDesktopEnvironment(DEV_PATHS)
      expect(env.platform).toBe('linux')
      // pixelFormat pins WHO runs; support honestly depends on the machine's
      // gst-launch (absent here → dll-missing) — the adapter's own suite
      // covers its behavior, this pins the wiring only.
      expect(env.vcBackend.pixelFormat).toBe('bgra')
      expect(env.virtualAudioInstall).toBeNull()
      expect(typeof env.startAppAudio).toBe('function')
      expect(typeof env.quitAppAudio).toBe('function')
    })
  })

  it('Windows: softcam camera (self-gated off win32-x64 here) + UAC registration, no installer', () => {
    withPlatform('win32', () => {
      const env = loadDesktopEnvironment(DEV_PATHS)
      expect(env.platform).toBe('win32')
      // On this mac the softcam adapter honestly reports unsupported — the
      // environment still hands it over; availability is the adapter's call.
      expect(env.vcBackend.pixelFormat).toBe('bgr24')
      expect(env.vcRegistration.filesPresent).toBe(true)
      expect(env.virtualAudioInstall).toBeNull()
      expect(env.startAppAudio).toBeUndefined()
    })
  })

  it('other platforms ride the softcam branch unchanged', () => {
    withPlatform('freebsd', () => {
      const env = loadDesktopEnvironment(DEV_PATHS)
      expect(env.platform).toBe('other')
      expect(env.vcBackend.pixelFormat).toBe('bgr24')
    })
  })

  it('packaged apps resolve vendored artifacts from resourcesPath', () => {
    withPlatform('darwin', () => {
      const env = loadDesktopEnvironment({
        isPackaged: true,
        resourcesPath: join('build', 'resources'),
        appPath: '/unused-when-packaged'
      })
      // The helper is NOT under the packaged path in this repo layout → honest
      // unavailability, proving the packaged resolution was actually used.
      expect(env.vcBackend.support).toBe('dll-missing')
      expect(env.vcRegistration.filesPresent).toBe(false)
    })
  })
})
