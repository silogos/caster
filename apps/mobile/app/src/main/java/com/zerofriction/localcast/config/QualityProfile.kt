package com.zerofriction.localcast.config

/**
 * The cast's named profiles (Phase11, [thermal.md](../../../../docs/architecture/thermal.md)):
 * one-tap presets that fix the capture long edge, fps and the sender bitrate
 * window (webrtc.md sender targets) — the thermal strategy's primary
 * mitigation, applied at cast start from this config module.
 *
 * Phase11 reconciled thermal.md's hypotheses (Cool540p30 · Balanced 720p30 ·
 * Performance 720p60) with the Phase10 presets the app shipped with:
 *540p/720p are the *short* edges of frames whose capture long edge is
 * 960/1280 px here, and the tuned windows are the Phase10 values, relabeled
 * (`light`→`cool`, `smooth`→`performance`; [CastSettingsCodec] migrates
 * stored settings). SHARP stays as the fourth, fidelity-over-thermals
 * preset — the user stays in control (thermal.md principle 2). Exact values
 * are re-tuned from Phase15 measurements; until then this table is the
 * shipped hypothesis.
 *
 * Every value stays individually tweakable after a preset — a config that
 * no longer matches any preset is labeled "custom" for the desktop's
 * display-only status line ([profileLabelFor]; session-info, webrtc.md).
 */
enum class QualityProfile(
    val label: String,
    val longEdgePx: Int,
    val fps: Int,
    val bitrateMinBps: Int,
    val bitrateMaxBps: Int,
) {
    /** thermal.md Cool: long sessions, warm devices, battery priority — the least added heat. */
    COOL(
        label = "cool",
        longEdgePx = 960,
        fps = 30,
        bitrateMinBps = 2_000_000,
        bitrateMaxBps = 3_000_000,
    ),
    /** thermal.md Balanced (default): the good-enough window for most games. */
    BALANCED(
        label = CastConfig.DEFAULT_PROFILE,
        longEdgePx = 1280,
        fps = 30,
        bitrateMinBps = 4_000_000,
        bitrateMaxBps = 6_000_000,
    ),
    /** thermal.md Performance: fast-motion games; measurably more heat. */
    PERFORMANCE(
        label = "performance",
        longEdgePx = 1280,
        fps = 60,
        bitrateMinBps = 6_000_000,
        bitrateMaxBps = 10_000_000,
    ),
    /** Fidelity over thermals: most detail, most heat — the user's explicit choice. */
    SHARP(
        label = "sharp",
        longEdgePx = 1920,
        fps = 30,
        bitrateMinBps = 8_000_000,
        bitrateMaxBps = 12_000_000,
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
fun profileLabelFor(longEdgePx: Int, fps: Int, bitrateMinBps: Int, bitrateMaxBps: Int): String =
    matchingProfile(longEdgePx, fps, bitrateMinBps, bitrateMaxBps)?.label ?: PROFILE_CUSTOM

/** The label for configs that match no preset ([profileLabelFor]). */
const val PROFILE_CUSTOM = "custom"

/**
 * The preset whose send targets match exactly, or null for a tweaked config.
 * The inverse of the preset→targets mapping — used for the display label and
 * for naming an auto-quality level in user-facing text (Phase12).
 */
fun matchingProfile(longEdgePx: Int, fps: Int, bitrateMinBps: Int, bitrateMaxBps: Int): QualityProfile? =
    QualityProfile.entries.firstOrNull {
        it.longEdgePx == longEdgePx && it.fps == fps &&
            it.bitrateMinBps == bitrateMinBps && it.bitrateMaxBps == bitrateMaxBps
    }
