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
    /** What the cast gives up when it struggles; the app steps the picture, libwebrtc only the bitrate (ADR-025). */
    val struggle: StrugglePolicy,
    /** Cast the device's game audio (AudioPlaybackCapture) with the screen. */
    val gameAudio: Boolean,
    /** Start the cast with the microphone on (audio.md: off by default). */
    val mic: Boolean,
    /**
     * Which microphone the cast records from (MicDeviceSource): the built-in
     * mic by default, else a wired/USB/Bluetooth mic resolved at mic-session
     * start — a kind that isn't connected falls back to the built-in mic
     * (MicDeviceResolver), never a failed session.
     */
    val micDevice: MicDeviceSource = MicDeviceSource.BUILTIN,
    /**
     * Feature 2026-11d: keep the game-audio DataChannel on raw PCM16 (ADR-012)
     * instead of negotiating AAC (ADR-013) — the codec's ~40–50 ms latency
     * leaves the ear path, at the cost of wire bitrate (~1.5 vs ~0.2 Mbps,
     * trivial on a LAN). Off = today's AAC default; the wire never changes
     * shape (the `pcm16` announce already exists in the channel sub-protocol).
     */
    val lowLatencyGameAudio: Boolean = DEFAULT_LOW_LATENCY_GAME_AUDIO,
    // ---- The profile's encoder choices (AdvancedCastSettings.kt) ----
    /** Hardware-with-fallback (today's behavior) or software-only encoders. */
    val encoderImpl: EncoderImplementation = EncoderImplementation.HARDWARE,
    /** H.264 High profile in the offer; false stays on (Constrained) Baseline. */
    val h264HighProfile: Boolean = true,
    /** The codec the offer advertises first (webrtc.md codec policy). */
    val preferredCodec: PreferredVideoCodec = PreferredVideoCodec.H264,
    /** Encoder-side fps cap; null follows the capture frame rate. */
    val encoderFpsLimit: Int? = null,
) {
    companion object {
        /** webrtc.md: initial sender targets (adjusted in Phases 10–12). */
        const val DEFAULT_PROFILE = "balanced"
        const val DEFAULT_LONG_EDGE_PX = 1280
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE_MIN_BPS = 4_000_000
        const val DEFAULT_BITRATE_MAX_BPS = 6_000_000
        /** audio.md: game audio is part of the default cast (set off in Phase10's UI). */
        const val DEFAULT_GAME_AUDIO = true

        /** audio.md/Phase8: the mic needs an explicit user action, not a default. */
        const val DEFAULT_MIC = false

        /** Feature 2026-11d: AAC (ADR-013) stays the default wire; the toggle opts into raw PCM. */
        const val DEFAULT_LOW_LATENCY_GAME_AUDIO = false

        fun default(): CastConfig = CastConfig(
            profile = DEFAULT_PROFILE,
            longEdgePx = DEFAULT_LONG_EDGE_PX,
            fps = DEFAULT_FPS,
            bitrateMinBps = DEFAULT_BITRATE_MIN_BPS,
            bitrateMaxBps = DEFAULT_BITRATE_MAX_BPS,
            struggle = StrugglePolicy.BALANCED,
            gameAudio = DEFAULT_GAME_AUDIO,
            mic = DEFAULT_MIC,
        )
    }
}
