package com.zerofriction.localcast.config

/**
 * Cast settings — the mobile is the configuration owner (overview.md /
 * AGENTS.md invariants: every cast setting lives here and only here).
 *
 * Phase5 shipped the conservative fixed profile from webrtc.md: 720p, 30 fps,
 *4–6 Mbps, degradation preference BALANCED. Phase7 adds the game-audio
 * toggle (on by default — it's the product's core promise). Phase8 adds the
 * mic flag (off by default — a microphone must never stream without an
 * explicit user action, audio.md; the live on/off toggle during the cast is
 * the affordance until then). Phase10 adds the settings UI, the named
 * presets ([QualityProfile]) and persistence ([CastSettingsStore]); a cast
 * reads its config at start (ScanScreen), so settings changes take effect on
 * the next cast. Thermal-driven values arrive in Phase11.
 */
data class CastConfig(
    /** Profile name — display-only in the desktop status line via `session-info`. */
    val profile: String,
    /** Capture target: the long edge of the physical screen scaled down to this. */
    val longEdgePx: Int,
    /** Frames per second sent to the encoder. */
    val fps: Int,
    /** Sender bitrate floor/ceiling (webrtc.md Phase5 target: 4–6 Mbps). */
    val bitrateMinBps: Int,
    val bitrateMaxBps: Int,
    /** BALANCED: libwebrtc may drop resolution or fps under bandwidth pressure. */
    val degradationPreference: String,
    /** Cast the device's game audio (AudioPlaybackCapture) with the screen. */
    val gameAudio: Boolean,
    /** Start the cast with the microphone on (audio.md: off by default). */
    val mic: Boolean,
    /**
     * Stats/thermal-driven auto quality (Phase12, [thermal.md]):
     * conservative one-level step-downs with hysteresis, announced and
     * reversible — off leaves the cast exactly at the user's settings.
     */
    val autoQuality: Boolean,
    // ---- Advanced (designs/mobile-app.html, AdvancedCastSettings.kt) ----
    /** Hardware-with-fallback (today's behavior) or software-only encoders. */
    val encoderImpl: EncoderImplementation = EncoderImplementation.HARDWARE,
    /** H.264 High profile in the offer; false stays on (Constrained) Baseline. */
    val h264HighProfile: Boolean = true,
    /** The codec the offer advertises first (webrtc.md codec policy). */
    val preferredCodec: PreferredVideoCodec = PreferredVideoCodec.H264,
    /** Encoder-side fps cap; null follows the capture frame rate. */
    val encoderFpsLimit: Int? = null,
    /** Rate-control mode; CBR is libwebrtc's (today's) realtime mode. */
    val bitrateMode: EncoderBitrateMode = EncoderBitrateMode.CBR,
) {
    companion object {
        /** webrtc.md: initial sender targets (adjusted in Phases 10–12). */
        const val DEFAULT_PROFILE = "balanced"
        const val DEFAULT_LONG_EDGE_PX = 1280
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE_MIN_BPS = 4_000_000
        const val DEFAULT_BITRATE_MAX_BPS = 6_000_000
        const val DEGRADATION_BALANCED = "balanced"

        /** [DegradationStrategy] labels carried on the sender (RtpParameters). */
        const val DEGRADATION_FRAMERATE = "framerate"
        const val DEGRADATION_RESOLUTION = "resolution"

        /** audio.md: game audio is part of the default cast (set off in Phase10's UI). */
        const val DEFAULT_GAME_AUDIO = true

        /** audio.md/Phase8: the mic needs an explicit user action, not a default. */
        const val DEFAULT_MIC = false

        /** thermal.md Phase12: auto quality is part of the good default cast; a switch turns it off. */
        const val DEFAULT_AUTO_QUALITY = true

        fun default(): CastConfig = CastConfig(
            profile = DEFAULT_PROFILE,
            longEdgePx = DEFAULT_LONG_EDGE_PX,
            fps = DEFAULT_FPS,
            bitrateMinBps = DEFAULT_BITRATE_MIN_BPS,
            bitrateMaxBps = DEFAULT_BITRATE_MAX_BPS,
            degradationPreference = DEGRADATION_BALANCED,
            gameAudio = DEFAULT_GAME_AUDIO,
            mic = DEFAULT_MIC,
            autoQuality = DEFAULT_AUTO_QUALITY,
        )
    }
}
