import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { logger } from '../log'

/**
 * One-time DirectShow registration for softcam (ADR-007): the filter must be
 * registered per machine (registry CLSID) before any app can see the camera —
 * the only moment that needs elevation. Detection is a registry query; the
 * elevated register runs softcam_installer.exe via PowerShell Start-Process
 * -Verb RunAs (UAC). Command execution is injectable so tests fake the OS.
 */

const LOG_SCOPE = 'vc-registration'
/** softcam's DirectShow filter CLSID (softcam.cpp CLSID_DShowSoftcam). */
export const SOFTCAM_CLSID = '{AEF3B972-5FA5-4647-9571-358EB472BC9E}'
/** Windows exit code for "the operation was canceled by the user" (UAC declined). */
export const UAC_CANCELLED_EXIT_CODE = 1223

export type RegistrationResult = 'registered' | 'declined' | 'failed'

/** Runs a command and resolves its exit code; rejects on spawn failure. */
export type CommandRunner = (command: string, args: string[]) => Promise<number>

export interface RegistrationOptions {
  /** Path of softcam_installer.exe. */
  installerPath: string
  /** Path of softcam.dll (passed to the installer). */
  dllPath: string
  /** Injectable for tests; default spawns the real command. */
  run?: CommandRunner
}

/** Spawn helper with a hard timeout — a stuck reg.exe must not hang the app. */
const REG_TIMEOUT_MS = 10_000
const INSTALLER_TIMEOUT_MS = 120_000

const defaultRunner = (command: string, args: string[], timeoutMs: number): Promise<number> =>
  new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'ignore', windowsHide: true })
    const timer = setTimeout(() => child.kill(), timeoutMs)
    child.on('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
    child.on('close', (code) => {
      clearTimeout(timer)
      resolve(code ?? -1)
    })
  })

export class SoftcamRegistration {
  private readonly installerPath: string
  private readonly dllPath: string
  private readonly run: CommandRunner

  constructor(options: RegistrationOptions) {
    this.installerPath = options.installerPath
    this.dllPath = options.dllPath
    this.run =
      options.run ??
      ((command, args) =>
        defaultRunner(command, args, command === 'reg.exe' ? REG_TIMEOUT_MS : INSTALLER_TIMEOUT_MS))
  }

  /** Whether the installer and DLL files actually exist on disk. */
  get filesPresent(): boolean {
    return existsSync(this.installerPath) && existsSync(this.dllPath)
  }

  /** True when the filter's CLSID is present in the registry (per-machine registration done). */
  async isRegistered(): Promise<boolean> {
    try {
      const code = await this.run('reg.exe', ['query', `HKCR\\CLSID\\${SOFTCAM_CLSID}`, '/ve'])
      return code === 0
    } catch (error) {
      logger.warn(LOG_SCOPE, 'registration detection failed', { error: String(error) })
      return false
    }
  }

  /**
   * The one-time elevated registration. The UAC prompt appears on the desktop;
   * the installer also shows its own "successfully registered" message box the
   * user dismisses (the wait covers it). Declined UAC is reported honestly —
   * it is a user decision, not an error.
   */
  async register(): Promise<RegistrationResult> {
    // Start-Process -Verb RunAs throws when the user cancels UAC; the elevated
    // process's exit code (0 = ok, 1 = installer failure) is surfaced via -PassThru.
    const script =
      `try { $p = Start-Process -FilePath '${this.installerPath}' ` +
      `-ArgumentList 'register','${this.dllPath}' -Verb RunAs -Wait -PassThru; ` +
      `exit $p.ExitCode } catch { exit ${UAC_CANCELLED_EXIT_CODE} }`
    try {
      const code = await this.run('powershell.exe', ['-NoProfile', '-Command', script])
      if (code === 0) {
        // Trust the registry over the exit code — it is what consumers see.
        const registered = await this.isRegistered()
        if (registered) return 'registered'
        logger.warn(LOG_SCOPE, `installer exited 0 but the CLSID is not registered (exit ${code})`)
        return 'failed'
      }
      if (code === UAC_CANCELLED_EXIT_CODE) {
        logger.info(LOG_SCOPE, 'registration declined — the UAC prompt was cancelled')
        return 'declined'
      }
      logger.warn(LOG_SCOPE, `registration failed — installer exit code ${code}`)
      return 'failed'
    } catch (error) {
      logger.warn(LOG_SCOPE, 'registration could not run', { error: String(error) })
      return 'failed'
    }
  }
}

/**
 * Where the packaged softcam files live: extraResources puts them under
 * `resources/softcam/` in production (process.resourcesPath); in dev they sit
 * in the repo's apps/desktop/resources/softcam.
 */
export function softcamDir(appIsPackaged: boolean, resourcesPath: string, devRoot: string): string {
  return appIsPackaged ? join(resourcesPath, 'softcam') : join(devRoot, 'resources', 'softcam')
}
