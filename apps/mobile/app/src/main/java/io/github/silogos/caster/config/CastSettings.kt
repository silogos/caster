package io.github.silogos.caster.config

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
    /**
     * The profile a cast over the USB cable uses (ADR-034): Capture until the
     * user picks another while the cable is in. [selection] stays the Wi-Fi
     * pick, so unplugging goes back to it.
     */
    val cableSelection: ProfileSelection = ProfileSelection.BuiltIn(QualityProfile.CAPTURE),
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
        get() = valuesOf(selection)

    /** The selected profile's name for the desktop's display-only status line (session-info, webrtc.md). */
    val activeName: String
        get() = nameOf(selection)

    private fun valuesOf(target: ProfileSelection): ProfileValues = when (target) {
        is ProfileSelection.BuiltIn -> target.profile.toValues()
        is ProfileSelection.Custom -> customProfile(target.id)?.values ?: CustomProfileRules.FALLBACK.toValues()
    }

    private fun nameOf(target: ProfileSelection): String = when (target) {
        is ProfileSelection.BuiltIn -> target.profile.label
        is ProfileSelection.Custom -> customProfile(target.id)?.name ?: CustomProfileRules.FALLBACK.label
    }

    /** What a cast over Wi-Fi runs on: the selected profile. */
    fun toConfig(): CastConfig = configFor(selection)

    /** What a cast over the USB cable runs on: the cable's profile (ADR-034). */
    fun toCableConfig(): CastConfig = configFor(cableSelection)

    private fun configFor(target: ProfileSelection): CastConfig {
        val values = valuesOf(target)
        return CastConfig(
            profile = nameOf(target),
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
            cableSelection = if (cableSelection == ProfileSelection.Custom(id)) {
                ProfileSelection.BuiltIn(QualityProfile.CAPTURE)
            } else {
                cableSelection
            },
        )
    }

    /** Makes [target] the cable's profile when it names a profile that exists. */
    fun withCableSelection(target: ProfileSelection): CastSettings = when (target) {
        is ProfileSelection.BuiltIn -> copy(cableSelection = target)
        is ProfileSelection.Custom -> if (customProfile(target.id) !== null) copy(cableSelection = target) else this
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
