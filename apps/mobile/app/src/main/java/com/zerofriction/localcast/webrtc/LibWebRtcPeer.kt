package com.zerofriction.localcast.webrtc

import com.zerofriction.localcast.signaling.SignalingClient
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** [PeerPort] over a libwebrtc [PeerConnection] — the production half of [PeerNegotiator]. */
class LibWebRtcPeer(private val pc: PeerConnection) : PeerPort<IceCandidate> {

    override fun createOffer(onSuccess: (sdp: String) -> Unit, onFailure: (error: String?) -> Unit) {
        pc.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = onSuccess(description.description)
                override fun onCreateFailure(error: String?) = onFailure(error)
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            },
            MediaConstraints(),
        )
    }

    override fun setLocalOffer(sdp: String, onSuccess: () -> Unit, onFailure: (error: String?) -> Unit) {
        pc.setLocalDescription(setObserver(onSuccess, onFailure), SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    override fun setRemoteAnswer(sdp: String, onSuccess: () -> Unit, onFailure: (error: String?) -> Unit) {
        pc.setRemoteDescription(setObserver(onSuccess, onFailure), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun addRemoteCandidate(candidate: IceCandidate) {
        pc.addIceCandidate(candidate)
    }

    override fun restartIce() = pc.restartIce()

    /** `dispose()`, not `close()`: close alone leaked the native pc and its observer (group D). */
    override fun dispose() = pc.dispose()

    private fun setObserver(onSuccess: () -> Unit, onFailure: (String?) -> Unit) = object : SdpObserver {
        override fun onSetSuccess() = onSuccess()
        override fun onSetFailure(error: String?) = onFailure(error)
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
    }

    companion object {
        /** LAN only (webrtc.md): host candidates, no STUN/TURN; unified plan; continual gathering. */
        fun lanRtcConfiguration(): PeerConnection.RTCConfiguration =
            PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
    }
}

/**
 * The pc observer both sessions share: local candidates trickle to the
 * desktop over signaling (the candidate object verbatim, mDNS hosts
 * included — risk R4), gathering-complete sends the end-of-candidates
 * `null`, and connection-state changes go to the session — **on libwebrtc's
 * signaling thread**; the session hops them itself.
 */
class SignalingPeerObserver(
    private val pcId: String,
    private val signaling: SignalingClient,
    private val onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit,
) : PeerConnection.Observer {
    override fun onIceCandidate(candidate: IceCandidate) {
        signaling.sendIceCandidate(pcId, IceCandidateJson.encode(candidate))
    }

    override fun onIceGatheringChange(event: PeerConnection.IceGatheringState?) {
        if (event == PeerConnection.IceGatheringState.COMPLETE) signaling.sendIceCandidate(pcId, null)
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = onConnectionChange.invoke(newState)

    override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
    override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
    override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
    override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit
    override fun onTrack(transceiver: RtpTransceiver?) = Unit
}
