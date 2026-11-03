package com.zerofriction.localcast.audio

import kotlin.math.abs

/**
 * One question about the captured playback PCM: does it actually carry
 * stereo content (L ≠ R), or mono-duplicated channels (L = R)? The cast
 * negotiated stereo Opus yet the desktop heard no separation (found live
 * 2026-11-03) — this probe splits "the platform delivers mono content"
 * (Android's mix is already folded, e.g. a mono sink) from "the pipeline
 * collapses stereo downstream of the capture". Aggregate numbers only —
 * never the samples themselves (logging rules).
 *
 * Owned per capture source; not thread-safe beyond the ADM's single
 * recording thread that feeds it.
 */
internal class StereoContentProbe(
    /** 10 ms frames per report — 500 = one report per ~5 s of audio. */
    private val framesPerReport: Int = FRAMES_PER_REPORT,
) {
    private var frames = 0
    private var sumAbsLeft = 0L
    private var sumAbsRight = 0L
    private var sumAbsDiff = 0L

    /**
     * Feeds one interleaved PCM16 little-endian stereo frame (the substituted
     * capture record's format). Returns the window's aggregate when it closes,
     * else null.
     */
    fun onSamples(samples: ByteArray): String? {
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
        if (frames < framesPerReport) return null
        val meanLeft = sumAbsLeft / frames
        val meanRight = sumAbsRight / frames
        val meanDiff = sumAbsDiff / frames
        val amplitude = maxOf(meanLeft, meanRight)
        val diffPct = if (amplitude == 0L) 0L else meanDiff * 100 / amplitude
        frames = 0
        sumAbsLeft = 0
        sumAbsRight = 0
        sumAbsDiff = 0
        return "mean|L|=$meanLeft mean|R|=$meanRight mean|L-R|=$meanDiff ($diffPct% of amplitude)"
    }

    companion object {
        private const val FRAMES_PER_REPORT = 500
    }
}
