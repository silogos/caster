package com.zerofriction.localcast.config

/**
 * Cast settings — the mobile is the configuration owner (overview.md /
 * AGENTS.md invariants: every cast setting lives here and only here).
 *
 * Phase5 ships the conservative fixed profile from webrtc.md: 720p, 30 fps,
 * 4–6 Mbps, degradation preference BALANCED. The settings UI, persistence and
 * sender-side application UX arrive in Phase10 ("changes take effect on next
 * cast or live if cheap"); thermal-driven values arrive in Phase11.
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
) {
    companion object {
        /** webrtc.md: initial sender targets (adjusted in Phases 10–12). */
        const val DEFAULT_PROFILE = "balanced"
        const val DEFAULT_LONG_EDGE_PX = 1280
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE_MIN_BPS = 4_000_000
        const val DEFAULT_BITRATE_MAX_BPS = 6_000_000
        const val DEGRADATION_BALANCED = "balanced"

        fun phase5Default(): CastConfig = CastConfig(
            profile = DEFAULT_PROFILE,
            longEdgePx = DEFAULT_LONG_EDGE_PX,
            fps = DEFAULT_FPS,
            bitrateMinBps = DEFAULT_BITRATE_MIN_BPS,
            bitrateMaxBps = DEFAULT_BITRATE_MAX_BPS,
            degradationPreference = DEGRADATION_BALANCED,
        )
    }
}
