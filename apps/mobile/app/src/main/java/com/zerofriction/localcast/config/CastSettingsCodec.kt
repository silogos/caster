package com.zerofriction.localcast.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Versioned JSON codec for persisted cast settings — pure, so the
 * persistence behaviors (round-trip, corrupt storage, unknown values,
 * migration) are plain-JVM unit-testable; [CastSettingsStore] is a thin
 * wrapper. Follows the repo's storage convention: any document this build
 * can't fully validate degrades to defaults, logged by the caller — a cast
 * must never run on guessed values.
 *
 * **v8 (ADR-025)** stores the selected profile, the user's own profiles and
 * the audio switches. **v7 (ADR-024)** had the same shape with two fields
 * where a profile now has one: `degradationStrategy` becomes `struggle`
 * ([StrugglePolicy.fromPressureLabel]) and `autoQuality` is dropped.
 *
 * **v1–v6** stored one flat set of values (a preset pick overwrote it; the
 * rest was an unnamed "Custom" state). They are still accepted and migrate
 * without losing a value: a flat set that equals a built-in profile selects
 * that profile; anything else becomes the user's first profile, "Custom 1",
 * selected. Two flat fields have no successor and are dropped: `bitrateAuto`
 * (a profile's window is simply its window) and `bitrateMode` (the setting
 * never took effect and is gone). The flat ladder itself: v1 → v2 was the
 * Phase11 preset relabels ([LEGACY_PROFILE_LABELS]), v2 → v3 added
 * `autoQuality` (dropped again in v8), v3 → v4 the encoder choices, v4 → v5 `micDevice`, v5 → v6
 * `lowLatencyGameAudio` — each with the default that was shipped then.
 */
object CastSettingsCodec {

    /** Storage format version; an unknown one is "not ours" → defaults. */
    const val VERSION = 8

    /** The first profile format: two fields where v8 has `struggle`. */
    const val PROFILES_V7 = 7

    /** The flat formats (see the class comment). */
    const val FIRST_FLAT_VERSION = 1
    const val LAST_FLAT_VERSION = 6

    /**
     * Phase11 preset relabels (`light`→`cool`, `smooth`→`performance`) —
     * v1 documents keep their stored label, translated here. The send
     * values are identical, so this is a rename, never a guess.
     */
    private val LEGACY_PROFILE_LABELS = mapOf(
        "light" to "cool",
        "smooth" to "performance",
    )

    /** How the selected profile is written: `builtin:<label>` or `custom:<id>`. */
    private const val SELECTED_BUILT_IN = "builtin:"
    private const val SELECTED_CUSTOM = "custom:"

    // encodeDefaults: the document carries every field explicitly — a version's
    // shape stays readable and future migration ladders strip what they know.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Every document starts with its version; the rest depends on it. */
    @Serializable
    private data class Versioned(val v: Int)

    /** Enums travel as labels (not names), so renaming a constant can't silently change what users stored. */
    @Serializable
    private data class StoredValues(
        val longEdgePx: Int,
        val fps: Int,
        val bitrateMinBps: Int,
        val bitrateMaxBps: Int,
        /** v8. Absent in v7, which had [degradationStrategy] instead. */
        val struggle: String? = null,
        val degradationStrategy: String? = null,
        val encoderImpl: String,
        val preferredCodec: String,
        val h264HighProfile: Boolean,
        val encoderFpsLimit: Int? = null,
        val lowLatencyGameAudio: Boolean,
    )

    @Serializable
    private data class StoredProfile(val id: Int, val name: String, val values: StoredValues)

    @Serializable
    private data class Stored(
        val v: Int,
        val selected: String,
        val profiles: List<StoredProfile>,
        val gameAudio: Boolean,
        val mic: Boolean,
        val micDevice: String,
    )

    /** A v1–v6 document. Fields a version did not have yet take the default shipped with it. */
    @Serializable
    private data class FlatStored(
        val v: Int,
        val profile: String,
        val longEdgePx: Int,
        val fps: Int,
        val bitrateMinBps: Int,
        val bitrateMaxBps: Int,
        val gameAudio: Boolean,
        val mic: Boolean,
        val micDevice: String = MicDeviceSource.BUILTIN.label,
        val lowLatencyGameAudio: Boolean = CastConfig.DEFAULT_LOW_LATENCY_GAME_AUDIO,
        val encoderImpl: String = EncoderImplementation.HARDWARE.label,
        val h264HighProfile: Boolean = true,
        val preferredCodec: String = PreferredVideoCodec.H264.label,
        val degradationStrategy: String = "balanced",
        val encoderFpsLimit: Int? = null,
    )

    fun encode(settings: CastSettings): String = json.encodeToString(
        Stored(
            v = VERSION,
            selected = when (val selection = settings.selection) {
                is ProfileSelection.BuiltIn -> SELECTED_BUILT_IN + selection.profile.label
                is ProfileSelection.Custom -> SELECTED_CUSTOM + selection.id
            },
            profiles = settings.customProfiles.map { StoredProfile(it.id, it.name, it.values.toStored()) },
            gameAudio = settings.gameAudio,
            mic = settings.mic,
            micDevice = settings.micDevice.label,
        ),
    )

    /** Null on anything this build can't fully validate — caller falls back to defaults. */
    fun decode(document: String): CastSettings? = try {
        when (json.decodeFromString<Versioned>(document).v) {
            VERSION, PROFILES_V7 -> decodeCurrent(json.decodeFromString(document))
            in FIRST_FLAT_VERSION..LAST_FLAT_VERSION -> decodeFlat(json.decodeFromString(document))
            else -> null
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodeCurrent(stored: Stored): CastSettings? {
        if (stored.profiles.size > CustomProfileRules.MAX_PROFILES) return null
        val profiles = stored.profiles.map { profile ->
            if (profile.id <= 0 || CustomProfileRules.cleanName(profile.name) != profile.name) return null
            CustomProfile(profile.id, profile.name, profile.values.toValues() ?: return null)
        }
        if (profiles.map { it.id }.toSet().size != profiles.size) return null
        val selection = when {
            stored.selected.startsWith(SELECTED_BUILT_IN) -> {
                val label = stored.selected.removePrefix(SELECTED_BUILT_IN)
                ProfileSelection.BuiltIn(QualityProfile.entries.firstOrNull { it.label == label } ?: return null)
            }
            stored.selected.startsWith(SELECTED_CUSTOM) -> {
                val id = stored.selected.removePrefix(SELECTED_CUSTOM).toIntOrNull() ?: return null
                if (profiles.none { it.id == id }) return null
                ProfileSelection.Custom(id)
            }
            else -> return null
        }
        return CastSettings(
            selection = selection,
            customProfiles = profiles,
            gameAudio = stored.gameAudio,
            mic = stored.mic,
            micDevice = MicDeviceSource.fromLabel(stored.micDevice) ?: return null,
        )
    }

    private fun decodeFlat(stored: FlatStored): CastSettings? {
        // The flat formats named the last preset picked; an unknown name is "not ours".
        val label = if (stored.v == FIRST_FLAT_VERSION) {
            LEGACY_PROFILE_LABELS[stored.profile] ?: stored.profile
        } else {
            stored.profile
        }
        if (QualityProfile.entries.none { it.label == label }) return null
        val values = StoredValues(
            longEdgePx = stored.longEdgePx,
            fps = stored.fps,
            bitrateMinBps = stored.bitrateMinBps,
            bitrateMaxBps = stored.bitrateMaxBps,
            degradationStrategy = stored.degradationStrategy,
            encoderImpl = stored.encoderImpl,
            preferredCodec = stored.preferredCodec,
            h264HighProfile = stored.h264HighProfile,
            encoderFpsLimit = stored.encoderFpsLimit,
            lowLatencyGameAudio = stored.lowLatencyGameAudio,
        ).toValues() ?: return null
        val settings = CastSettings(
            selection = ProfileSelection.BuiltIn(CustomProfileRules.FALLBACK),
            gameAudio = stored.gameAudio,
            mic = stored.mic,
            micDevice = MicDeviceSource.fromLabel(stored.micDevice) ?: return null,
        )
        val builtIn = QualityProfile.entries.firstOrNull { it.toValues() == values }
        return if (builtIn !== null) {
            settings.copy(selection = ProfileSelection.BuiltIn(builtIn))
        } else {
            settings.withProfileAdded(CustomProfileRules.nextName(emptyList()), values)
        }
    }

    private fun ProfileValues.toStored() = StoredValues(
        longEdgePx = longEdgePx,
        fps = fps,
        bitrateMinBps = bitrateMinBps,
        bitrateMaxBps = bitrateMaxBps,
        struggle = struggle.label,
        encoderImpl = encoderImpl.label,
        preferredCodec = preferredCodec.label,
        h264HighProfile = h264HighProfile,
        encoderFpsLimit = encoderFpsLimit,
        lowLatencyGameAudio = lowLatencyGameAudio,
    )

    /** Null for an unknown label or a value this build does not offer. */
    private fun StoredValues.toValues(): ProfileValues? {
        val values = ProfileValues(
            longEdgePx = longEdgePx,
            fps = fps,
            bitrateMinBps = bitrateMinBps,
            bitrateMaxBps = bitrateMaxBps,
            // v8 names the choice; older documents name the pressure strategy it came from.
            struggle = struggle?.let { StrugglePolicy.fromLabel(it) ?: return null }
                ?: StrugglePolicy.fromPressureLabel(degradationStrategy ?: return null)
                ?: return null,
            encoderImpl = EncoderImplementation.fromLabel(encoderImpl) ?: return null,
            preferredCodec = PreferredVideoCodec.fromLabel(preferredCodec) ?: return null,
            h264HighProfile = h264HighProfile,
            encoderFpsLimit = encoderFpsLimit,
            lowLatencyGameAudio = lowLatencyGameAudio,
        )
        return values.takeIf { it.isValid }
    }
}
