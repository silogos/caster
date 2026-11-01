import { join } from 'node:path'
import { loadSoftcam, type VcCameraBackend } from './vc/softcamLibrary'
import { SoftcamRegistration, noopRegistration, softcamDir } from './vc/registration'
import { loadSyphonBackend } from './vc/syphonPublisher'
import { loadPipewireBackend } from './vc/pipewirePublisher'
import { ZfaudioInstaller, zfaudioDir, type InstallResult } from './zfaudio/installer'
import { PipewireDeviceManager } from './zfaudio/pipewireDevices'

/**
 * The desktop's platform environment, resolved once at startup (refactor of
 * the if/else chains index.ts had grown): the ONE place that maps OS →
 * implementation. Everything above this seam — the virtual camera controller,
 * the IPC handlers, the renderer — consumes the interface and never asks
 * "which platform am I on". The platform map mirrors ADR-007 (camera) and
 * ADR-008 (audio):
 *
 * - macOS: Syphon camera + the one-click ZeroFrictionAudio installer.
 * - Linux: PipeWire camera node + loopback devices managed with the app run.
 * - Windows: softcam camera + UAC registration (VB-Cable for audio, #74).
 *
 * Each adapter self-gates and reports honest availability; this module only
 * decides who runs.
 */

export type DesktopPlatform = 'darwin' | 'win32' | 'linux' | 'other'

export interface DesktopEnvironment {
  platform: DesktopPlatform
  /** The virtual camera adapter (ADR-007) + its per-machine registration story. */
  vcBackend: VcCameraBackend
  vcRegistration: SoftcamRegistration
  /**
   * The one-click virtual audio install where the platform has one (macOS,
   * ADR-008). Null elsewhere — Linux's devices are created automatically and
   * Windows pragmatics are detection + guidance, neither is an install.
   */
  virtualAudioInstall: { supported: boolean; install: () => Promise<InstallResult> } | null
  /** Audio lifecycle tied to the app run, where the platform needs one (Linux). */
  startAppAudio?: () => void
  quitAppAudio?: () => Promise<void>
}

/** The app-path facts the per-platform loaders resolve their resources with. */
export interface EnvironmentPaths {
  isPackaged: boolean
  resourcesPath: string
  appPath: string
}

/** Narrow Node's platform values to the ones this app distinguishes. */
export function desktopPlatform(platform: NodeJS.Platform): DesktopPlatform {
  return platform === 'darwin' || platform === 'win32' || platform === 'linux' ? platform : 'other'
}

export function loadDesktopEnvironment(paths: EnvironmentPaths): DesktopEnvironment {
  const platform = desktopPlatform(process.platform)
  const bundled = (name: string): string =>
    paths.isPackaged ? join(paths.resourcesPath, name) : join(paths.appPath, 'resources', name)

  if (platform === 'darwin') {
    const syphon = loadSyphonBackend({ dir: bundled('syphon') })
    const installer = new ZfaudioInstaller({ dir: zfaudioDir(paths.isPackaged, paths.resourcesPath, paths.appPath) })
    return {
      platform,
      vcBackend: syphon,
      vcRegistration: noopRegistration(syphon.support === 'ok'),
      virtualAudioInstall: { supported: installer.filesPresent, install: () => installer.install() }
    }
  }

  if (platform === 'linux') {
    const pipewire = loadPipewireBackend()
    const devices = new PipewireDeviceManager()
    return {
      platform,
      vcBackend: pipewire,
      vcRegistration: noopRegistration(pipewire.support === 'ok'),
      virtualAudioInstall: null,
      startAppAudio: () => void devices.ensureDevices(),
      quitAppAudio: () => devices.unloadAll()
    }
  }

  // Windows — and anything else carrying the softcam files; loadSoftcam
  // self-gates to honest unavailability off win32-x64.
  const dir = softcamDir(paths.isPackaged, paths.resourcesPath, paths.appPath)
  return {
    platform,
    vcBackend: loadSoftcam(dir),
    vcRegistration: new SoftcamRegistration({
      installerPath: join(dir, 'softcam_installer.exe'),
      dllPath: join(dir, 'softcam.dll')
    }),
    virtualAudioInstall: null
  }
}
