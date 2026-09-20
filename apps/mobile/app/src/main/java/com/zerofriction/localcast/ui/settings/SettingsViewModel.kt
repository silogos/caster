package com.zerofriction.localcast.ui.settings

import androidx.lifecycle.ViewModel
import com.zerofriction.localcast.config.CastSettingChoices.MANUAL_BITRATE_MAX_BPS
import com.zerofriction.localcast.config.CastSettingChoices.MANUAL_BITRATE_MIN_BPS
import com.zerofriction.localcast.config.CastSettingChoices.MANUAL_BITRATE_MIN_FRACTION
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.QualityProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the cast settings screen (Phase10): every user action transforms
 * [CastSettings] and persists immediately (the injected `persist` is
 * [com.zerofriction.localcast.config.CastSettingsStore.save]; the store is
 * read back at cast start — "changes take effect on the next cast").
 *
 * Semantics:
 *  - picking a preset applies all of its values and restores Auto bitrate;
 *  - tweaking resolution/fps/bitrate leaves the values custom (the
 *    desktop's display label says so via [CastSettings.toConfig]);
 *  - Auto bitrate = the chosen preset's window; manual = a user ceiling
 *    with the floor derived from it.
 */
class SettingsViewModel(
    initial: CastSettings = CastSettings.default(),
    private val persist: (CastSettings) -> Unit = {},
) : ViewModel() {

    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<CastSettings> = _settings.asStateFlow()

    fun selectProfile(profile: QualityProfile) {
        update {
            it.copy(
                profile = profile,
                longEdgePx = profile.longEdgePx,
                fps = profile.fps,
                bitrateAuto = true,
                bitrateMinBps = profile.bitrateMinBps,
                bitrateMaxBps = profile.bitrateMaxBps,
            )
        }
    }

    fun selectLongEdge(longEdgePx: Int) {
        update { it.copy(longEdgePx = longEdgePx) }
    }

    fun selectFps(fps: Int) {
        update { it.copy(fps = fps) }
    }

    /** Auto restores the chosen preset's window; manual derives the floor from the current ceiling. */
    fun setBitrateAuto(auto: Boolean) {
        update {
            if (auto) {
                val preset = it.profile
                it.copy(
                    bitrateAuto = true,
                    bitrateMinBps = preset.bitrateMinBps,
                    bitrateMaxBps = preset.bitrateMaxBps,
                )
            } else {
                it.copy(
                    bitrateAuto = false,
                    bitrateMinBps = manualMinFor(it.bitrateMaxBps),
                )
            }
        }
    }

    fun setManualBitrateMax(maxBps: Int) {
        update {
            val clamped = maxBps.coerceIn(MANUAL_BITRATE_MIN_BPS, MANUAL_BITRATE_MAX_BPS)
            it.copy(
                bitrateAuto = false,
                bitrateMinBps = manualMinFor(clamped),
                bitrateMaxBps = clamped,
            )
        }
    }

    fun setGameAudio(on: Boolean) {
        update { it.copy(gameAudio = on) }
    }

    fun setMic(on: Boolean) {
        update { it.copy(mic = on) }
    }

    /** Phase12 (thermal.md): the auto-quality switch — off means the cast runs exactly at the user's settings. */
    fun setAutoQuality(on: Boolean) {
        update { it.copy(autoQuality = on) }
    }

    private fun manualMinFor(maxBps: Int): Int = (maxBps * MANUAL_BITRATE_MIN_FRACTION).toInt()

    private fun update(transform: (CastSettings) -> CastSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        persist(next)
    }
}
