package com.zerofriction.localcast.thermal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The platform's thermal ladder (thermal.md monitoring inputs) as the app's
 * own vocabulary. `PowerManager.THERMAL_STATUS_*` are mirrored, not wrapped,
 * so the mapping and the transitions stay plain-JVM unit-testable
 * (GameAudioMonitor's discipline: no Android types in the state machine) —
 * a test guards the mirror against `PowerManager` itself.
 */
enum class ThermalStatus(val platformValue: Int) {
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
    EMERGENCY(5),
    SHUTDOWN(6);

    companion object {
        /** Platform int → status; null on any value this build doesn't know. */
        fun fromPlatformValue(value: Int): ThermalStatus? =
            entries.firstOrNull { it.platformValue == value }
    }
}

/**
 * One read-only snapshot of the device's thermal situation while a cast
 * runs. This is diagnostics data (Phase11: read-only — nothing may act on
 * it); the UI shows the [status] ladder in plain words, the log carries the
 * details.
 */
data class ThermalState(
    val status: ThermalStatus = ThermalStatus.NONE,
    /**
     * `PowerManager.getThermalHeadroom` forecast (API30+; null below or
     * before the first sample). Larger = more headroom; ≤0 means throttling
     * is imminent.
     */
    val headroom: Float? = null,
    /** Battery temperature in °C (BatteryManager, tenths → float); null before the first sample. */
    val batteryTempC: Float? = null,
)

/**
 * Pure state machine behind the thermal read-out (Phase11): consumes the
 * platform ladder events and the periodic facts ([ThermalSource] is the
 * Android side) and publishes [ThermalState] for the UI plus a transition
 * callback for the logs. Read-only by construction — no path from here
 * reaches cast parameters (auto-degradation is Phase12, not now).
 */
class ThermalMonitor(
    /** Every state update (ladder transitions and periodic samples alike). */
    private val onState: (ThermalState) -> Unit = {},
    /** Ladder changes only — one INFO log line each, Phase15's measurement trail. */
    private val onTransition: (old: ThermalStatus, new: ThermalStatus) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow(ThermalState())
    val state: StateFlow<ThermalState> = _state.asStateFlow()

    /**
     * A platform ladder event (the listener callback, or the current status
     * seeded at cast start). Unknown platform values are ignored — the
     * ladder only ever moves on values this build understands, never on a
     * guess.
     */
    fun onStatusChanged(platformValue: Int) {
        val status = ThermalStatus.fromPlatformValue(platformValue) ?: return
        val current = _state.value
        if (status == current.status) return
        _state.value = current.copy(status = status)
        onState(_state.value)
        onTransition(current.status, status)
    }

    /** Periodic facts (headroom, battery temperature) — never a ladder transition. */
    fun onSample(headroom: Float?, batteryTempC: Float?) {
        _state.value = _state.value.copy(headroom = headroom, batteryTempC = batteryTempC)
        onState(_state.value)
    }
}
