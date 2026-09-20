package com.zerofriction.localcast.thermal

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase11 thermal monitoring, read-only by construction: the ladder mapping
 * (guarded against the platform's own constants), transition semantics
 * (one callback per real ladder change, none for repeats or unknown values),
 * and the periodic facts that must never masquerade as transitions.
 */
class ThermalMonitorTest {

    private data class Wiring(
        val monitor: ThermalMonitor,
        val states: MutableList<ThermalState>,
        val transitions: MutableList<Pair<ThermalStatus, ThermalStatus>>,
    )

    private fun monitor(): Wiring {
        val states = mutableListOf<ThermalState>()
        val transitions = mutableListOf<Pair<ThermalStatus, ThermalStatus>>()
        return Wiring(
            ThermalMonitor(
                onState = { states.add(it) },
                onTransition = { old, new -> transitions.add(old to new) },
            ),
            states,
            transitions,
        )
    }

    @Test
    fun `the ladder mirrors the platform's constants`() {
        // ThermalStatus deliberately doesn't wrap PowerManager (pure Kotlin);
        // this guard pins the mirror to the API it mirrors.
        assertEquals(PowerManager.THERMAL_STATUS_NONE, ThermalStatus.NONE.platformValue)
        assertEquals(PowerManager.THERMAL_STATUS_LIGHT, ThermalStatus.LIGHT.platformValue)
        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, ThermalStatus.MODERATE.platformValue)
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, ThermalStatus.SEVERE.platformValue)
        assertEquals(PowerManager.THERMAL_STATUS_CRITICAL, ThermalStatus.CRITICAL.platformValue)
        assertEquals(PowerManager.THERMAL_STATUS_EMERGENCY, ThermalStatus.EMERGENCY.platformValue)
        assertEquals(PowerManager.THERMAL_STATUS_SHUTDOWN, ThermalStatus.SHUTDOWN.platformValue)
        assertEquals(ThermalStatus.MODERATE, ThermalStatus.fromPlatformValue(PowerManager.THERMAL_STATUS_MODERATE))
        assertNull(ThermalStatus.fromPlatformValue(99))
    }

    @Test
    fun `starts at NONE and publishes nothing until something happens`() {
        val wiring = monitor()
        assertEquals(ThermalState(ThermalStatus.NONE), wiring.monitor.state.value)
        assertTrue(wiring.states.isEmpty())
        assertTrue(wiring.transitions.isEmpty())
    }

    @Test
    fun `a ladder change publishes one state update and one transition`() {
        val wiring = monitor()
        wiring.monitor.onStatusChanged(PowerManager.THERMAL_STATUS_MODERATE)
        assertEquals(ThermalStatus.MODERATE, wiring.monitor.state.value.status)
        assertEquals(1, wiring.states.size)
        assertEquals(listOf(ThermalStatus.NONE to ThermalStatus.MODERATE), wiring.transitions)

        // The same status again is not a transition — the listener can be chatty.
        wiring.monitor.onStatusChanged(PowerManager.THERMAL_STATUS_MODERATE)
        assertEquals(1, wiring.states.size)
        assertEquals(1, wiring.transitions.size)

        // Recovery is also a transition — the UI line must move back down.
        wiring.monitor.onStatusChanged(PowerManager.THERMAL_STATUS_NONE)
        assertEquals(ThermalStatus.NONE, wiring.monitor.state.value.status)
        assertEquals(
            listOf(ThermalStatus.NONE to ThermalStatus.MODERATE, ThermalStatus.MODERATE to ThermalStatus.NONE),
            wiring.transitions,
        )
    }

    @Test
    fun `an unknown platform value never moves the ladder`() {
        val wiring = monitor()
        wiring.monitor.onStatusChanged(PowerManager.THERMAL_STATUS_LIGHT)
        wiring.monitor.onStatusChanged(99)
        assertEquals(ThermalStatus.LIGHT, wiring.monitor.state.value.status)
        assertEquals(1, wiring.transitions.size)
    }

    @Test
    fun `periodic samples update the facts without ladder transitions`() {
        val wiring = monitor()
        wiring.monitor.onSample(headroom = 2.5f, batteryTempC = 38.2f)
        assertEquals(ThermalState(ThermalStatus.NONE, headroom = 2.5f, batteryTempC = 38.2f), wiring.monitor.state.value)
        assertTrue(wiring.transitions.isEmpty())
        assertEquals(1, wiring.states.size)

        // The API29 shape: no headroom available — the facts carry nulls.
        wiring.monitor.onSample(headroom = null, batteryTempC = 39.0f)
        assertEquals(ThermalState(ThermalStatus.NONE, headroom = null, batteryTempC = 39.0f), wiring.monitor.state.value)
        assertTrue(wiring.transitions.isEmpty())
    }
}
