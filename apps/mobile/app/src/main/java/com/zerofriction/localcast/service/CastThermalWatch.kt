package com.zerofriction.localcast.service

import android.content.Context
import android.util.Log
import com.zerofriction.localcast.thermal.ThermalMonitor
import com.zerofriction.localcast.thermal.ThermalSource
import com.zerofriction.localcast.thermal.ThermalState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Thermal monitoring for the running cast (Phase11, thermal.md): the ladder
 * is published to [state] for the home screen and logged (headroom + battery
 * temperature at [ThermalSource.SAMPLE_INTERVAL_MS]); auto quality reads it
 * each stats tick. Runs for exactly the cast's lifetime; [stop] resets the
 * published state to NONE.
 */
internal class CastThermalWatch(
    private val context: Context,
    private val state: MutableStateFlow<ThermalState>,
) {
    private var source: ThermalSource? = null

    fun start() {
        if (source !== null) return
        val monitor = ThermalMonitor(
            onState = { newState -> state.value = newState },
            onTransition = { old, new ->
                val snapshot = state.value
                Log.i(
                    TAG,
                    "thermal: $old → $new" +
                        ", headroom ${snapshot.headroom ?: "n/a"}" +
                        ", battery ${snapshot.batteryTempC?.let { "$it°C" } ?: "n/a"}",
                )
            },
        )
        source = ThermalSource(context, monitor).also { it.start() }
    }

    fun stop() {
        source?.stop()
        source = null
        state.value = ThermalState()
    }

    private companion object {
        const val TAG = "CastService"
    }
}
