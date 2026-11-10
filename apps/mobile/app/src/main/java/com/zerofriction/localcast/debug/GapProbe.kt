package com.zerofriction.localcast.debug

import android.util.Log

/**
 * Debug-only: the longest pause between consecutive events on one thread,
 * one summary line a second. It sits at two points of the game-audio path —
 * where a captured block reaches the app, and where a chunk is handed to the
 * DataChannel — so a chunk that reaches the desktop late can be placed:
 * before the app (Android's capture), inside it (the send thread), or after
 * it (network, desktop). The desktop logs the same measure on arrival.
 * Reads the clock only; one instance per thread.
 */
class GapProbe(
    private val label: String,
    private val nowNs: () -> Long = System::nanoTime,
    private val report: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var lastNs = 0L
    private var windowStartNs = 0L
    private var events = 0
    private var longestNs = 0L
    private var long = 0

    fun onEvent() {
        val now = nowNs()
        if (events == 0 && lastNs == 0L) windowStartNs = now
        if (lastNs != 0L) {
            val gap = now - lastNs
            if (gap > longestNs) longestNs = gap
            if (gap >= LONG_GAP_NS) long++
        }
        lastNs = now
        events++
        if (now - windowStartNs >= SUMMARY_PERIOD_NS) {
            report("$label gap n=$events max=${"%.1f".format(longestNs / 1_000_000.0)} ms, long=$long")
            windowStartNs = now
            events = 0
            longestNs = 0L
            long = 0
        }
    }

    companion object {
        private const val TAG = "LatencyTest"
        private const val SUMMARY_PERIOD_NS = 1_000_000_000L

        /** More than one capture block (1 024 frames, 21 ms) can explain: late. */
        const val LONG_GAP_NS = 30_000_000L
    }
}
