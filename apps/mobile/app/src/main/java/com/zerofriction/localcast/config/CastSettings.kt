package com.zerofriction.localcast.config

/**
 * Everything the app persists about a cast: which profile is selected, the
 * user's own profiles, and which audio tracks ride it — serialized by
 * [CastSettingsCodec] and stored by [CastSettingsStore]. [toConfig] is the
 * single conversion into what a cast actually consumes — [CastConfig] stays
 * the one thing the rest of the app sees (AGENTS.md: one home for cast
 * settings).
 *
 * The cast's values are the selected profile's ([active]); nothing here
 * duplicates them, so a profile is the whole answer to "what will this cast
 * do" (ADR-024). The two audio switches are not part of a profile: which
 * tracks ride the cast is a choice about the session. How the game audio
 * travels is the profile's.
 */
data class CastSettings(
    val selection: ProfileSelection,
    /** The user's own profiles, in the order they were made. */
    val customProfiles: List<CustomProfile> = emptyList(),
    /** Cast the device's game audio (AudioPlaybackCapture) with the screen. */
    val gameAudio: Boolean,
    /** Start the cast with the microphone on (audio.md: off by default). */
    val mic: Boolean,
    /** Which microphone the cast records from (MicDeviceSource, audio.md). */
    val micDevice: MicDeviceSource = MicDeviceSource.BUILTIN,
) {
    /** The user's profile with this id, or null. */
    fun customProfile(id: Int): CustomProfile? = customProfiles.firstOrNull { it.id == id }

    /**
     * The selected profile's values. A selection that points at no profile
     * cannot be stored (the codec rejects it, every edit here keeps it
     * whole); should one exist anyway, the cast runs on the default profile
     * rather than on nothing.
     */
    val active: ProfileValues
        get() = when (val selected = selection) {
            is ProfileSelection.BuiltIn -> selected.profile.toValues()
            is ProfileSelection.Custom -> customProfile(selected.id)?.values ?: CustomProfileRules.FALLBACK.toValues()
        }

    /** The selected profile's name for the desktop's display-only status line (session-info, webrtc.md). */
    val activeName: String
        get() = when (val selected = selection) {
            is ProfileSelection.BuiltIn -> selected.profile.label
            is ProfileSelection.Custom -> customProfile(selected.id)?.name ?: CustomProfileRules.FALLBACK.label
        }

    fun toConfig(): CastConfig {
        val values = active
        return CastConfig(
            profile = activeName,
            longEdgePx = values.longEdgePx,
            fps = values.fps,
            bitrateMinBps = values.bitrateMinBps,
            bitrateMaxBps = values.bitrateMaxBps,
            degradationPreference = when (values.degradationStrategy) {
                DegradationStrategy.BALANCED -> CastConfig.DEGRADATION_BALANCED
                DegradationStrategy.MAINTAIN_FRAMERATE -> CastConfig.DEGRADATION_FRAMERATE
                DegradationStrategy.MAINTAIN_RESOLUTION -> CastConfig.DEGRADATION_RESOLUTION
            },
            gameAudio = gameAudio,
            mic = mic,
            micDevice = micDevice,
            autoQuality = values.autoQuality,
            lowLatencyGameAudio = values.lowLatencyGameAudio,
            encoderImpl = values.encoderImpl,
            h264HighProfile = values.h264HighProfile,
            preferredCodec = values.preferredCodec,
            encoderFpsLimit = values.encoderFpsLimit,
        )
    }

    // ---- The user's own profiles. Each returns the settings unchanged when the edit is not allowed. ----

    /** Whether one more profile fits ([CustomProfileRules.MAX_PROFILES]). */
    val canAddProfile: Boolean get() = customProfiles.size < CustomProfileRules.MAX_PROFILES

    /** Adds a profile named [name] holding [values] and selects it. */
    fun withProfileAdded(name: String, values: ProfileValues): CastSettings {
        val clean = CustomProfileRules.cleanName(name) ?: return this
        if (!canAddProfile || !values.isValid) return this
        val id = (customProfiles.maxOfOrNull { it.id } ?: 0) + 1
        return copy(
            selection = ProfileSelection.Custom(id),
            customProfiles = customProfiles + CustomProfile(id, clean, values),
        )
    }

    /** A copy of the user's profile [id] under the next free name, selected. */
    fun withProfileDuplicated(id: Int): CastSettings {
        val source = customProfile(id) ?: return this
        return withProfileAdded(CustomProfileRules.nextName(customProfiles), source.values)
    }

    fun withProfileRenamed(id: Int, name: String): CastSettings {
        val clean = CustomProfileRules.cleanName(name) ?: return this
        return copy(customProfiles = customProfiles.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Changes the values of the user's profile [id]; a result this build does not offer is refused. */
    fun withProfileEdited(id: Int, edit: (ProfileValues) -> ProfileValues): CastSettings {
        val source = customProfile(id) ?: return this
        val edited = edit(source.values)
        if (!edited.isValid) return this
        return copy(customProfiles = customProfiles.map { if (it.id == id) it.copy(values = edited) else it })
    }

    /** Removes the user's profile [id]; when it was selected, the app's default profile takes over. */
    fun withProfileDeleted(id: Int): CastSettings {
        if (customProfile(id) === null) return this
        return copy(
            selection = if (selection == ProfileSelection.Custom(id)) {
                ProfileSelection.BuiltIn(CustomProfileRules.FALLBACK)
            } else {
                selection
            },
            customProfiles = customProfiles.filterNot { it.id == id },
        )
    }

    /** Selects [target] when it names a profile that exists. */
    fun withSelection(target: ProfileSelection): CastSettings = when (target) {
        is ProfileSelection.BuiltIn -> copy(selection = target)
        is ProfileSelection.Custom -> if (customProfile(target.id) !== null) copy(selection = target) else this
    }

    companion object {
        /** The Balanced profile + the Phase7/8 audio defaults (game audio on, mic off). */
        fun default(): CastSettings = CastSettings(
            selection = ProfileSelection.BuiltIn(QualityProfile.BALANCED),
            gameAudio = CastConfig.DEFAULT_GAME_AUDIO,
            mic = CastConfig.DEFAULT_MIC,
        )
    }
}
