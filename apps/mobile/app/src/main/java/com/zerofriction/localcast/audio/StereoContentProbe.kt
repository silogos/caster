package com.zerofriction.localcast.audio

import kotlin.math.abs

/**
 * One question about the captured playback PCM: does it actually carry
 * stereo content (L ≠ R), or is a channel dead? The cast negotiated stereo
 * Opus yet the desktop heard no separation (found live 2026-11-03), and the
 * playback-capture tap turns out to come up
 * mono in three flavors (L-only, R-only, L=R) before sometimes settling
 * into true stereo — this probe is the evidence stream that feeds
 * [MonoTapRecovery]. Aggregate numbers only — never the samples themselves
 * (logging rules).
 *
 * Owned per capture source; not thread-safe beyond the ADM's single
 * recording thread that feeds it.
 */
internal class StereoContentProbe(
    /**
     * Sample *pairs* per report — one interleaved frame counts as one pair.
     * 48 000 pairs = one report per second at 48 kHz.
     */
    private val framesPerReport: Int = FRAMES_PER_REPORT,
) {
    private var frames = 0
    private var sumAbsLeft = 0L
    private var sumAbsRight = 0L
    private var sumAbsDiff = 0L

    /** The window's channel aggregates, once the window closes. */
    data class Report(val meanAbsLeft: Long, val meanAbsRight: Long, val meanAbsDiff: Long) {
        val amplitude: Long get() = maxOf(meanAbsLeft, meanAbsRight)

        /**
         * Loud, yet one channel under [MonoTapRecovery.DEAD_CHANNEL_RATIO] of
         * the other — a dead tap channel (hard-panned content leaks; a dead
         * channel is exactly zero). The shared predicate of the self-heal and
         * the flavor sweep.
         */
        fun oneChannelDead(
            minAmplitude: Long = MonoTapRecovery.MIN_AMPLITUDE,
            deadRatio: Long = MonoTapRecovery.DEAD_CHANNEL_RATIO,
        ): Boolean = amplitude >= minAmplitude && minOf(meanAbsLeft, meanAbsRight) * deadRatio < amplitude

        /**
         * Loud, both channels carrying the same signal (L−R under
         * 1/[MonoTapRecovery.DUAL_MONO_RATIO] of the amplitude) — the L=R
         * fold flavor, or genuinely mono content (indistinguishable here).
         */
        fun isDualMono(
            minAmplitude: Long = MonoTapRecovery.MIN_AMPLITUDE,
            ratio: Long = MonoTapRecovery.DUAL_MONO_RATIO,
        ): Boolean = amplitude >= minAmplitude && meanAbsDiff * ratio < amplitude

        /** Human-readable line for the logs. */
        fun summarize(): String {
            val diffPct = if (amplitude == 0L) 0L else meanAbsDiff * 100 / amplitude
            return "mean|L|=$meanAbsLeft mean|R|=$meanAbsRight mean|L-R|=$meanAbsDiff ($diffPct% of amplitude)"
        }
    }

    /**
     * Feeds one interleaved PCM16 little-endian stereo frame (the substituted
     * capture record's format). Returns the window's aggregate when it closes,
     * else null.
     */
    fun onSamples(samples: ByteArray): Report? {
        val samplePairs = samples.size / 4
        if (samplePairs == 0) return null
        var i = 0
        while (i + 3 < samples.size) {
            val left = (samples[i + 1].toInt() shl 8) or (samples[i].toInt() and 0xFF)
            val right = (samples[i + 3].toInt() shl 8) or (samples[i + 2].toInt() and 0xFF)
            sumAbsLeft += abs(left)
            sumAbsRight += abs(right)
            sumAbsDiff += abs(left - right)
            i += 4
        }
        frames += samplePairs
        return closeIfFull()
    }

    /**
     * Feeds one interleaved PCM float stereo frame (±1.0 range, scaled into
     * the PCM16 domain the thresholds speak). The playback-capture docs'
     * own example records in float — a flavor the one-channel tap may remix
     * differently, so the sweep measures it too.
     */
    fun onSamples(samples: FloatArray): Report? {
        val samplePairs = samples.size / 2
        if (samplePairs == 0) return null
        var i = 0
        while (i + 1 < samples.size) {
            val left = (samples[i] * 32767f).toLong()
            val right = (samples[i + 1] * 32767f).toLong()
            sumAbsLeft += abs(left)
            sumAbsRight += abs(right)
            sumAbsDiff += abs(left - right)
            i += 2
        }
        frames += samplePairs
        return closeIfFull()
    }

    private fun closeIfFull(): Report? {
        if (frames < framesPerReport) return null
        val report = Report(sumAbsLeft / frames, sumAbsRight / frames, sumAbsDiff / frames)
        frames = 0
        sumAbsLeft = 0
        sumAbsRight = 0
        sumAbsDiff = 0
        return report
    }

    companion object {
        private const val FRAMES_PER_REPORT = 48_000
    }
}
