package com.zerofriction.localcast.config

/**
 * A profile's encoder and pressure choices ([ProfileValues]): the knobs for
 * the contention cases the built-in profiles don't cover — e.g. a SoC whose
 * video hardware competes with a game (risk-register R5: drop to software
 * per-device, on evidence).
 *
 * The built-in profiles carry the shipped defaults (hardware-with-fallback
 * encoder, H.264 first, no encoder-side fps cap); the user changes them in a
 * profile of their own. There is no rate-control choice: libwebrtc fixes the
 * hardware encoder to CBR, and the bitrate follows the network through the
 * profile's window (ADR-023, ADR-024).
 *
 * Stored as labels (not enum names) via [CastSettingsCodec] — renaming a
 * constant can't silently change what users stored (same convention as the
 * preset labels).
 */
enum class EncoderImplementation(val label: String) {
    /** The SoC's video hardware first, with libwebrtc's software fallback — today's behavior. */
    HARDWARE("hardware"),

    /**
     * MediaCodec software encoders only (OMX.google/c2.android) — frees the
     * video hardware for a game at the cost of CPU heat. The device-specific
     * escape hatch when a hardware encoder misbehaves.
     */
    SOFTWARE("software");

    companion object {
        fun fromLabel(label: String): EncoderImplementation? = entries.firstOrNull { it.label == label }
    }
}

/** The codec the offer advertises first (webrtc.md codec policy, [com.zerofriction.localcast.webrtc.SdpCodecOrderer]). */
enum class PreferredVideoCodec(val label: String, val sdpName: String) {
    H264("h264", "h264"),
    VP8("vp8", "vp8");

    companion object {
        fun fromLabel(label: String): PreferredVideoCodec? = entries.firstOrNull { it.label == label }
    }
}

/**
 * What the cast gives up when it struggles — a slow network, a hot phone or
 * an overloaded encoder alike (ADR-025). One choice where there used to be
 * two ("Under network pressure" and "Automatic quality"): the app lowers the
 * picture one step at a time in this order and brings it back by itself
 * ([com.zerofriction.localcast.adaptive.AdaptiveQualityController]).
 */
enum class StrugglePolicy(val label: String) {
    /** Detail goes first; the frame rate goes last. */
    SMOOTH("smooth"),

    /** The frame rate goes first; detail stays. */
    SHARP("sharp"),

    /** A little of both. */
    BALANCED("balanced"),

    /** Neither is ever lowered; the bitrate still moves inside the profile's range. */
    FIXED("fixed");

    companion object {
        fun fromLabel(label: String): StrugglePolicy? = entries.firstOrNull { it.label == label }

        /**
         * What a stored "Under network pressure" label becomes (v7 and
         * older documents). The old "Automatic quality: off" does not map to
         * [FIXED]: it was turned off because the old rule went to the lowest
         * quality on a temperature reading, which the new rule does not do.
         */
        fun fromPressureLabel(label: String): StrugglePolicy? = when (label) {
            "framerate" -> SMOOTH
            "resolution" -> SHARP
            "balanced" -> BALANCED
            else -> null
        }
    }
}

/** The discrete choices of a profile's encoder card — named, not magic (AGENTS.md). */
object AdvancedSettingChoices {
    /** Encoder-side fps caps; null (Off) follows the capture frame rate. */
    val ENCODER_FPS_LIMIT_CHOICES = listOf(15, 30, 60)
}
