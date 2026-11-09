package com.zerofriction.localcast.debug

import android.util.Log

/**
 * Debug-only: how old each captured frame is when the capture thread gets it
 * — now minus the buffer's timestamp, which the virtual display stamps on
 * the monotonic clock when it composes. A standing queue between the virtual
 * display and the capture thread shows as a steady age of whole frames.
 * Logs a one-line summary per second; reads timestamps only.
 */
object CaptureAgeProbe {
    private val ages = ArrayList<Double>(SUMMARY_FRAMES)
    private var windowStartNs = 0L

    fun onFrame(timestampNs: Long) {
        val now = System.nanoTime()
        ages += (now - timestampNs) / 1_000_000.0
        if (windowStartNs == 0L) windowStartNs = now
        if (now - windowStartNs >= SUMMARY_PERIOD_NS) {
            ages.sort()
            val median = ages[ages.size / 2]
            Log.i(TAG, "capture age n=${ages.size} min=${"%.1f".format(ages.first())} p50=${"%.1f".format(median)} max=${"%.1f".format(ages.last())} ms")
            ages.clear()
            windowStartNs = now
        }
    }

    private const val TAG = "LatencyTest"
    private const val SUMMARY_PERIOD_NS = 1_000_000_000L
    private const val SUMMARY_FRAMES = 128
}
