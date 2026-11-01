import { spawn } from 'node:child_process'
import { cpSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { logger } from '../log'

/**
 * One-time install of the ZeroFrictionAudio HAL plugin (ADR-008): the two
 * loopback devices other apps read ("Zero-Friction Game Audio" / "Zero-Friction
 * Mic") exist only after the .driver bundle is copied into the system HAL
 * directory and coreaudiod restarts — the only moment that needs elevation.
 * Detection is a plain path check (the directory is world-readable); the
 * elevated install runs the bundled install.sh through osascript's native
 * administrator password prompt (the macOS analog of softcam's UAC flow,
 * vc/registration.ts). Command execution is injectable so tests fake the OS.
 */

const LOG_SCOPE = 'zfaudio-install'

/** Where macOS loads AudioServerPlugIn bundles from (ADR-008). */
export const ZFAUDIO_DEST = '/Library/Audio/Plug-Ins/HAL/ZeroFrictionAudio.driver'

export type InstallResult = 'installed' | 'declined' | 'failed'

/** Runs a command and resolves its exit code + stderr; rejects on spawn failure. */
export type CommandRunner = (
  command: string,
  args: string[]
) => Promise<{ code: number; stderr: string }>

/** Path existence check, injectable so tests fake the filesystem. */
export type PathChecker = (path: string) => boolean

export interface InstallerOptions {
  /** Directory holding the bundled ZeroFrictionAudio.driver + install.sh. */
  dir: string
  /** Injectable for tests; default spawns the real command. */
  run?: CommandRunner
  /** Injectable for tests; default is fs.existsSync. */
  exists?: PathChecker
  /**
   * Injectable for tests; default copies script + driver to a TCC-free
   * staging dir and returns the staged script's path (see below).
   */
  stage?: (dir: string) => string
}

/**
 * Where the elevated install runs from. Found live (#72): an app running from
 * a TCC-protected folder (~/Documents, ~/Downloads — dev today, but any user
 * who runs the app from there) makes the elevated root shell fail with
 * "Operation not permitted" reading the bundled script — `do shell script`
 * does not inherit the app's folder permissions. The system temp dir has no
 * such protection, so the app (which CAN read its own resources) stages the
 * script + driver there first, and the elevation copies from the staging
 * copy. Left behind on purpose: the OS sweeps temp.
 */
const STAGING_DIR = join(tmpdir(), 'zfc-zfaudio-install')

/** The admin prompt can sit on screen while the user digs out their password. */
const INSTALL_TIMEOUT_MS = 120_000

/** Single-quote a value for /bin/sh (and osascript's do-shell-script). */
const shellQuote = (value: string): string => `'${value.replaceAll("'", `'\\''`)}'`

/** Quote a string for an AppleScript source (double-quoted string literal). */
const appleQuote = (value: string): string =>
  `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`

const defaultRunner = (command: string, args: string[]): Promise<{ code: number; stderr: string }> =>
  new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'ignore', 'pipe'] })
    let stderr = ''
    child.stderr?.on('data', (chunk: Buffer) => {
      stderr += chunk.toString('utf8')
    })
    const timer = setTimeout(() => child.kill(), INSTALL_TIMEOUT_MS)
    child.on('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
    child.on('close', (code) => {
      clearTimeout(timer)
      resolve({ code: code ?? -1, stderr })
    })
  })

/**
 * A cancelled password prompt is osascript exit 1 with "User canceled" /
 * error -128 on stderr — indistinguishable from failure by exit code alone,
 * unlike Windows UAC's dedicated 1223.
 */
const isUserCancelled = (stderr: string): boolean =>
  /user cancel+ed/i.test(stderr) || stderr.includes('-128')

const defaultStage = (dir: string): string => {
  const scriptPath = join(dir, 'install.sh')
  rmSync(STAGING_DIR, { recursive: true, force: true })
  // install.sh cds to its own directory and expects the driver beside it —
  // the staged copies keep that shape, so the script runs unmodified.
  cpSync(scriptPath, join(STAGING_DIR, 'install.sh'))
  cpSync(join(dir, 'ZeroFrictionAudio.driver'), join(STAGING_DIR, 'ZeroFrictionAudio.driver'), {
    recursive: true
  })
  return join(STAGING_DIR, 'install.sh')
}

export class ZfaudioInstaller {
  private readonly dir: string
  private readonly scriptPath: string
  private readonly driverPath: string
  private readonly run: CommandRunner
  private readonly exists: PathChecker
  private readonly stage: (dir: string) => string

  constructor(options: InstallerOptions) {
    this.dir = options.dir
    this.scriptPath = join(options.dir, 'install.sh')
    this.driverPath = join(options.dir, 'ZeroFrictionAudio.driver')
    this.run = options.run ?? defaultRunner
    this.exists = options.exists ?? existsSync
    this.stage = options.stage ?? defaultStage
  }

  /** Whether the bundled driver + install script actually exist on disk. */
  get filesPresent(): boolean {
    return this.exists(this.scriptPath) && this.exists(this.driverPath)
  }

  /** True once the plugin is in the system HAL directory (per-machine install done). */
  isInstalled(): boolean {
    return this.exists(ZFAUDIO_DEST)
  }

  /**
   * The one-time elevated install. The native administrator password dialog
   * appears on screen; install.sh (run as root) replaces the bundle and
   * restarts coreaudiod — launchd brings it back within ~1 s, and the devices
   * appear when it does. A cancelled prompt is reported honestly — it is a
   * user decision, not an error.
   */
  async install(): Promise<InstallResult> {
    if (!this.filesPresent) {
      logger.warn(LOG_SCOPE, 'install requested but the bundled driver is missing')
      return 'failed'
    }
    // Stage first, as the app (which can read its own resources): the
    // elevated shell cannot read through TCC-protected folders (#72).
    let stagedScript: string
    try {
      stagedScript = this.stage(this.dir)
    } catch (error) {
      logger.warn(LOG_SCOPE, 'staging the installer failed', { error: String(error) })
      return 'failed'
    }
    const shellCommand = `/bin/bash ${shellQuote(stagedScript)}`
    const appleScript = `do shell script ${appleQuote(shellCommand)} with administrator privileges`
    try {
      const { code, stderr } = await this.run('osascript', ['-e', appleScript])
      if (code === 0) {
        // Trust the filesystem over the exit code — the devices are what consumers see.
        if (this.isInstalled()) return 'installed'
        logger.warn(LOG_SCOPE, 'osascript exited 0 but the driver is not installed')
        return 'failed'
      }
      if (isUserCancelled(stderr)) {
        logger.info(LOG_SCOPE, 'install declined — the password prompt was cancelled')
        return 'declined'
      }
      logger.warn(LOG_SCOPE, `install failed — osascript exit ${code}`, { stderr: stderr.trim() })
      return 'failed'
    } catch (error) {
      logger.warn(LOG_SCOPE, 'install could not run', { error: String(error) })
      return 'failed'
    }
  }
}

/**
 * Where the packaged zfaudio files live: extraResources puts them under
 * `resources/zfaudio/` in production (process.resourcesPath); in dev they sit
 * in the repo's apps/desktop/resources/zfaudio. Same mapping as softcamDir.
 */
export function zfaudioDir(appIsPackaged: boolean, resourcesPath: string, devRoot: string): string {
  return appIsPackaged ? join(resourcesPath, 'zfaudio') : join(devRoot, 'resources', 'zfaudio')
}
