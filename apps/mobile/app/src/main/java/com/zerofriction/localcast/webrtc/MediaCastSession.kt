package com.zerofriction.localcast.webrtc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.PlaybackCaptureAudioSource
import com.zerofriction.localcast.config.CastConfig
import com.zerofriction.localcast.capture.CaptureSize
import com.zerofriction.localcast.capture.DisplaySize
import com.zerofriction.localcast.signaling.SignalingClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * The mobile's media half (mobile.md module `webrtc`): the `media` PC —
 * screen video + game audio in, offer/answer over signaling, ICE trickling,
 * ~1 Hz getStats logging (webrtc.md). The `mic` PC (factory B) arrives in
 * Phase8 (ADR-003).
 *
 * The mobile is always the offerer (webrtc.md): the offer encodes this
 * session's send parameters — H.264 first, VP8 fallback ([SdpCodecOrderer]),
 * the config's bitrate window and degradation preference on the sender.
 *
 * Threading: libwebrtc demands every PC call from one thread, so all work runs
 * on a private HandlerThread; signaling events arrive on transport threads and
 * are posted over. The signaling listener is registered here and removed on
 * teardown — the session never outlives its socket's usefulness by itself.
 */
class MediaCastSession(
    private val context: Context,
    private val signaling: SignalingClient,
    private val config: CastConfig,
    /**
     * The MediaProjection consent result (mobile.md ordering: consent is
     * obtained from the activity BEFORE the service enters the foreground, and
     * the service is in the foreground BEFORE the projection starts).
     */
    private val projectionPermissionIntent: Intent,
    /** Physical display size in px — scaled down to the config's long edge. */
    private val physicalWidth: Int,
    private val physicalHeight: Int,
    statsIntervalMs: Long = STATS_INTERVAL_MS,

    /** Session state changes, delivered from the session thread. */
    private val onState: (State) -> Unit = {},

    /** Game-audio state changes (Phase7) — Off/Active/Muted/Silent/Failed (audio.md). */
    private val onGameAudioState: (GameAudioState) -> Unit = {},

    /**
     * Every ~1 Hz video sender sample (Phase12's auto-quality input). Called
     * from the session thread with the sample and the measured send bitrate.
     */
    private val onVideoStats: (SenderSample, Long) -> Unit = { _, _ -> },

    /**
     * The capture format changed live (rotation or a quality step) — the
     * service re-sends its display-only `session-info` so the desktop's
     * status line follows. Delivered from the session thread.
     */
    private val onCaptureFormat: (width: Int, height: Int, fps: Int) -> Unit = { _, _, _ -> },
) {

    enum class State { STARTING, NEGOTIATING, STREAMING, CLOSED }

    sealed interface Failure {
        /** The user (or system) revoked the projection — a first-class stop path (mobile.md). */
        data object ProjectionRevoked : Failure

        /** The desktop ended the session (`bye`, e.g. its window was closed). */
        data object DesktopEnded : Failure

        /**
         * The link to the desktop is gone for good — ICE failed, or signaling
         * hit a terminal error (session expired/unknown after the reconnect
         * ladder ran out). The user-facing result is the same: rescan.
         */
        data object ConnectionLost : Failure

        /** Anything unexpected — the service shows the simple message, details stay here. */
        data class Error(val detail: String) : Failure
    }

    /** Fires exactly once, from any thread, when the cast can't (or can no longer) continue. */
    private var onFatal: ((Failure) -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoTrack: VideoTrack? = null
    private var pc: PeerConnection? = null

    /** Game audio (Phase7) — null means game audio is off for this cast. */
    private var audio: PlaybackCaptureAudioSource? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    /** Desktop candidates that arrived before the answer set the remote description. */
    private val pendingCandidates = mutableListOf<IceCandidate>()

    /** True once the answer's remote description was applied — gates addIceCandidate. */
    private var remoteDescriptionSet = false

    /** Grown once the answer's parameters exist; sampled by the stats poller. */
    private var lastBytesSent = 0L
    private var lastStatsAtMs = 0L
    private val statsIntervalMs = statsIntervalMs

    /**
     * The sender's *live* bitrate window (Phase12): starts at the config's,
     * moved by [changeQuality] when auto quality steps. [applySenderParameters]
     * and the `session-info` summary both read this, never the frozen config.
     */
    private var liveBitrateMinBps = config.bitrateMinBps
    private var liveBitrateMaxBps = config.bitrateMaxBps

    /**
     * Live capture-format state (Phase14 rotation fix). The quality target
     * moves with [changeQuality]; the actual size is recomputed from the
     * DISPLAY's current bounds — never the cast-start snapshot, which goes
     * stale the moment the device rotates. Thread-confined to the session
     * thread, published via [currentCaptureFormat] for the service's
     * `session-info`.
     */
    private var liveLongEdgePx = config.longEdgePx
    private var liveFps = config.fps
    private var captureWidth = 0
    private var captureHeight = 0

    /** The format in force right now — read by the service from any thread. */
    data class CaptureFormat(val width: Int, val height: Int, val fps: Int)

    @Volatile
    var currentCaptureFormat: CaptureFormat? = null
        private set

    private var displayListener: DisplayManager.DisplayListener? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "media projection stopped — ending the cast")
            fatal(Failure.ProjectionRevoked)
        }
    }

    private val signalingListener: (SignalingClient.Event) -> Unit = { event ->
        when (event) {
            is SignalingClient.Event.Authorized ->
                // Every (re)auth rebuilds the pcs — the socket may have been
                // gone for a while; the old transport objects are dead.
                post { if (active) negotiate() }

            is SignalingClient.Event.SdpAnswer ->
                if (event.pc == PC_ID) post { if (active) applyAnswer(event.sdp) }

            is SignalingClient.Event.IceCandidate ->
                if (event.pc == PC_ID) post { if (active) applyRemoteCandidate(event.candidate) }

            is SignalingClient.Event.SessionEnded -> post { if (active) fatal(Failure.DesktopEnded) }

            // Terminal signaling failure (ladder exhausted / expired QR after a
            // desktop restart): no re-auth will ever come — end the cast.
            is SignalingClient.Event.Failed ->
                post { if (active) fatal(Failure.ConnectionLost) }

            // Pairing/lifecycle events belong to the PairingClient; a mid-cast
            // reconnect is normal (backoff ladder) and does not stop the cast.
            else -> Unit
        }
    }

    /** True between [start] and teardown — guards late signaling callbacks. */
    private var active = false

    fun start(onFatal: (Failure) -> Unit) {
        check(!active) { "session already started" }
        this.onFatal = onFatal
        active = true
        val sessionThread = HandlerThread("MediaCastSession").apply { start() }
        thread = sessionThread
        handler = Handler(sessionThread.looper)
        signaling.addListener(signalingListener)
        post { buildOnThread() }
    }

    fun stop() {
        post { teardown() }
    }

    /**
     * User mute of the game-audio track (audio.md): zeroes the captured
     * frames sender-side — the Opus stream keeps flowing, no renegotiation,
     * and the video track is untouched. No-op when game audio is off.
     */
    fun setGameAudioMuted(muted: Boolean) {
        post { audio?.setMuted(muted) }
    }

    /** The current game-audio state (safe from any thread — read-only). */
    val gameAudioState: GameAudioState get() = audio?.state ?: GameAudioState.Off

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
            val egl = EglBase.create()
            eglBase = egl
            // Game audio rides the media pc (ADR-003): factory A's ADM is the
            // playback-capture ADM. The projection doesn't exist yet — the
            // audio source fetches it lazily at record start (one consent =
            // one projection, reused from the capturer).
            val newAudio = if (config.gameAudio && hasRecordAudioPermission()) {
                PlaybackCaptureAudioSource(context, projection = { capturer?.mediaProjection }, onState = onGameAudioState)
            } else {
                if (config.gameAudio) {
                    Log.w(TAG, "RECORD_AUDIO not granted — casting without game audio")
                }
                null
            }
            audio = newAudio
            val factoryBuilder = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            if (newAudio !== null) factoryBuilder.setAudioDeviceModule(newAudio.adm)
            val newFactory = factoryBuilder.createPeerConnectionFactory()
            factory = newFactory

            val (width, height) = CaptureSize.scaleTo(config.longEdgePx, physicalWidth, physicalHeight)
            Log.i(TAG, "capture target ${width}x${height} @ ${config.fps} fps")

            val source = newFactory.createVideoSource(/* isScreencast = */ true)
            videoSource = source
            val helper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
            surfaceTextureHelper = helper
            val newCapturer = ScreenCapturerAndroid(projectionPermissionIntent, projectionCallback)
            capturer = newCapturer
            newCapturer.initialize(helper, context, source.capturerObserver)
            newCapturer.startCapture(width, height, config.fps)
            captureWidth = width
            captureHeight = height
            currentCaptureFormat = CaptureFormat(width, height, config.fps)
            // Follow device rotation live (Phase14 fix): the stock capturer
            // does NOT resize on rotation (found live — a portrait-start cast
            // kept its portrait virtual display forever, and Android squeezed
            // the rotated screen into it). The default display's change
            // events arrive on the session thread via the session handler;
            // [applyCaptureFormat] no-ops unless the size actually changed.
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY && active) applyCaptureFormat()
                }
            }
            context.getSystemService(DisplayManager::class.java)
                .registerDisplayListener(listener, handler)
            displayListener = listener

            val track = newFactory.createVideoTrack(VIDEO_TRACK_ID, source)
            videoTrack = track

            if (newAudio !== null) {
                val constraints = MediaConstraints().apply {
                    // Captured playback is finished audio (audio.md): refuse
                    // the APM's mic-shaped processing on it.
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                }
                val newAudioSource = newFactory.createAudioSource(constraints)
                audioSource = newAudioSource
                val newAudioTrack = newFactory.createAudioTrack(AUDIO_TRACK_ID, newAudioSource)
                audioTrack = newAudioTrack
            }

            // The monitor's initial value has no transition to report —
            // publish the starting state explicitly (Off = not in this cast).
            onGameAudioState(if (newAudio !== null) GameAudioState.Active else GameAudioState.Off)

            // Display-only summary for the desktop status line (webrtc.md) —
            // not configuration; the desktop never acts on it. The mic flag is
            // the config's *intent*; the service sends the authoritative
            // summary (mic actually on) once it knows — see CastService.
            signaling.sendSessionInfo(
                profile = config.profile,
                width = width,
                height = height,
                fps = config.fps,
                gameAudio = newAudio !== null,
                mic = config.mic,
            )

            negotiate()
        } catch (t: Throwable) {
            Log.e(TAG, "building the media pipeline failed", t)
            fatal(Failure.Error(t.message ?: t.javaClass.simpleName))
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** (Re)create the pc and send a fresh offer. Runs on the session thread. */
    private fun negotiate() {
        closePc()
        pendingCandidates.clear()
        val newFactory = factory ?: return
        val newTrack = videoTrack ?: return

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // LAN only (webrtc.md): host candidates, no STUN/TURN.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val newPc = newFactory.createPeerConnection(rtcConfig, pcObserver)
        if (newPc === null) {
            fatal(Failure.Error("createPeerConnection returned null"))
            return
        }
        pc = newPc
        remoteDescriptionSet = false
        val trackSender = newPc.addTrack(newTrack, listOf(STREAM_ID))
        if (trackSender === null) {
            fatal(Failure.Error("addTrack returned null — native track wiring failed"))
            return
        }
        // Game audio rides the same pc + stream as the video (ADR-003) — the
        // desktop plays both from one MediaStream.
        val currentAudioTrack = audioTrack
        if (currentAudioTrack !== null) {
            newPc.addTrack(currentAudioTrack, listOf(STREAM_ID))
        }
        onState(State.NEGOTIATING)

        newPc.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(original: SessionDescription) {
                    // Codec policy lives in the offer (webrtc.md): H.264 first.
                    val munged = SessionDescription(
                        original.type,
                        SdpCodecOrderer.preferVideoCodecs(original.description),
                    )
                    newPc.setLocalDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() {
                                signaling.sendSdpOffer(PC_ID, munged.description)
                                Log.i(TAG, "sdp offer sent (${munged.description.length} bytes)")
                            }

                            override fun onSetFailure(error: String?) = fatal(Failure.Error("setLocalDescription: $error"))

                            override fun onCreateSuccess(description: SessionDescription) = Unit
                            override fun onCreateFailure(error: String?) = Unit
                        },
                        munged,
                    )
                }

                override fun onCreateFailure(error: String?) = fatal(Failure.Error("createOffer: $error"))

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
                    applySenderParameters(currentPc)
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

    /**
     * Sender-side parameters from the config module only (webrtc.md): the
     * *live* bitrate window ([liveBitrateMinBps]/[liveBitrateMaxBps] — the
     * config's at cast start, moved by [changeQuality]) and BALANCED
     * degradation. Called once the answer exists; if the pc refuses (early
     * states), the values reapply on the next call. Targets the *video*
     * sender explicitly — since Phase7 the pc also carries an audio sender,
     * and `senders` order is not a contract.
     */
    private fun applySenderParameters(currentPc: PeerConnection) {
        val sender = currentPc.senders.firstOrNull { it.track()?.kind() == "video" } ?: return
        val parameters = sender.parameters
        if (parameters.encodings.isEmpty()) return
        parameters.encodings.first().apply {
            minBitrateBps = liveBitrateMinBps
            maxBitrateBps = liveBitrateMaxBps
        }
        parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
        val result = sender.setParameters(parameters)
        if (!result) {
            Log.w(TAG, "setParameters rejected — bitrate/degradation targets may not be applied")
        }
    }

    /**
     * Re-apply the capture format from the display's *current* bounds and the
     * live quality target (rotation or a quality step). Runs on the session
     * thread — the display listener arrives on it via the session handler.
     * No-ops when nothing changed, so unrelated display events are free;
     * `force` re-applies the (unchanged) format anyway — a quality step can
     * change fps alone.
     */
    private fun applyCaptureFormat(force: Boolean = false) {
        val newCapturer = capturer ?: return
        val (displayWidth, displayHeight) = DisplaySize.physicalPx(context)
        val target = CaptureSize.followDisplay(liveLongEdgePx, captureWidth, captureHeight, displayWidth, displayHeight)
        if (target !== null) {
            newCapturer.changeCaptureFormat(target.first, target.second, liveFps)
            captureWidth = target.first
            captureHeight = target.second
            currentCaptureFormat = CaptureFormat(target.first, target.second, liveFps)
            Log.i(TAG, "capture → ${target.first}x${target.second} @ ${liveFps} fps (display ${displayWidth}x${displayHeight})")
            onCaptureFormat(target.first, target.second, liveFps)
        } else if (force) {
            newCapturer.changeCaptureFormat(captureWidth, captureHeight, liveFps)
            currentCaptureFormat = CaptureFormat(captureWidth, captureHeight, liveFps)
            Log.i(TAG, "capture format re-applied @ ${liveFps} fps")
            onCaptureFormat(captureWidth, captureHeight, liveFps)
        }
    }

    /**
     * One auto-quality step, applied live (Phase12, thermal.md — no
     * renegotiation): the capture pipeline reconfigures to the new
     * resolution/fps and the video sender's bitrate window moves. Runs on the
     * session thread; safe to call from any thread. The desktop's display-only
     * status line follows via the service's fresh `session-info`.
     */
    fun changeQuality(longEdgePx: Int, fps: Int, bitrateMinBps: Int, bitrateMaxBps: Int) {
        post {
            if (!active) return@post
            liveLongEdgePx = longEdgePx
            liveFps = fps
            // The display's live bounds — the cast-start snapshot goes stale
            // on rotation, and a quality step after one would re-apply the
            // wrong orientation (found live, fixed together with the
            // rotation listener above).
            applyCaptureFormat(force = true)
            liveBitrateMinBps = bitrateMinBps
            liveBitrateMaxBps = bitrateMaxBps
            val currentPc = pc
            if (currentPc !== null) applySenderParameters(currentPc)
            Log.i(TAG, "adaptive quality: bitrate window → ${bitrateMinBps / 1_000}–${bitrateMaxBps / 1_000} kbps")
        }
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
            // Wire format: the candidate object itself, relayed verbatim by
            // signaling — including mDNS-obfuscated hosts (risk R4).
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
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    onState(State.STREAMING)
                    // Silence detection only counts while actually connected (audio.md).
                    audio?.setStreaming(true)
                    scheduleStats()
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    // Recovery is the offerer's job (ICE restart / rebuild) —
                    // webrtc.md; the desktop never renegotiates on its own.
                    Log.w(TAG, "ice failed — stopping the cast")
                    fatal(Failure.ConnectionLost)
                }
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
        override fun onTrack(transceiver: RtpTransceiver?) = Unit
    }

    // ---- getStats logging (webrtc.md: ~1 Hz) ----

    private val statsRunnable = object : Runnable {
        override fun run() {
            val currentPc = pc
            if (currentPc === null || !active) return
            currentPc.getStats { report ->
                val sample = SenderStats.sampleVideoSend(report.statsMap.values.map { it.members })
                if (sample !== null) {
                    val nowMs = System.currentTimeMillis()
                    val bitrateBps = if (lastStatsAtMs > 0) {
                        ((sample.bytesSent - lastBytesSent) * 8000) / (nowMs - lastStatsAtMs).coerceAtLeast(1)
                    } else {
                        0
                    }
                    Log.i(
                        TAG,
                        "stats: ${bitrateBps / 1_000} kbps, ${sample.framesEncoded} encoded" +
                            ", ${sample.framesDropped} dropped, ${sample.framesPerSecond} fps" +
                            ", ${sample.frameWidth}x${sample.frameHeight}" +
                            ", rtt ${sample.rttMs} ms, encoder ${sample.encoderImplementation ?: "unknown"}",
                    )
                    lastBytesSent = sample.bytesSent
                    lastStatsAtMs = nowMs
                    onVideoStats(sample, bitrateBps)
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

    private fun teardown() {
        if (!active && thread === null) return
        active = false
        signaling.removeListener(signalingListener)
        displayListener?.let { listener ->
            context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(listener)
        }
        displayListener = null
        onState(State.CLOSED)
        handler?.removeCallbacks(statsRunnable)
        closePc()
        try {
            capturer?.stopCapture()
        } catch (t: Throwable) {
            // The projection may already be gone (revoked) — nothing to save.
            Log.d(TAG, "stopCapture after projection death: ${t.message}")
        }
        capturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        // The audio ADM is owned (and released) by the factory below —
        // JavaAudioDeviceModule.release() must not be called from app code.
        audio = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        factory?.dispose()
        factory = null
        eglBase?.release()
        eglBase = null
        thread?.quitSafely()
        thread = null
        handler = null
        Log.i(TAG, "media session torn down")
    }

    private fun closePc() {
        pc?.close()
        pc = null
    }

    private fun fatal(failure: Failure) {
        if (!active) return
        active = false
        post { teardown() }
        onFatal?.invoke(failure)
    }

    companion object {
        private const val TAG = "MediaCastSession"

        /** webrtc.md: each side polls getStats ~1 Hz. */
        const val STATS_INTERVAL_MS = 1_000L

        private const val PC_ID = "media"
        private const val VIDEO_TRACK_ID = "zfc-video"
        private const val AUDIO_TRACK_ID = "zfc-game-audio"
        private const val STREAM_ID = "zfc-media"
    }
}
