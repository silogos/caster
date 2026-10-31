import { spawn } from 'node:child_process'
import { logger } from '../log'

/**
 * Linux per-track virtual audio (ADR-008's recorded follow-up): two loopback
 * devices — "Zero-Friction Game Audio" and "Zero-Friction Mic" — so the
 * existing label detection (`findPerTrackDevices`) and the mixer's per-track
 * sinkId routing work unchanged. One `pactl load-module module-null-sink`
 * per device creates BOTH sides: the sink is the output our mixer plays
 * into, and its auto-created `.monitor` source is the input OBS/Zoom read —
 * the same output-ours/input-theirs shape as the macOS HAL plugin and
 * BlackHole. pactl (present on PulseAudio and PipeWire-Pulse stacks alike)
 * is chosen over pw-cli for that compatibility; no elevation anywhere.
 *
 * Lifecycle (decided for #75, recorded in the ADR-008 addendum):
 * ensureDevices() at app start — capture-card style, the devices exist
 * whenever caster runs (silence when nothing plays) — and unloadAll() at
 * quit. A crashed run leaves modules behind, so ensure is idempotent: sinks
 * already listed are adopted, not duplicated. Command execution is
 * injectable for tests; the pactl argument shapes are verified on a rig
 * (issue #75's on-device checklist).
 */

const LOG_SCOPE = 'zfaudio-pipewire'

/** One per-track device: the sink (ours to play into) + the label apps see. */
export interface PipewireTrackDevice {
  sinkName: string
  label: string
}

export const PER_TRACK_DEVICES: readonly PipewireTrackDevice[] = [
  { sinkName: 'zero_friction_game_audio', label: 'Zero-Friction Game Audio' },
  { sinkName: 'zero_friction_mic', label: 'Zero-Friction Mic' }
] as const

/** Runs a command; resolves exit code + stdout. Rejects on spawn failure. */
export type CommandRunner = (
  command: string,
  args: string[]
) => Promise<{ code: number; stdout: string }>

const defaultRunner = (command: string, args: string[]): Promise<{ code: number; stdout: string }> =>
  new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'ignore'] })
    let stdout = ''
    child.stdout?.on('data', (chunk: Buffer) => {
      stdout += chunk.toString('utf8')
    })
    child.on('error', (error) => reject(error))
    child.on('close', (code) => resolve({ code: code ?? -1, stdout }))
  })

export interface PipewireDeviceManagerOptions {
  /** Injectable for tests; default spawns the real command. */
  run?: CommandRunner
}

export class PipewireDeviceManager {
  private readonly run: CommandRunner
  /** Loaded module indices per sink name — what unload-module needs. */
  private loadedModules = new Map<string, string>()

  constructor(options: PipewireDeviceManagerOptions = {}) {
    this.run = options.run ?? defaultRunner
  }

  /**
   * The sink names currently registered (one per line of `pactl list short
   * sinks` — the name is the second tab-separated field).
   */
  private async existingSinks(): Promise<Set<string>> {
    const { code, stdout } = await this.run('pactl', ['list', 'short', 'sinks'])
    const names = new Set<string>()
    if (code !== 0) return names
    for (const line of stdout.split('\n')) {
      const fields = line.split('\t')
      if (fields.length >= 2 && fields[1] !== '') names.add(fields[1])
    }
    return names
  }

  /**
   * Create both devices unless they already exist (a crashed previous run
   * leaves them). Resolves true only when both are present afterwards.
   */
  async ensureDevices(): Promise<boolean> {
    let existing: Set<string>
    try {
      existing = await this.existingSinks()
    } catch (error) {
      logger.warn(LOG_SCOPE, 'pactl unavailable — per-track devices not created', { error: String(error) })
      return false
    }
    let ok = true
    for (const device of PER_TRACK_DEVICES) {
      if (existing.has(device.sinkName)) {
        logger.info(LOG_SCOPE, `sink already present — adopted: ${device.sinkName}`)
        continue
      }
      try {
        const { code, stdout } = await this.run('pactl', [
          'load-module',
          'module-null-sink',
          `sink_name=${device.sinkName}`,
          `sink_properties=device.description='${device.label}'`
        ])
        if (code !== 0) {
          logger.warn(LOG_SCOPE, `load-module failed for ${device.sinkName} — exit ${code}`)
          ok = false
          continue
        }
        // load-module prints the module index on stdout.
        const index = stdout.trim()
        if (index === '') {
          logger.warn(LOG_SCOPE, `load-module for ${device.sinkName} printed no index — cannot unload later`)
          ok = false
          continue
        }
        this.loadedModules.set(device.sinkName, index)
        logger.info(LOG_SCOPE, `created ${device.label} (module ${index})`)
      } catch (error) {
        logger.warn(LOG_SCOPE, `load-module could not run for ${device.sinkName}`, { error: String(error) })
        ok = false
      }
    }
    return ok
  }

  /** App quit: remove exactly what we loaded (adopted modules stay — not ours). */
  async unloadAll(): Promise<void> {
    for (const [sinkName, index] of this.loadedModules) {
      try {
        const { code } = await this.run('pactl', ['unload-module', index])
        if (code !== 0) logger.warn(LOG_SCOPE, `unload-module ${index} (${sinkName}) — exit ${code}`)
      } catch (error) {
        logger.warn(LOG_SCOPE, `unload-module ${index} (${sinkName}) could not run`, { error: String(error) })
      }
    }
    this.loadedModules.clear()
  }
}
