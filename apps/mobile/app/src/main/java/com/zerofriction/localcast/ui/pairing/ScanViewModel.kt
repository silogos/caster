package com.zerofriction.localcast.ui.pairing

import androidx.lifecycle.ViewModel
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.pairing.defaultSignalingScheduler
import com.zerofriction.localcast.pairing.defaultTransportFactory
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.signaling.SignalingClient
import kotlinx.coroutines.flow.StateFlow

/**
 * Drives the scan screen: QR text arrives (camera scan or the debug manual
 * input) and is handed to the pairing state machine, whose state is rendered
 * 1:1. Phase6: starting a cast hands the live signaling connection to the
 * cast service (which owns it from then on) — the scan screen renders
 * `CastService.state` while a cast runs, and leaving it never ends the cast.
 */
class ScanViewModel(
    private val pairingClient: PairingClient =
        PairingClient(::defaultTransportFactory, ::defaultSignalingScheduler, ::defaultUserAgent),
    /** The cast service's state — injected like HomeViewModel's, so the VM is JVM-testable. */
    castState: StateFlow<CastState> = CastService.state,
) : ViewModel() {

    val pairingState: StateFlow<PairingClient.State> = pairingClient.state

    /** The cast service's state — what the cast controls render. */
    val castState: StateFlow<CastState> = castState

    /**
     * Hand the live signaling connection of the current pairing to the cast
     * service (consent collected). Null if nothing live is owned — the cast
     * can't start and the user rescans.
     */
    fun releaseSignalingClient(): SignalingClient? = pairingClient.releaseSignaling()

    fun onQrScanned(qrText: String) {
        // Camera frames keep flowing while the UI transitions; only a fresh
        // attempt (Idle/Failed/Disconnected) may start a connection.
        when (pairingClient.state.value) {
            PairingClient.State.Idle,
            is PairingClient.State.Failed,
            PairingClient.State.Ended,
            -> pairingClient.startFromQrText(qrText)
            PairingClient.State.Connecting,
            PairingClient.State.Authenticating,
            is PairingClient.State.Connected,
            is PairingClient.State.Reconnecting,
            -> {}
        }
    }

    fun onManualPayloadSubmit(payloadJson: String) {
        pairingClient.startFromQrText(payloadJson)
    }

    fun onDisconnect() {
        pairingClient.disconnect()
    }

    override fun onCleared() {
        // After a handover the pairing machine owns nothing, so this cannot
        // end a running cast — it only drops an un-cast pairing.
        pairingClient.reset()
    }
}

/** "ZeroFrictionCast/0.1.0 (Android 15; Pixel 8)" — the desktop shows the model. */
private fun defaultUserAgent(): String =
    "ZeroFrictionCast/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
