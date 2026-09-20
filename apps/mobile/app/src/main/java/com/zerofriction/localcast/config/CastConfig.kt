package com.zerofriction.localcast.config

/**
 * Cast settings — the mobile is the configuration owner (overview.md /
 * AGENTS.md invariants: every cast setting lives here and only here).
 *
 * Phase5 shipped the conservative fixed profile from webrtc.md: 720p, 30 fps,
 * 4–6 Mbps, degradation preference BALANCED. Phase7 adds the game-audio
 * toggle (on by default — it's the product's core promise); mic arrives in
 * Phase8. The settings UI, persistence and live-application UX arrive in
 * Phase10 ("changes take effect on next cast"); thermal-driven values arrive
 * in Phase11.
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
) {
    companion object {
        /** webrtc.md: initial sender targets (adjusted in Phases 10–12). */
        const val DEFAULT_PROFILE = "balanced"
        const val DEFAULT_LONG_EDGE_PX = 1280
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE_MIN_BPS = 4_000_000
        const val DEFAULT_BITRATE_MAX_BPS = 6_000_000
        const val DEGRADATION_BALANCED = "balanced"

        /** audio.md: game audio is part of the default cast (set off in Phase10's UI). */
        const val DEFAULT_GAME_AUDIO = true

        fun default(): CastConfig = CastConfig(
            profile = DEFAULT_PROFILE,
            longEdgePx = DEFAULT_LONG_EDGE_PX,
            fps = DEFAULT_FPS,
            bitrateMinBps = DEFAULT_BITRATE_MIN_BPS,
            bitrateMaxBps = DEFAULT_BITRATE_MAX_BPS,
            degradationPreference = DEGRADATION_BALANCED,
            gameAudio = DEFAULT_GAME_AUDIO,
        )
    }
}
