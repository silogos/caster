package com.zerofriction.localcast.hotspot

import android.util.Log
import com.zerofriction.localcast.pairing.PairingError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hotspot pairing mode (issue #96, ADR-011): when there is no shared Wi-Fi,
 * the PHONE hosts a local-only hotspot and the desktop joins it — the same
 * LAN-only posture with no infrastructure (no relay, no STUN/TURN, no
 * internet; a local-only hotspot shares no mobile data). The guided-manual
 * flow: start here → SSID + password on the phone screen → the human joins
 * on the desktop → scan the desktop's fresh QR (its `h` already includes the
 * hotspot-subnet address) → pair and cast as usual. Zero protocol change.
 *
 * Pure lifecycle machine (no Android types — the platform wrapper
 * [LocalOnlyHotspotSource] drives it; JVM-tested here like PairingClient).
 * Start/stop are explicit and user-owned: the cast runs OVER the hotspot, so
 * nothing auto-stops it mid-session; the OS revoking it mid-cast degrades
 * honestly through the existing ADR-009 paths (Failed → one-tap return).
 */
class HotspotMachine {

    sealed interface State {
        /** Not hosting. */
        data object Idle : State

        /** The runtime permission is missing — the UI requests it (issue #96's honest permission path). */
        data object NeedPermission : State

        /** Reservation requested; waiting for the OS callback. */
        data object Starting : State

        /** Live: SSID + password are on screen; the desktop can join. */
        data class Active(val ssid: String, val password: String) : State

        /** Terminal honest failure — retry is one tap (except OsRevoked mid-use). */
        data class Failed(val reason: Reason) : State
    }

    /** Every failure keeps it simple per AGENTS.md: honest plain words, detail in logs. */
    enum class Reason {
        /** The user declined the permission — a decision, not an error; retry asks again. */
        PERMISSION_DECLINED,

        /** The device refuses local-only hotspots (OEM/carrier policy, unsupported hardware). */
        NOT_SUPPORTED,

        /** No channel available — the radio is busy (e.g. an existing hotspot/tether). */
        NO_CHANNEL,

        /** Another mode blocks it (tethering off by policy, an active incompatible mode). */
        INCOMPATIBLE_MODE,

        /** The OS tore the hotspot down mid-use (call, thermal, policy — OEM variance, R11). */
        OS_REVOKED,

        /** Anything else — unknown failure or a SecurityException from an OEM permission quirk. */
        UNKNOWN,
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Entry point — [hasPermission] is the wrapper's runtime-permission check result. */
    fun startRequested(hasPermission: Boolean) {
        when (_state.value) {
            State.Idle, is State.Failed, State.NeedPermission -> {
                if (hasPermission) {
                    _state.value = State.Starting
                } else {
                    _state.value = State.NeedPermission
                }
            }
            // Double-tap while busy is a no-op — one reservation at a time.
            State.Starting, is State.Active -> Unit
        }
    }

    /** The UI's permission request resolved — retrying with the grant goes straight to Starting. */
    fun permissionResult(granted: Boolean) {
        if (_state.value != State.NeedPermission) return
        _state.value = if (granted) State.Starting else State.Failed(Reason.PERMISSION_DECLINED)
    }

    /** The OS granted the reservation — credentials are only readable while we hold it. */
    fun started(ssid: String, password: String) {
        if (_state.value != State.Starting) return
        _state.value = State.Active(ssid, password)
        Log.i(TAG, "hotspot active (ssid=$ssid)")
    }

    fun startFailed(reason: Reason) {
        if (_state.value != State.Starting) return
        _state.value = State.Failed(reason)
        Log.w(TAG, "hotspot start failed: $reason")
    }

    /** The OS revoked a live hotspot (documented behavior of LocalOnlyHotspot). */
    fun osRevoked() {
        if (_state.value !is State.Active) return
        _state.value = State.Failed(Reason.OS_REVOKED)
        Log.w(TAG, "hotspot revoked by the OS mid-use")
    }

    /** Explicit stop — the only normal exit from Active (the wrapper closes the reservation). */
    fun stopRequested() {
        if (_state.value is State.Active || _state.value == State.Starting) {
            Log.i(TAG, "hotspot stopped")
        }
        _state.value = State.Idle
    }

    private companion object {
        const val TAG = "HotspotMachine"
    }
}

/**
 * The failure-hint gate (issue #96's follow-up): the "Start a hotspot here"
 * escape appears exactly for the unreachable-desktop failure — the one error
 * that means "no shared network", which a hotspot solves. Every other
 * failure (bad QR, busy, expired) has its own existing escape and must not
 * grow a hotspot nudge.
 */
fun hotspotHintFor(error: PairingError): Boolean = error == PairingError.CONNECT_UNREACHABLE
