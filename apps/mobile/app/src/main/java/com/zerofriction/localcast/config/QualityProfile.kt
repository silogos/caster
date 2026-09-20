package com.zerofriction.localcast.config

/**
 * Named cast quality presets (Phase10, roadmap): the settings screen's
 * one-tap profiles. A preset fixes the capture long edge, fps and the sender
 * bitrate window (webrtc.md sender targets); every value stays individually
 * tweakable afterwards — a config that no longer matches any preset is
 * labeled "custom" for the desktop's display-only status line
 * ([profileLabelFor]; session-info, webrtc.md).
 *
 * BALANCED is the conservative Phase5 target the app shipped with; the
 * others are the same pipeline with wider targets, not new machinery.
 * Thermal-driven presets arrive in Phase11 (thermal.md) and will reuse this
 * type.
 */
enum class QualityProfile(
    val label: String,
    val longEdgePx: Int,
    val fps: Int,
    val bitrateMinBps: Int,
    val bitrateMaxBps: Int,
) {
    BALANCED(
        label = CastConfig.DEFAULT_PROFILE,
        longEdgePx = 1280,
        fps = 30,
        bitrateMinBps = 4_000_000,
        bitrateMaxBps = 6_000_000,
    ),
    SHARP(
        label = "sharp",
        longEdgePx = 1920,
        fps = 30,
        bitrateMinBps = 8_000_000,
        bitrateMaxBps = 12_000_000,
    ),
    SMOOTH(
        label = "smooth",
        longEdgePx = 1280,
        fps = 60,
        bitrateMinBps = 6_000_000,
        bitrateMaxBps = 10_000_000,
    ),
    LIGHT(
        label = "light",
        longEdgePx = 960,
        fps = 30,
        bitrateMinBps = 2_000_000,
        bitrateMaxBps = 3_000_000,
    ),
}

/**
 * The settings screen's discrete choices and manual-bitrate bounds — named,
 * not magic (AGENTS.md). A manual bitrate is a user-picked ceiling; the
 * window's floor is a fixed fraction of it.
 */
object CastSettingChoices {
    /** Capture long-edge options ("resolution"). */
    val LONG_EDGE_CHOICES = listOf(960, 1280, 1920)

    /** Encoder frame-rate options. */
    val FPS_CHOICES = listOf(30, 60)

    /** Manual bitrate slider bounds (bps). */
    const val MANUAL_BITRATE_MIN_BPS = 2_000_000
    const val MANUAL_BITRATE_MAX_BPS = 20_000_000

    /** Floor of a manual window: [MANUAL_BITRATE_MIN_FRACTION] × the ceiling. */
    const val MANUAL_BITRATE_MIN_FRACTION = 0.5
}

/**
 * The display-only profile label for a config (session-info, webrtc.md):
 * the preset's name when the send targets match it exactly, "custom" the
 * moment the user tweaked anything beyond a preset.
 */
fun profileLabelFor(longEdgePx: Int, fps: Int, bitrateMinBps: Int, bitrateMaxBps: Int): String {
    val match = QualityProfile.entries.firstOrNull {
        it.longEdgePx == longEdgePx && it.fps == fps &&
            it.bitrateMinBps == bitrateMinBps && it.bitrateMaxBps == bitrateMaxBps
    }
    return match?.label ?: PROFILE_CUSTOM
}

/** The label for configs that match no preset ([profileLabelFor]). */
const val PROFILE_CUSTOM = "custom"
