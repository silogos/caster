package com.zerofriction.localcast.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The Android side of thermal monitoring (thermal.md): registers the
 * platform ladder listener (API29, the minSdk floor) and samples the
 * forward-looking headroom (API30+) plus battery temperature on a fixed
 * cadence, feeding the pure [ThermalMonitor]. Phase11 scope: read-only —
 * this class observes and logs, it never touches cast parameters.
 *
 * Owned by CastService for exactly the lifetime of a cast: start() at cast
 * start, stop() on every teardown path (the state it feeds resets with the
 * cast).
 */
class ThermalSource(
    private val context: Context,
    private val monitor: ThermalMonitor,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val sampleIntervalMs: Long = SAMPLE_INTERVAL_MS,
) {
    private val powerManager: PowerManager
        get() = context.getSystemService(PowerManager::class.java)

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        monitor.onStatusChanged(status)
    }

    private val sampleRunnable = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, sampleIntervalMs)
        }
    }

    fun start() {
        // The executor overload is API30+; the deprecated plain overload is
        // the API29 path (callbacks land on the main thread either way).
        if (sdkInt >= Build.VERSION_CODES.R) {
            powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
        } else {
            @Suppress("DEPRECATION")
            powerManager.addThermalStatusListener(listener)
        }
        // Seed the ladder immediately — a cast started on an already-warm
        // phone reports that fact at once (and it counts as a transition
        // from the cast's NONE baseline, so it is logged).
        monitor.onStatusChanged(powerManager.currentThermalStatus)
        sample()
        handler.postDelayed(sampleRunnable, sampleIntervalMs)
        Log.i(TAG, "thermal monitoring started (sample every ${sampleIntervalMs / 1_000}s)")
    }

    fun stop() {
        powerManager.removeThermalStatusListener(listener)
        handler.removeCallbacks(sampleRunnable)
        Log.i(TAG, "thermal monitoring stopped — status was ${monitor.state.value.status}")
    }

    /** Headroom where the platform has it (API30+), battery temperature everywhere. */
    private fun sample() {
        val headroom = if (sdkInt >= Build.VERSION_CODES.R) {
            powerManager.getThermalHeadroom(HEADROOM_HORIZON_SECONDS)
        } else {
            null
        }
        val batteryTempC = batteryTemperature()
        monitor.onSample(headroom, batteryTempC)
        Log.d(
            TAG,
            "sample: status ${monitor.state.value.status}" +
                ", headroom ${headroom ?: "n/a"}" +
                ", battery ${batteryTempC?.let { "$it°C" } ?: "n/a"}",
        )
    }

    /** The sticky ACTION_BATTERY_CHANGED broadcast carries the temperature in tenths of °C. */
    private fun batteryTemperature(): Float? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val tenthsC = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return if (tenthsC > 0) tenthsC / 10f else null
    }

    companion object {
        private const val TAG = "ThermalSource"

        /** Phase15's measurement protocol watches this cadence in logcat. */
        const val SAMPLE_INTERVAL_MS = 10_000L

        /** thermal.md: forward-looking forecast horizon for getThermalHeadroom. */
        const val HEADROOM_HORIZON_SECONDS = 30
    }
}
