package com.zerofriction.localcast.config

/**
 * The advanced cast settings (designs/mobile-app.html, the settings screen's
 * Advanced section): encoder-level knobs for the contention cases the presets
 * don't cover — e.g. a SoC whose video hardware competes with a game
 * (risk-register R5: drop to software per-device, on evidence).
 *
 * Every default reproduces today's cast exactly (hardware-with-fallback
 * encoder, H.264 first, H.264 High profile, BALANCED degradation, no
 * encoder-side fps cap, CBR) — a fresh install behaves identically to the
 * pre-Advanced app, and each knob is an explicit user override.
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

/** What the cast gives up first under bandwidth/encoder pressure ([CastConfig.degradationPreference]). */
enum class DegradationStrategy(val label: String) {
    BALANCED("balanced"),
    MAINTAIN_FRAMERATE("framerate"),
    MAINTAIN_RESOLUTION("resolution");

    companion object {
        fun fromLabel(label: String): DegradationStrategy? = entries.firstOrNull { it.label == label }
    }
}

/**
 * The encoder's rate-control mode. CBR (libwebrtc's hardcoded realtime mode)
 * works at full effort every frame; VBR spends fewer bits on easy frames,
 * lowering the encoder's average load. Phase-limited: VBR needs the forked
 * encoder (see docs/decisions/) — until it lands, everything encodes CBR.
 */
enum class EncoderBitrateMode(val label: String) {
    CBR("cbr"),
    VBR("vbr");

    companion object {
        fun fromLabel(label: String): EncoderBitrateMode? = entries.firstOrNull { it.label == label }
    }
}

/** The discrete choices of the Advanced section — named, not magic (AGENTS.md). */
object AdvancedSettingChoices {
    /** Encoder-side fps caps; null (Off) follows the capture frame rate. */
    val ENCODER_FPS_LIMIT_CHOICES = listOf(15, 30, 60)
}
