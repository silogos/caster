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
    const val VERSION = 3

    /** The Phase10/11 formats — accepted through [LEGACY_PROFILE_LABELS]/[LEGACY_DEFAULT_AUTO_QUALITY]. */
    const val LEGACY_VERSION = 1
    const val LEGACY_VERSION_2 = 2

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

    private val json = Json { ignoreUnknownKeys = true }

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
        if (stored.v != VERSION && stored.v != LEGACY_VERSION && stored.v != LEGACY_VERSION_2) return null
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
        )
    }
}
