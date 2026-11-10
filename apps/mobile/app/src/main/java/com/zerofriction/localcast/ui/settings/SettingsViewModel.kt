package com.zerofriction.localcast.ui.settings

import androidx.lifecycle.ViewModel
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.config.ProfileSelection
import com.zerofriction.localcast.config.ProfileValues
import com.zerofriction.localcast.config.QualityProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's cast settings in memory: every user action transforms
 * [CastSettings] and persists immediately (the injected `persist` is
 * [com.zerofriction.localcast.config.CastSettingsStore.save]; the store is
 * read back at cast start — "changes take effect on the next cast").
 *
 * The rules live in [CastSettings] (what a profile edit may do, what a
 * deleted selection falls back to); this only applies them and saves. Home
 * and the profile screens share one instance, so a profile edited on one
 * screen is the profile the other shows.
 */
class SettingsViewModel(
    initial: CastSettings = CastSettings.default(),
    private val persist: (CastSettings) -> Unit = {},
) : ViewModel() {

    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<CastSettings> = _settings.asStateFlow()

    // ---- Profiles (ADR-024) ----

    fun selectBuiltIn(profile: QualityProfile) {
        update { it.withSelection(ProfileSelection.BuiltIn(profile)) }
    }

    fun selectCustom(id: Int) {
        update { it.withSelection(ProfileSelection.Custom(id)) }
    }

    /** Adds the user's profile [name] holding [values] and selects it; the new id, or null when it was refused. */
    fun addProfile(name: String, values: ProfileValues): Int? {
        val before = _settings.value
        update { it.withProfileAdded(name, values) }
        return newProfileId(before)
    }

    /** A copy of the user's profile [id], selected; the copy's id, or null when it was refused. */
    fun duplicateProfile(id: Int): Int? {
        val before = _settings.value
        update { it.withProfileDuplicated(id) }
        return newProfileId(before)
    }

    fun renameProfile(id: Int, name: String) {
        update { it.withProfileRenamed(id, name) }
    }

    fun editProfile(id: Int, edit: (ProfileValues) -> ProfileValues) {
        update { it.withProfileEdited(id, edit) }
    }

    fun deleteProfile(id: Int) {
        update { it.withProfileDeleted(id) }
    }

    // ---- Audio tracks: a choice about the session, not about a profile ----

    fun setGameAudio(on: Boolean) {
        update { it.copy(gameAudio = on) }
    }

    fun setMic(on: Boolean) {
        update { it.copy(mic = on) }
    }

    /** Which microphone the cast records from — takes effect at the next mic start. */
    fun setMicDevice(device: MicDeviceSource) {
        update { it.copy(micDevice = device) }
    }

    private fun newProfileId(before: CastSettings): Int? {
        val known = before.customProfiles.map { it.id }.toSet()
        return _settings.value.customProfiles.firstOrNull { it.id !in known }?.id
    }

    private fun update(transform: (CastSettings) -> CastSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        persist(next)
    }
}
