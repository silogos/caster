package com.zerofriction.localcast.debug

import android.util.Log

/**
 * Debug-only half of the latency probe (docs/development/latency-measurement.md):
 * logs when the test pattern's click comes out of the playback-capture
 * record, splitting the audio path into "phone track → captured" and
 * "captured → Mac". Reads only a peak amplitude — no audio is logged.
 * Called from the ADM's samples callback in debug builds only.
 */
object LatencyClickProbe {
    private var lastLoudMs = 0L

    fun onSamples(pcm16: ByteArray) {
        var peak = 0
        var i = 0
        while (i + 1 < pcm16.size) {
            val s = (pcm16[i].toInt() and 0xff) or (pcm16[i + 1].toInt() shl 8)
            val a = kotlin.math.abs(s.toShort().toInt())
            if (a > peak) peak = a
            i += 2
        }
        if (peak < ONSET_LEVEL) return
        val now = System.currentTimeMillis()
        if (now - lastLoudMs >= QUIET_BEFORE_MS) {
            Log.i(TAG, "click captured_ms=$now")
        }
        lastLoudMs = now
    }

    private const val TAG = "LatencyTest"
    private const val ONSET_LEVEL = 8_000
    private const val QUIET_BEFORE_MS = 300L
}
