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
 * 1:1. Phase5 adds the cast trigger on top of a successful pair: the screen
 * collects the foreground service's cast state and hands it the live
 * signaling client. The session still lives and dies with this screen
 * (Phase3 decision); the cast service (Phase6) takes ownership later.
 */
class ScanViewModel(
    private val pairingClient: PairingClient =
        PairingClient(::defaultTransportFactory, ::defaultSignalingScheduler, ::defaultUserAgent),
) : ViewModel() {

    val pairingState: StateFlow<PairingClient.State> = pairingClient.state

    /** The cast service's state — what the cast controls render. */
    val castState: StateFlow<CastState> = CastService.state

    /** Live signaling connection of the current pairing, if any. */
    fun signalingClient(): SignalingClient? = pairingClient.signalingClient()

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
        pairingClient.reset()
    }
}

/** "ZeroFrictionCast/0.1.0 (Android 15; Pixel 8)" — the desktop shows the model. */
private fun defaultUserAgent(): String =
    "ZeroFrictionCast/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
