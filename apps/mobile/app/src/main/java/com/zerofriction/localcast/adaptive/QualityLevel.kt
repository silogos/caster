package com.zerofriction.localcast.adaptive

/**
 * One rung of the auto-quality ladder (Phase12, thermal.md): the concrete
 * send targets a step lands on. Deliberately the same shape as a profile
 * preset — the ladder is the preset list, plus (as its top rung) whatever
 * the user's own settings are, so a tweaked ("custom") config adapts too.
 */
data class QualityLevel(
    val longEdgePx: Int,
    val fps: Int,
    val bitrateMinBps: Int,
    val bitrateMaxBps: Int,
) {
    /**
     * "Not heavier than": every load dimension at or below [other]'s. The
     * bitrate *max* is the load cap that matters (a floor only protects
     * against starving the stream), so the min is not compared.
     */
    fun isNoHeavierThan(other: QualityLevel): Boolean =
        longEdgePx <= other.longEdgePx && fps <= other.fps && bitrateMaxBps <= other.bitrateMaxBps

    companion object {
        /** The shipped preset ladder (config/QualityProfile.kt, thermal.md). */
        val PRESETS: List<QualityLevel> =
            com.zerofriction.localcast.config.QualityProfile.entries.map {
                QualityLevel(it.longEdgePx, it.fps, it.bitrateMinBps, it.bitrateMaxBps)
            }
    }
}
