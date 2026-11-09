package com.zerofriction.localcast.service

import android.util.Log
import com.zerofriction.localcast.signaling.Envelope
import com.zerofriction.localcast.signaling.SignalingClient
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The cast-scoped desktop controls (proto 2): the virtual camera (ADR-007)
 * and the audio output routing. The desktop owns the truth — its status
 * pushes are the UI's only source; the phone sends requests and renders
 * what comes back. An old desktop (proto 1) answers nothing recoverable and
 * the controls simply stay hidden.
 *
 * Bound to the cast's signaling client for exactly the cast's lifetime:
 * [unbind] removes the listener and resets both states — the socket returns
 * to [ConnectedDesktop] and must not keep touching cast-scoped state.
 */
internal class DesktopControls(
    private val vcState: MutableStateFlow<VirtualCameraUiState>,
    private val audioRouting: MutableStateFlow<AudioRoutingUiState>,
) {
    private var client: SignalingClient? = null
    private var listener: ((SignalingClient.Event) -> Unit)? = null

    /** Subscribe and query; every (re)auth re-queries so the state stays truthful across reconnects. */
    fun bind(signaling: SignalingClient) {
        unbind()
        val newListener: (SignalingClient.Event) -> Unit = { event ->
            when (event) {
                is SignalingClient.Event.Authorized -> query(signaling)
                is SignalingClient.Event.VcStatusEvent -> vcState.value = event.status.toUiState()
                is SignalingClient.Event.AudioRoutingStatusEvent -> audioRouting.value = event.status.toUiState()
                else -> {}
            }
        }
        client = signaling
        listener = newListener
        signaling.addListener(newListener)
        query(signaling)
    }

    fun unbind() {
        val current = client
        val currentListener = listener
        if (current !== null && currentListener !== null) current.removeListener(currentListener)
        client = null
        listener = null
        vcState.value = VirtualCameraUiState()
        audioRouting.value = AudioRoutingUiState()
    }

    /** Optimistic flip: the next vc-status lands the honest state. */
    fun toggleVirtualCamera() {
        val signaling = client ?: return
        if (vcState.value.active) signaling.sendVcStop() else signaling.sendVcStart()
    }

    fun setAudioRouting(mode: String?) {
        val signaling = client ?: return
        if (mode == Envelope.AUDIO_ROUTING_SPEAKERS || mode == Envelope.AUDIO_ROUTING_CABLE || mode == Envelope.AUDIO_ROUTING_BOTH) {
            signaling.sendAudioRoutingSet(mode)
        } else {
            Log.w(TAG, "set-audio-routing with unknown mode — ignored")
        }
    }

    private fun query(signaling: SignalingClient) {
        signaling.sendVcQuery()
        signaling.sendAudioRoutingQuery()
    }

    private companion object {
        const val TAG = "CastService"
    }
}
