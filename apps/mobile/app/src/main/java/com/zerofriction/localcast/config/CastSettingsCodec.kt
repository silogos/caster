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
 * the caller — a cast must never run on guessed values.
 */
object CastSettingsCodec {

    /** Storage format version; a mismatch is "not ours" → defaults. */
    const val VERSION = 1

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
        if (stored.v != VERSION) return null
        val profile = QualityProfile.entries.firstOrNull { it.label == stored.profile } ?: return null
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
        )
    }
}
