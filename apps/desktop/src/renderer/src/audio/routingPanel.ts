import type { DesktopApi } from '../../../shared/ipc'
import type { AudioMixer } from './mixer'
import { findCableOutputDevice, findPerTrackDevices, isRoutingMode, type RoutingMode } from './routing'

/**
 * The settings modal's audio-output panel (extracted from main.ts): where the
 * mixed cast audio goes (issue #54), the per-track Zero-Friction devices
 * (ADR-008), the one-click install row, and the honest per-platform guidance
 * (#74). Owns its elements, detection, and subscriptions; main.ts only gets
 * the panel's small surface: re-detect when the modal opens, apply the
 * phone's mirrored pick, dispose on teardown.
 */

export interface RoutingPanelDeps {
  mixer: AudioMixer
  api: DesktopApi
  log: (level: 'debug' | 'info' | 'warn' | 'error', message: string, details?: Record<string, unknown>) => void
}

export interface RoutingPanel {
  /** Re-run device detection (startup, each settings open, after an install). */
  detect(): Promise<void>
  /** Apply a routing pick (the select or the phone's mirror); validated, refused like a local pick. */
  applyMode(mode: unknown): void
  /** Drop the panel's subscriptions. */
  dispose(): void
}

export function wireRoutingPanel(deps: RoutingPanelDeps): RoutingPanel {
  const { mixer, api, log } = deps
  const audioOutputEl = document.getElementById('audio-output') as HTMLSelectElement
  const audioOutputNoteEl = document.getElementById('audio-output-note') as HTMLParagraphElement

  const CABLE_GUIDANCE =
    'For send-only audio, install a virtual cable: brew install blackhole-2ch (then restart).'
  const VBCABLE_GUIDANCE =
    'For send-only audio, install VB-Cable (vb-audio.com/cable/), then restart the app.'
  const LINUX_CABLE_PENDING =
    'Send-only audio uses the Zero-Friction devices — they are created automatically at startup (needs PipeWire/PulseAudio: pactl).'

  let cableDeviceId: string | null = null
  let labelsUnlocked = false
  // The main process reports its OS on the virtual-audio push (issue #74) —
  // the guidance below is honest per platform without sniffing the renderer.
  let desktopPlatform: 'darwin' | 'win32' | 'linux' | 'other' = 'other'

  /** What to tell the user when no cable device exists — per platform (#74). */
  const cableGuidanceText = (): string => {
    if (desktopPlatform === 'win32') return VBCABLE_GUIDANCE
    if (desktopPlatform === 'linux') return LINUX_CABLE_PENDING
    // macOS with the bundled driver: the Install row's own note explains the
    // better path — no third-party guidance on top of it.
    if (desktopPlatform === 'darwin' && virtualAudioSupported) return ''
    return CABLE_GUIDANCE
  }

  /** The effective state reaches the main process (cached for the phone's mirror, proto 2). */
  const reportRouting = (): void => {
    api.reportAudioRouting({
      mode: mixer.getRoutingMode(),
      cableAvailable: cableDeviceId !== null
    })
  }

  const renderRoutingUi = (device: { deviceId: string; label: string } | null): void => {
    cableDeviceId = device?.deviceId ?? null
    for (const option of Array.from(audioOutputEl.options)) {
      if (option.dataset.route === 'cable' || option.dataset.route === 'both') {
        option.disabled = cableDeviceId === null
      }
    }
    if (device === null) {
      audioOutputNoteEl.textContent = cableGuidanceText()
      // A mode stored by a previous session whose device is now gone falls
      // back to speakers — refusing beats a silent cast.
      if (mixer.getRoutingMode() !== 'speakers') mixer.setRoutingMode('speakers')
    } else {
      audioOutputNoteEl.textContent = `Cable: ${device.label}`
    }
    audioOutputEl.value = mixer.getRoutingMode()
    reportRouting()
  }

  // ADR-008 install UX: the Zero-Friction loopback devices as a one-click
  // install — one administrator password, once, and OBS/Zoom can pick game
  // audio and the mic as two separate input devices forever after. The row
  // appears only where the main process says installing is possible (macOS
  // with the bundled driver) AND the devices are missing.
  const virtualAudioRowEl = document.getElementById('virtual-audio-row') as HTMLDivElement
  const virtualAudioInstallButtonEl = document.getElementById('virtual-audio-install') as HTMLButtonElement
  const virtualAudioNoteEl = document.getElementById('virtual-audio-note') as HTMLParagraphElement

  const VIRTUAL_AUDIO_GUIDANCE =
    'Adds two input devices — Zero-Friction Game Audio and Zero-Friction Mic — that OBS and Zoom can pick separately. One administrator password, once.'
  const VIRTUAL_AUDIO_INSTALLING = 'Installing — enter this Mac’s administrator password…'
  const VIRTUAL_AUDIO_DECLINED = 'Install cancelled — the devices were not installed.'
  const VIRTUAL_AUDIO_FAILED = 'Install failed — try again.'
  /** coreaudiod restarts asynchronously after the elevated copy — poll briefly. */
  const REDETECT_ATTEMPTS = 10
  const REDETECT_INTERVAL_MS = 500

  let virtualAudioSupported = false
  let perTrackMissing = true
  let virtualAudioNoteText = VIRTUAL_AUDIO_GUIDANCE

  const renderVirtualAudioRow = (): void => {
    const visible = virtualAudioSupported && perTrackMissing
    virtualAudioRowEl.hidden = !visible
    virtualAudioNoteEl.hidden = !visible
    if (visible) virtualAudioNoteEl.textContent = virtualAudioNoteText
  }

  const detect = async (): Promise<void> => {
    try {
      // Output device labels/ids populate only after an audio permission was
      // granted once; the receiver runs in Electron with media permissions
      // granted by default, so this one-time probe unlocks them.
      if (!labelsUnlocked) {
        labelsUnlocked = true
        const probe = await navigator.mediaDevices.getUserMedia({ audio: true })
        probe.getTracks().forEach((track) => track.stop())
      }
      const devices = await navigator.mediaDevices.enumerateDevices()
      renderRoutingUi(findCableOutputDevice(devices))
      // Per-track devices (batch A, ADR-008): game audio and mic each get
      // their own input device — the two-track architecture end to end.
      const perTrack = findPerTrackDevices(devices)
      perTrackMissing = perTrack.gameAudio === null || perTrack.mic === null
      if (!perTrackMissing) virtualAudioNoteText = VIRTUAL_AUDIO_GUIDANCE
      mixer.setPerTrackCableDevices({
        game: perTrack.gameAudio?.deviceId,
        mic: perTrack.mic?.deviceId
      })
      renderVirtualAudioRow()
    } catch (error) {
      log('warn', 'audio output device detection failed', { error: String(error) })
      renderRoutingUi(null)
    }
  }

  const applyMode = (mode: string): void => {
    if (!isRoutingMode(mode)) return
    mixer.setRoutingMode(mode as RoutingMode, cableDeviceId ?? undefined)
    audioOutputEl.value = mixer.getRoutingMode()
    reportRouting()
  }

  const unsubscribeVirtualAudioState = api.onVirtualAudioState((state) => {
    virtualAudioSupported = state.supported
    desktopPlatform = state.platform
    renderVirtualAudioRow()
    // The guidance text may have changed with the platform — refresh it if the
    // no-cable note is what's on screen right now.
    if (cableDeviceId === null) audioOutputNoteEl.textContent = cableGuidanceText()
  })

  virtualAudioInstallButtonEl.addEventListener('click', () => {
    if (virtualAudioInstallButtonEl.disabled) return
    virtualAudioInstallButtonEl.disabled = true
    virtualAudioNoteText = VIRTUAL_AUDIO_INSTALLING
    renderVirtualAudioRow()
    void api.installVirtualAudio().then(async (result) => {
      if (result === 'installed') {
        // coreaudiod restarts asynchronously — poll until the devices enumerate.
        for (let attempt = 0; attempt < REDETECT_ATTEMPTS && perTrackMissing; attempt++) {
          await new Promise((resolve) => setTimeout(resolve, REDETECT_INTERVAL_MS))
          await detect()
        }
        virtualAudioNoteText = perTrackMissing ? VIRTUAL_AUDIO_FAILED : VIRTUAL_AUDIO_GUIDANCE
      } else {
        // A cancelled password prompt is a user decision, not an error.
        virtualAudioNoteText = result === 'declined' ? VIRTUAL_AUDIO_DECLINED : VIRTUAL_AUDIO_FAILED
      }
      virtualAudioInstallButtonEl.disabled = false
      renderVirtualAudioRow()
    })
  })

  audioOutputEl.addEventListener('change', () => applyMode(audioOutputEl.value))

  // The phone's mirror (proto 2): a cable route without a device is refused
  // exactly like a local pick; the report flows back as the honest status.
  const unsubscribeAudioRoutingSet = api.onAudioRoutingSet((mode) => applyMode(mode))

  // Detect once at startup too (not just on modal open): the phone may query
  // or set the route during a cast before Settings was ever opened.
  void detect()

  return {
    detect,
    applyMode,
    dispose: () => {
      unsubscribeVirtualAudioState()
      unsubscribeAudioRoutingSet()
    }
  }
}
