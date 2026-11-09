package com.zerofriction.localcast.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.signaling.SignalingClient
import com.zerofriction.localcast.webrtc.MicCastSession
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The cast's mic half (Phase8, audio.md): the `mic` pc built on demand
 * (factory B, independent of the `media` pc — no renegotiation of the cast),
 * the live device pick, and the plug/unplug watcher. Runs on the service's
 * main thread; publishes [micState] and [micRouting].
 *
 * [onMicOnChanged] tells the service when the mic session comes and goes —
 * it re-declares the FGS type set (Android11+ silences a backgrounded mic
 * unless the FGS carries the `microphone` type) and re-sends the
 * display-only `session-info`.
 */
internal class MicController(
    private val context: Context,
    private val signaling: SignalingClient,
    /** The selected kind — the live copy of the cast config's mic device. */
    private var selectedDevice: MicDeviceSource,
    private val mainHandler: Handler,
    private val micState: MutableStateFlow<MicState>,
    private val micRouting: MutableStateFlow<MicRoutingUiState?>,
    private val onMicOnChanged: (Boolean) -> Unit,
) {
    /** Non-null while the mic is on for this cast. */
    private var micSession: MicCastSession? = null

    /**
     * Plug/unplug watcher for a RECORDING mic session (the realtime picker's
     * honest half): when the routed kind's inputs disappear mid-cast, the
     * record is rerouted to the phone mic and the UI gets the fallback fact.
     * Registered while a mic session exists, unregistered on its teardown.
     */
    private var deviceWatcher: AudioDeviceCallback? = null

    /** The pending device-settle action — cancelled per new event, at teardown. */
    private var pendingSettle: Runnable? = null

    val isOn: Boolean get() = micSession !== null

    /** Cast start with the mic in the config (audio.md: denial is never cast-fatal, the UI states the fact). */
    fun startForCast() {
        if (hasRecordAudioPermission()) startSession() else micState.value = MicState.NeedsPermission
    }

    /** The mic's live on/off toggle: on = build the `mic` pc; off = tear it down. */
    fun toggle() {
        val current = micSession
        if (current !== null) {
            current.stop()
            micSession = null
            unregisterDeviceWatcher()
            micRouting.value = null
            // The dying session's onState(Off) is identity-guarded away
            // (startSession) — the off fact is the controller's to state.
            micState.value = MicState.Off
            onMicOnChanged(false)
            return
        }
        if (!hasRecordAudioPermission()) {
            micState.value = MicState.NeedsPermission
            Log.w(TAG, "mic toggle without RECORD_AUDIO — mic stays off")
            return
        }
        startSession()
    }

    /**
     * A mid-cast mic-device pick (Home picker). The running cast follows the
     * pick — the store write stays the caller's ("settings live in the
     * store, the service reads fresh"). With the mic on: same-rate switch →
     * the live record reroutes in place; cross-rate switch → the session
     * rebuilds (an `AudioRecord`'s rate is fixed at creation); no live record
     * yet → the pick is kept and applies at the next mic start (a rebuild
     * here would tear down a seconds-old session — the live-found
     * toggle-churn race). With the mic off the pick waits for the next mic-on.
     */
    fun setDevice(device: MicDeviceSource) {
        selectedDevice = device
        val mic = micSession ?: return
        when (mic.setMicDevice(device)) {
            MicCastSession.MicDeviceSwitch.APPLIED_LIVE -> Unit
            MicCastSession.MicDeviceSwitch.NEEDS_REBUILD -> rebuildSession()
            MicCastSession.MicDeviceSwitch.DEFERRED ->
                Log.i(TAG, "mic pick stored — no live record yet, it applies at the next mic start")
        }
    }

    /** Cast teardown: the session, the watcher and the routing readout (the service resets [micState]). */
    fun stop() {
        micSession?.stop()
        micSession = null
        unregisterDeviceWatcher()
        micRouting.value = null
    }

    /**
     * [degradedFrom] ≠ null builds the session on the built-in mic whatever
     * the selection says — the capture-death degrade (a dead external input
     * that never sent an unplug event; the fallback fact reaches the UI via
     * [MicRoutingUiState.fallbackFrom]).
     *
     * Every callback is identity-guarded (`micSession === newMic`): teardown
     * is asynchronous (the dying session's thread publishes its terminal
     * state whenever its stop lands), and an unguarded late `Off` was shown
     * live to land *after* the replacement's `Active` — the UI then showed
     * the mic off while it recorded, and the next toggle inverted.
     */
    private fun startSession(degradedFrom: MicDeviceSource? = null) {
        lateinit var newMic: MicCastSession
        newMic = MicCastSession(
            context = context,
            signaling = signaling,
            micDevice = degradedFrom ?: selectedDevice,
            onState = { state -> if (micSession === newMic) micState.value = state },
            onMicRouted = { effective, fallbackFrom ->
                if (micSession === newMic) {
                    micRouting.value = MicRoutingUiState(effective, fallbackFrom ?: degradedFrom)
                }
            },
            onCaptureDied = { kind ->
                if (micSession === newMic) {
                    mainHandler.post { if (micSession === newMic) rebuildSession(fallbackFrom = kind) }
                }
            },
        )
        micSession = newMic
        registerDeviceWatcher()
        newMic.start()
        // Before the ADM's first mic read: the mic bit must be on the FGS
        // before the app can be backgrounded, or Android11+ mutes the mic.
        onMicOnChanged(true)
        Log.i(TAG, "mic session started${if (degradedFrom !== null) " (degraded from ${degradedFrom.label})" else ""}")
    }

    /**
     * Tear the mic session down and build it fresh on the current selection —
     * the only way to change the capture rate mid-cast (round 4).
     * [fallbackFrom] routes the rebuild onto the built-in mic (the
     * capture-death degrade). Brief and mic-local: a ~1–2 s gap, the cast
     * itself is untouched, and the FGS keeps its microphone type throughout
     * ([startSession] re-asserts it). The dying session's late publishes are
     * identity-guarded away.
     */
    private fun rebuildSession(fallbackFrom: MicDeviceSource? = null) {
        val current = micSession ?: return
        current.stop()
        micSession = null
        unregisterDeviceWatcher()
        micRouting.value = null
        startSession(degradedFrom = fallbackFrom)
    }

    /**
     * Registers [deviceWatcher] (idempotent) while a mic session records.
     * Device add/remove events are **settled**, not obeyed instantly: a
     * flapping external input (live finding: the USB headset bounced every
     * ~5 s) would otherwise rebuild the mic session per bounce. After the
     * quiet period the mic simply follows the *selected* kind again —
     * resolving to the phone mic when the selection is gone (the
     * plain-words fallback) and back onto the selection when it returns.
     */
    private fun registerDeviceWatcher() {
        if (deviceWatcher !== null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val watcher = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                onDevicesChanged(addedDevices)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                onDevicesChanged(removedDevices)
            }
        }
        deviceWatcher = watcher
        am.registerAudioDeviceCallback(watcher, null)
    }

    /** Input-side device churn → one settle timer (output-only churn is irrelevant). */
    private fun onDevicesChanged(changed: Array<out AudioDeviceInfo>) {
        if (changed.all { it.isSink }) return
        if (selectedDevice == MicDeviceSource.BUILTIN && micRouting.value?.effective == MicDeviceSource.BUILTIN) return
        scheduleSettle()
    }

    /** Rescheduled per event — a flap keeps deferring it; a real change lets it fire. */
    private fun scheduleSettle() {
        pendingSettle?.let { mainHandler.removeCallbacks(it) }
        val settle = Runnable {
            pendingSettle = null
            val mic = micSession ?: return@Runnable
            when (mic.setMicDevice(selectedDevice)) {
                MicCastSession.MicDeviceSwitch.APPLIED_LIVE -> Unit
                MicCastSession.MicDeviceSwitch.NEEDS_REBUILD -> rebuildSession()
                MicCastSession.MicDeviceSwitch.DEFERRED -> Unit
            }
        }
        pendingSettle = settle
        mainHandler.postDelayed(settle, DEVICE_SETTLE_MS)
    }

    private fun unregisterDeviceWatcher() {
        pendingSettle?.let { mainHandler.removeCallbacks(it) }
        pendingSettle = null
        val watcher = deviceWatcher ?: return
        deviceWatcher = null
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.unregisterAudioDeviceCallback(watcher)
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "CastService"

        /**
         * How long the device set must stay quiet before the mic follows the
         * selection again (a flapping input keeps deferring the timer — one
         * rebuild per storm, not one per bounce; live finding 2026-11-02).
         */
        const val DEVICE_SETTLE_MS = 2_000L
    }
}
