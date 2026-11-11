package com.zerofriction.localcast.config

import kotlin.math.roundToInt

/*
 * Profiles (ADR-024): a profile is everything that shapes a cast — the
 * picture's size, frame rate and bitrate, what it gives up under pressure,
 * its encoder, and how the game audio travels. The four built-in profiles ([QualityProfile]) are fixed; the user
 * adds their own, each one a named, editable copy. One profile is selected,
 * and the next cast runs on it.
 *
 * Before this there was one settings document: a preset pick overwrote the
 * Advanced values with it, and anything else was an unnamed "Custom" state.
 * A setup that started as Sharp and was changed to 60 fps still carried
 * Sharp's "Keep resolution" and its 8 Mbps floor, with nothing on screen
 * saying so (found 2026-11-11, latency-measurement.md).
 */

/** What a profile fixes for a cast. */
data class ProfileValues(
    /** Capture target: the long edge of the physical screen scaled down to this. */
    val longEdgePx: Int,
    /** Frames per second sent to the encoder. */
    val fps: Int,
    /** The sender's bitrate window (webrtc.md sender targets). */
    val bitrateMinBps: Int,
    val bitrateMaxBps: Int,
    /** What the cast gives up when it struggles (ADR-025). */
    val struggle: StrugglePolicy,
    val encoderImpl: EncoderImplementation,
    /** The codec the offer advertises first. */
    val preferredCodec: PreferredVideoCodec,
    /** H.264 High profile in the offer; false stays on Baseline. */
    val h264HighProfile: Boolean,
    /** Encoder-side fps cap; null follows [fps]. */
    val encoderFpsLimit: Int?,
    /** Keep the game-audio channel on raw PCM16 (Feature 2026-11d; false = the AAC default). */
    val lowLatencyGameAudio: Boolean,
) {
    /**
     * A user-picked bitrate range: both ends on the slider's steps and inside
     * its ends, the most at least one step above the least. The sender never
     * goes under the least (ADR-023, revised).
     */
    fun withBitrateRange(minBps: Int, maxBps: Int): ProfileValues {
        val step = CastSettingChoices.BITRATE_STEP_BPS
        fun onStep(bps: Int) = (bps.toDouble() / step).roundToInt() * step
        val least = onStep(minBps).coerceIn(
            CastSettingChoices.BITRATE_SLIDER_MIN_BPS,
            CastSettingChoices.MANUAL_BITRATE_MAX_BPS - step,
        )
        val most = onStep(maxBps).coerceIn(least + step, CastSettingChoices.MANUAL_BITRATE_MAX_BPS)
        return copy(bitrateMinBps = least, bitrateMaxBps = most)
    }

    /** Whether every value is one this build offers — a cast must never run on guessed values. */
    val isValid: Boolean
        get() = longEdgePx in CastSettingChoices.LONG_EDGE_CHOICES &&
            fps in CastSettingChoices.FPS_CHOICES &&
            bitrateMinBps in 1 until bitrateMaxBps &&
            bitrateMaxBps <= CastSettingChoices.MANUAL_BITRATE_MAX_BPS &&
            (encoderFpsLimit === null || encoderFpsLimit in AdvancedSettingChoices.ENCODER_FPS_LIMIT_CHOICES)
}

/** A profile the user made: a name over its own [values]. */
data class CustomProfile(
    /** Stable for the profile's life; never reused within a settings document. */
    val id: Int,
    val name: String,
    val values: ProfileValues,
)

/** Which profile the next cast runs on. */
sealed interface ProfileSelection {
    data class BuiltIn(val profile: QualityProfile) : ProfileSelection
    data class Custom(val id: Int) : ProfileSelection
}

/** A built-in profile's values: its own picture and pressure strategy over the shipped encoder defaults. */
fun QualityProfile.toValues(): ProfileValues = ProfileValues(
    longEdgePx = longEdgePx,
    fps = fps,
    bitrateMinBps = bitrateMinBps,
    bitrateMaxBps = bitrateMaxBps,
    struggle = struggle,
    encoderImpl = EncoderImplementation.HARDWARE,
    preferredCodec = PreferredVideoCodec.H264,
    h264HighProfile = h264HighProfile,
    encoderFpsLimit = null,
    lowLatencyGameAudio = CastConfig.DEFAULT_LOW_LATENCY_GAME_AUDIO,
)

/** The limits on the user's own profiles — named, not magic (AGENTS.md). */
object CustomProfileRules {
    /** A name is shown in a list row on the phone and on the desktop's status line. */
    const val MAX_NAME_LENGTH = 20

    /** A list this long still fits the Home card without becoming the page. */
    const val MAX_PROFILES = 12

    /** The built-in profile a deleted selection falls back to: the app's default. */
    val FALLBACK = QualityProfile.BALANCED

    /** The name as it would be stored, or null when it is empty or too long. */
    fun cleanName(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

    /** "Custom 1", "Custom 2", …: the first one not taken. */
    fun nextName(existing: List<CustomProfile>): String {
        val taken = existing.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { "$NAME_PREFIX $it" }.first { it !in taken }
    }

    private const val NAME_PREFIX = "Custom"
}
