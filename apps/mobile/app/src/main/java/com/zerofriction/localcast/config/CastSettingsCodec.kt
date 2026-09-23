package com.zerofriction.localcast.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Versioned JSON codec for persisted cast settings (Phase10) — pure, so the
 * persistence behaviors (round-trip, corrupt storage, unknown values) are
 * plain-JVM unit-testable; [CastSettingsStore] is a thin wrapper. Follows the
 * repo's storage convention (same as the desktop mixer's `localStorage`): any
 * document this build can't fully validate degrades to defaults, logged by
 * the caller — a cast must never run on guessed values. Older documents are
 * still accepted through the migration ladder: v1 → v2 was the Phase11 preset
 * relabels ([LEGACY_PROFILE_LABELS]), v2 → v3 added `autoQuality` (Phase12)
 * with the on default — every other shape stays fully valid.
 */
object CastSettingsCodec {

    /** Storage format version; a mismatch is "not ours" → defaults. */
    const val VERSION = 4

    /** The Phase10/11 formats — accepted through [LEGACY_PROFILE_LABELS]/[LEGACY_DEFAULT_AUTO_QUALITY]. */
    const val LEGACY_VERSION = 1
    const val LEGACY_VERSION_2 = 2
    const val LEGACY_VERSION_3 = 3

    /** Older documents predate the auto-quality switch — the shipped default applies. */
    private const val LEGACY_DEFAULT_AUTO_QUALITY = CastConfig.DEFAULT_AUTO_QUALITY

    /**
     * Phase11 preset relabels (`light`→`cool`, `smooth`→`performance`) —
     * v1 documents keep their stored label, translated here. The send
     * values are identical, so this is a rename, never a guess.
     */
    private val LEGACY_PROFILE_LABELS = mapOf(
        "light" to "cool",
        "smooth" to "performance",
    )

    // encodeDefaults: the document carries every field explicitly — a version's
    // shape stays readable and future migration ladders strip what they know.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The stored document. The preset travels as its label (not the enum
     * name) so renaming a constant can't silently change what users stored.
     */
    @Serializable
    private data class Stored(
        val v: Int,
        val profile: String,
        val longEdgePx: Int,
        val fps: Int,
        val bitrateAuto: Boolean,
        val bitrateMinBps: Int,
        val bitrateMaxBps: Int,
        val gameAudio: Boolean,
        val mic: Boolean,
        /** Absent in v1/v2 documents — the default applies (kotlinx fills defaults). */
        val autoQuality: Boolean = LEGACY_DEFAULT_AUTO_QUALITY,
        /** Absent in pre-v4 documents — today's-behavior defaults apply (AdvancedCastSettings.kt). */
        val encoderImpl: String = EncoderImplementation.HARDWARE.label,
        val h264HighProfile: Boolean = true,
        val preferredCodec: String = PreferredVideoCodec.H264.label,
        val degradationStrategy: String = DegradationStrategy.BALANCED.label,
        val encoderFpsLimit: Int? = null,
        val bitrateMode: String = EncoderBitrateMode.CBR.label,
    )

    fun encode(settings: CastSettings): String = json.encodeToString(
        Stored(
            v = VERSION,
            profile = settings.profile.label,
            longEdgePx = settings.longEdgePx,
            fps = settings.fps,
            bitrateAuto = settings.bitrateAuto,
            bitrateMinBps = settings.bitrateMinBps,
            bitrateMaxBps = settings.bitrateMaxBps,
            gameAudio = settings.gameAudio,
            mic = settings.mic,
            autoQuality = settings.autoQuality,
            encoderImpl = settings.encoderImpl.label,
            h264HighProfile = settings.h264HighProfile,
            preferredCodec = settings.preferredCodec.label,
            degradationStrategy = settings.degradationStrategy.label,
            encoderFpsLimit = settings.encoderFpsLimit,
            bitrateMode = settings.bitrateMode.label,
        ),
    )

    /** Null on anything this build can't fully validate — caller falls back to defaults. */
    fun decode(document: String): CastSettings? {
        val stored = try {
            json.decodeFromString<Stored>(document)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (stored.v != VERSION && stored.v != LEGACY_VERSION && stored.v != LEGACY_VERSION_2 && stored.v != LEGACY_VERSION_3) return null
        // v1 documents predate the Phase11 thermal relabels — translate, don't guess.
        val label = if (stored.v == LEGACY_VERSION) {
            LEGACY_PROFILE_LABELS[stored.profile] ?: stored.profile
        } else {
            stored.profile
        }
        val profile = QualityProfile.entries.firstOrNull { it.label == label } ?: return null
        if (stored.longEdgePx !in CastSettingChoices.LONG_EDGE_CHOICES) return null
        if (stored.fps !in CastSettingChoices.FPS_CHOICES) return null
        if (stored.bitrateMinBps !in 1 until stored.bitrateMaxBps) return null
        if (stored.bitrateMaxBps > CastSettingChoices.MANUAL_BITRATE_MAX_BPS) return null
        // Advanced (v4): unknown labels and out-of-choice caps are "not ours".
        val encoderImpl = EncoderImplementation.fromLabel(stored.encoderImpl) ?: return null
        val preferredCodec = PreferredVideoCodec.fromLabel(stored.preferredCodec) ?: return null
        val degradationStrategy = DegradationStrategy.fromLabel(stored.degradationStrategy) ?: return null
        val bitrateMode = EncoderBitrateMode.fromLabel(stored.bitrateMode) ?: return null
        val encoderFpsLimit = stored.encoderFpsLimit
        if (encoderFpsLimit !== null && encoderFpsLimit !in AdvancedSettingChoices.ENCODER_FPS_LIMIT_CHOICES) return null
        return CastSettings(
            profile = profile,
            longEdgePx = stored.longEdgePx,
            fps = stored.fps,
            bitrateAuto = stored.bitrateAuto,
            bitrateMinBps = stored.bitrateMinBps,
            bitrateMaxBps = stored.bitrateMaxBps,
            gameAudio = stored.gameAudio,
            mic = stored.mic,
            autoQuality = stored.autoQuality,
            encoderImpl = encoderImpl,
            h264HighProfile = stored.h264HighProfile,
            preferredCodec = preferredCodec,
            degradationStrategy = degradationStrategy,
            encoderFpsLimit = encoderFpsLimit,
            bitrateMode = bitrateMode,
        )
    }
}
