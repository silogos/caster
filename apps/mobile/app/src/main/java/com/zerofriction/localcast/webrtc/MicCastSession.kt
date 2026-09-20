package com.zerofriction.localcast.webrtc

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.signaling.SignalingClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The mobile's mic half (mobile.md module `webrtc`, ADR-003 factory B): the
 * `mic` PC — microphone only, on its own [PeerConnectionFactory] with the
 * stock [JavaAudioDeviceModule] (`VOICE_COMMUNICATION`, mono → Opus mono,
 * audio.md), negotiated independently of the `media` PC over the same
 * signaling socket (`pc` discriminator, webrtc.md).
 *
 * The mic is off by default (audio.md); [CastService] builds this session
 * when the user turns it on mid-cast and tears it down when they turn it off.
 * That independence is the point: the `media` PC (screen + game audio) is
 * never renegotiated because of the mic, and every failure here is
 * **mic-local** — the cast keeps running and the UI shows [MicState.Failed],
 * never a cast error. Cast-fatal events (projection revoked, desktop gone)
 * are owned by [MediaCastSession]; the service stops this session alongside
 * it.
 *
 * Threading mirrors [MediaCastSession]: all PC work on a private
 * HandlerThread; signaling events arrive on transport threads and are posted
 * over.
 */
class MicCastSession(
    private val context: Context,
    private val signaling: SignalingClient,
    /** State changes, delivered from the session thread. */
    private val onState: (MicState) -> Unit = {},
    statsIntervalMs: Long = STATS_INTERVAL_MS,
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var pc: PeerConnection? = null

    /** Desktop candidates that arrived before the answer set the remote description. */
    private val pendingCandidates = mutableListOf<IceCandidate>()

    /** True once the answer's remote description was applied — gates addIceCandidate. */
    private var remoteDescriptionSet = false

    /** Grown once the answer's parameters exist; sampled by the stats poller. */
    private var lastBytesSent = 0L
    private var lastStatsAtMs = 0L
    private val statsIntervalMs = statsIntervalMs

    private val signalingListener: (SignalingClient.Event) -> Unit = { event ->
        when (event) {
            // Every (re)auth rebuilds the pc, like the media session — the old
            // transport objects are dead after a reconnect.
            is SignalingClient.Event.Authorized ->
                post { if (active) negotiate() }

            is SignalingClient.Event.SdpAnswer ->
                if (event.pc == PC_ID) post { if (active) applyAnswer(event.sdp) }

            is SignalingClient.Event.IceCandidate ->
                if (event.pc == PC_ID) post { if (active) applyRemoteCandidate(event.candidate) }

            // The cast as a whole is ending (desktop gone, bye, or terminal
            // signaling failure) — the media session reports it to the user;
            // this session just stops itself.
            is SignalingClient.Event.SessionEnded,
            is SignalingClient.Event.Failed,
            -> post { if (active) stopLocal(MicState.Off) }

            else -> Unit
        }
    }

    /** True between [start] and teardown — guards late signaling callbacks. */
    private var active = false

    fun start() {
        check(!active) { "mic session already started" }
        active = true
        val sessionThread = HandlerThread("MicCastSession").apply { start() }
        thread = sessionThread
        handler = Handler(sessionThread.looper)
        signaling.addListener(signalingListener)
        post { buildOnThread() }
    }

    fun stop() {
        post { stopLocal(MicState.Off) }
    }

    // ---- Everything below runs (or is posted to) the session thread ----

    private fun post(block: () -> Unit) {
        val h = handler
        if (h !== null) {
            h.post(block)
        } else {
            block()
        }
    }

    private fun buildOnThread() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
            )
            // Factory B (ADR-003): the stock ADM records the actual microphone
            // — voice defaults (VOICE_COMMUNICATION, mono, platform AEC/NS/AGC)
            // are wanted here, unlike the playback-capture factory A.
            val adm = JavaAudioDeviceModule.builder(context)
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String) = micFailed("record init: $errorMessage")
                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                        errorMessage: String,
                    ) = micFailed("record start ($errorCode): $errorMessage")
                    override fun onWebRtcAudioRecordError(errorMessage: String) = micFailed("record: $errorMessage")
                })
                .createAudioDeviceModule()
            val newFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
            factory = newFactory

            val newAudioSource = newFactory.createAudioSource(MediaConstraints())
            audioSource = newAudioSource
            val newAudioTrack = newFactory.createAudioTrack(AUDIO_TRACK_ID, newAudioSource)
            audioTrack = newAudioTrack

            onState(MicState.Active)
            negotiate()
        } catch (t: Throwable) {
            Log.e(TAG, "building the mic pipeline failed", t)
            micFailed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** (Re)create the pc and send a fresh offer. Runs on the session thread. */
    private fun negotiate() {
        closePc()
        pendingCandidates.clear()
        val newFactory = factory ?: return
        val newTrack = audioTrack ?: return

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // LAN only (webrtc.md): host candidates, no STUN/TURN.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val newPc = newFactory.createPeerConnection(rtcConfig, pcObserver)
        if (newPc === null) {
            micFailed("createPeerConnection returned null")
            return
        }
        pc = newPc
        remoteDescriptionSet = false
        if (newPc.addTrack(newTrack, listOf(STREAM_ID)) === null) {
            micFailed("addTrack returned null — native track wiring failed")
            return
        }
        newPc.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(original: SessionDescription) {
                    // Audio-only offer: Opus is the only codec listed — no
                    // munging needed (the codec policy in webrtc.md is per
                    // track, and mic has no fallback).
                    newPc.setLocalDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() {
                                signaling.sendSdpOffer(PC_ID, original.description)
                                Log.i(TAG, "sdp offer sent (${original.description.length} bytes)")
                            }

                            override fun onSetFailure(error: String?) = micFailed("setLocalDescription: $error")

                            override fun onCreateSuccess(description: SessionDescription) = Unit
                            override fun onCreateFailure(error: String?) = Unit
                        },
                        original,
                    )
                }

                override fun onCreateFailure(error: String?) = micFailed("createOffer: $error")

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            },
            MediaConstraints(),
        )
    }

    private fun applyAnswer(sdp: String) {
        val currentPc = pc ?: return
        currentPc.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    val queued = pendingCandidates.toList()
                    pendingCandidates.clear()
                    for (candidate in queued) currentPc.addIceCandidate(candidate)
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "setRemoteDescription failed: $error")
                }

                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String?) = Unit
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    private fun applyRemoteCandidate(element: JsonElement) {
        val currentPc = pc ?: return
        if (element is JsonNull) {
            Log.d(TAG, "desktop finished ice gathering")
            return
        }
        val candidate = IceCandidateJson.decode(element)
        if (candidate === null) {
            Log.w(TAG, "malformed candidate from desktop — ignoring")
            return
        }
        if (!remoteDescriptionSet) {
            pendingCandidates.add(candidate)
        } else {
            currentPc.addIceCandidate(candidate)
        }
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signaling.sendIceCandidate(PC_ID, IceCandidateJson.encode(candidate))
        }

        override fun onIceGatheringChange(event: PeerConnection.IceGatheringState?) {
            if (event == PeerConnection.IceGatheringState.COMPLETE) {
                signaling.sendIceCandidate(PC_ID, null)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connection state: $newState")
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> scheduleStats()

                // Mic-local only: the user turned the mic off on the other
                // side, or the mic link died — the cast (media pc) is the
                // media session's business, never touched from here.
                PeerConnection.PeerConnectionState.FAILED -> micFailed("ice failed")
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
    }

    // ---- getStats logging (webrtc.md: ~1 Hz; audio bytes prove the mic is flowing) ----

    private val statsRunnable = object : Runnable {
        override fun run() {
            val currentPc = pc
            if (currentPc === null || !active) return
            currentPc.getStats { report ->
                val sample = SenderStats.sampleAudioSend(report.statsMap.values.map { it.members })
                if (sample !== null) {
                    val nowMs = System.currentTimeMillis()
                    val bitrateBps = if (lastStatsAtMs > 0) {
                        ((sample.bytesSent - lastBytesSent) * 8000) / (nowMs - lastStatsAtMs).coerceAtLeast(1)
                    } else {
                        0
                    }
                    Log.i(
                        TAG,
                        "stats: ${bitrateBps / 1_000} kbps, rtt ${sample.rttMs ?: "?"} ms",
                    )
                    lastBytesSent = sample.bytesSent
                    lastStatsAtMs = nowMs
                }
            }
            if (active) {
                handler?.postDelayed(this, statsIntervalMs)
            }
        }
    }

    private fun scheduleStats() {
        handler?.removeCallbacks(statsRunnable)
        handler?.postDelayed(statsRunnable, statsIntervalMs)
    }

    // ---- teardown ----

    /**
     * Idempotent teardown; [terminal] is the state published when it finishes
     * ([MicState.Off] for a user stop, [MicState.Failed] for a capture/ice
     * death).
     */
    private fun stopLocal(terminal: MicState) {
        if (!active && thread === null) return
        active = false
        signaling.removeListener(signalingListener)
        handler?.removeCallbacks(statsRunnable)
        closePc()
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        // The ADM is owned (and released) by the factory — its release must
        // not be called from app code.
        factory?.dispose()
        factory = null
        thread?.quitSafely()
        thread = null
        handler = null
        onState(terminal)
        Log.i(TAG, "mic session torn down")
    }

    private fun closePc() {
        pc?.close()
        pc = null
    }

    /**
     * Mic-local failure: stop this session and tell the UI — the cast keeps
     * running (audio.md). Safe from any thread via post; a no-op if the
     * session is already down.
     */
    private fun micFailed(detail: String) {
        Log.w(TAG, "mic unavailable: $detail")
        post {
            if (active) stopLocal(MicState.Failed)
        }
    }

    companion object {
        private const val TAG = "MicCastSession"

        /** webrtc.md: each side polls getStats ~1 Hz. */
        const val STATS_INTERVAL_MS = 1_000L

        private const val PC_ID = "mic"
        private const val AUDIO_TRACK_ID = "zfc-mic"
        private const val STREAM_ID = "zfc-mic"
    }
}
