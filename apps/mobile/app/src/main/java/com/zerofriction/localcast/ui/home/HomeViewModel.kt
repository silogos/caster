package com.zerofriction.localcast.ui.home

import androidx.lifecycle.ViewModel
import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.pairing.SavedDesktop
import com.zerofriction.localcast.pairing.SavedDesktops
import com.zerofriction.localcast.pairing.applyGrant
import com.zerofriction.localcast.pairing.defaultSignalingScheduler
import com.zerofriction.localcast.pairing.defaultTransportFactory
import com.zerofriction.localcast.ui.pairing.defaultUserAgent
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.signaling.SignalingClient
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the home screen state. Phase6: the cast service owns the session, so
 * the home screen renders [CastService.state] 1:1 — the home screen shows a
 * live cast ("Casting to …" + stop) even though pairing happened on the scan
 * screen, because the session no longer dies with any ViewModel.
 *
 * Persistent session (ADR-006): the hub also drives the QR-less reconnect —
 * [connectSaved] runs the resume handshake against the saved desktop, and
 * the pairing machine's Connected state is adopted into [ConnectedDesktop]
 * by the screen exactly like a scan pairing. The saved store and machine are
 * injected so this ViewModel stays JVM-testable.
 */
class HomeViewModel(
    castStateFlow: StateFlow<CastState> = CastService.state,
    private val savedDesktops: SavedDesktops? = null,
    private val pairingClient: PairingClient = PairingClient(
        ::defaultTransportFactory,
        ::defaultSignalingScheduler,
        ::defaultUserAgent,
    ),
) : ViewModel() {

    val castState: StateFlow<CastState> = castStateFlow

    /** The resume machine's state — Connecting/Connected/Failed while reconnecting. */
    val pairingState: StateFlow<PairingClient.State> = pairingClient.state

    /** The saved desktop entry, or null when nothing usable is stored. */
    fun savedDesktop(): SavedDesktop? = savedDesktops?.load()

    /**
     * One-tap reconnect (ADR-006): resume the saved session without a QR.
     * A dead seal (vault cannot open) is handled by [SavedDesktops.load]
     * degrading to null — the caller's None-state navigation leads to a scan.
     */
    fun connectSaved() {
        val saved = savedDesktops?.load() ?: return
        val trustKey = savedDesktops.trustKeyOf(saved) ?: return
        pairingClient.startSaved(saved, trustKey)
    }

    /**
     * Terminate without a live connection (the desktop is retired/unreachable):
     * drop the saved session — the entry, not just the socket.
     */
    fun terminateSaved() {
        savedDesktops?.clear()
        pairingClient.reset()
    }

    /** Leave for the scanner — drop the machine, keep the saved entry. */
    fun prepareForScan() {
        pairingClient.reset()
    }

    /** Hand the reconnected signaling socket to the hub holder (the screen drives adoption). */
    fun releaseSignalingClient(): SignalingClient? = pairingClient.releaseSignaling()

    override fun onCleared() {
        // After an adoption the machine owns nothing — this cannot end a
        // running connection; it only drops an unfinished reconnect attempt.
        pairingClient.reset()
    }
}
