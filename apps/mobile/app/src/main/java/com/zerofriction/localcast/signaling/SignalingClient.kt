package com.zerofriction.localcast.signaling

import android.util.Log
import com.zerofriction.localcast.signaling.SignalingClient.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The mobile side of the signaling channel (docs/architecture/webrtc.md):
 * connect to ws://<host>:<port>/zfc/v1 (hosts tried in order), then the pairing
 * handshake, then the Phase4 lifecycle — heartbeat (ping every 5 s, peer
 * silent >15 s is gone) and reconnect-with-backoff while the session from the
 * QR has not expired. Media messages (sdp-offer/answer, ice, session-info) are
 * forwarded as events; the webrtc module (Phase5) consumes them.
 *
 * Transport callbacks arrive on OkHttp threads; state is exposed as a
 * [StateFlow] and events through the callback given to [connect] — consumers
 * must apply them thread-safely (e.g. via their own MutableStateFlow).
 */
class SignalingClient(
    private val hosts: List<String>,
    private val port: Int,
    private val sessionId: String,
    private val secret: ByteArray,
    private val userAgent: String,
    /** Expiry `e` from the QR payload, Unix seconds — reconnects stop at it (webrtc.md). */
    private val expiresAtUnixSeconds: Long,
    private val transport: SignalingTransport,
    private val scheduler: SignalingScheduler,
    /** Injected clock (epoch ms) — drives heartbeat silence and expiry checks. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Named defaults (webrtc.md); injectable so unit tests use tight values. */
    private val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
    private val heartbeatTimeoutMs: Long = HEARTBEAT_TIMEOUT_MS,
) {
    enum class State {
        CONNECTING,
        AUTHENTICATING,
        AUTHORIZED,
        /** An authorized connection dropped; retrying with backoff until expiry. */
        RECONNECTING,
        FAILED,
        CLOSED,
    }

    sealed interface Event {
        data object Authenticating : Event

        /** Fired on every successful auth — the initial one and every re-auth. */
        data class Authorized(val desktopName: String, val proto: Int) : Event

        /** An authorized connection dropped; a reconnect is scheduled after `delayMs`. */
        data class Reconnecting(val delayMs: Long) : Event

        /** SDP answer for one PeerConnection (the mobile is always the offerer). */
        data class SdpAnswer(val pc: String, val sdp: String) : Event

        /**
         * Trickled candidate from the desktop; `candidate` is opaque JSON —
         * [kotlinx.serialization.json.JsonNull] marks end-of-gathering for `pc`.
         */
        data class IceCandidate(val pc: String, val candidate: JsonElement) : Event

        /** `bye` from the desktop — the session is invalidated there; no retry. */
        data class SessionEnded(val reason: String?) : Event

        data class Failed(val error: SignalingError) : Event
    }

    private val _state = MutableStateFlow(State.CONNECTING)
    val state: StateFlow<State> = _state.asStateFlow()

    private var onEvent: ((Event) -> Unit)? = null
    private var hostIndex = 0
    private var sendSeq = 0

    /** True once any handshake succeeded — distinguishes reconnect attempts from first pairing. */
    private var hasAuthorizedOnce = false
    /** Index into [RECONNECT_BACKOFF_MS] for the pending reconnect attempt. */
    private var reconnectAttempt = 0
    private var heartbeatHandle: (() -> Unit)? = null
    private var reconnectHandle: (() -> Unit)? = null
    /** Epoch ms of the last inbound frame — heartbeat silence is measured from it. */
    private var lastInboundAtMs = 0L

    private val listener = object : SignalingTransport.Listener {
        override fun onTransportOpen() {
            _state.value = State.AUTHENTICATING
            Log.i(TAG, "connected, sending hello")
            onEvent?.invoke(Event.Authenticating)
            sendEnvelope(Envelope.TYPE_HELLO, Payloads.hello(userAgent, Handshake.PROTOCOL_MIN, Handshake.PROTOCOL_MAX))
        }

        override fun onTransportText(message: String) {
            // Any frame is heartbeat liveness (webrtc.md: silence > 15 s = gone).
            lastInboundAtMs = nowMs()
            when (val parsed = EnvelopeCodec.parse(message)) {
                EnvelopeParseResult.BadVersion -> fail(SignalingError.BAD_VERSION)
                EnvelopeParseResult.BadMessage -> Log.w(TAG, "ignoring malformed frame")
                is EnvelopeParseResult.Valid -> handleEnvelope(parsed.envelope)
            }
        }

        override fun onTransportClosed(code: Int, reason: String) {
            onConnectionLost("closed ($code $reason)")
        }

        override fun onTransportFailure(cause: Throwable) {
            onConnectionLost("failure: ${cause.message}")
        }
    }

    init {
        require(hosts.isNotEmpty()) { "no candidate hosts to connect to" }
    }

    fun connect(onEvent: (Event) -> Unit) {
        this.onEvent = onEvent
        connectHost(0)
    }

    /**
     * Graceful end: `bye` invalidates the session on the desktop (fresh QR
     * there). No reconnect is scheduled afterwards.
     */
    fun disconnect() {
        if (_state.value == State.AUTHORIZED) {
            sendEnvelope(Envelope.TYPE_BYE, Payloads.bye("user-ended"))
        }
        cancelTimers()
        transport.close(1000, "bye")
        _state.value = State.CLOSED
    }

    // ---- Senders for the Phase5 webrtc module (mobile is always the offerer) ----

    fun sendSdpOffer(pc: String, sdp: String) {
        sendEnvelope(Envelope.TYPE_SDP_OFFER, Payloads.sdp(pc, sdp))
    }

    /** `candidate` null = end-of-gathering for `pc` (webrtc.md). */
    fun sendIceCandidate(pc: String, candidate: JsonElement?) {
        sendEnvelope(Envelope.TYPE_ICE, Payloads.ice(pc, candidate))
    }

    /** Display-only summary for the desktop status line (webrtc.md). */
    fun sendSessionInfo(profile: String, width: Int, height: Int, fps: Int, gameAudio: Boolean, mic: Boolean) {
        sendEnvelope(Envelope.TYPE_SESSION_INFO, Payloads.sessionInfo(profile, width, height, fps, gameAudio, mic))
    }

    // ---- Connection lifecycle ----

    private fun connectHost(index: Int) {
        _state.value = State.CONNECTING
        hostIndex = index
        val url = "ws://${hosts[index]}:$port$SIGNALING_PATH"
        Log.i(TAG, "connecting to $url")
        transport.open(url, listener)
    }

    private fun onConnectionLost(why: String) {
        when (_state.value) {
            State.AUTHORIZED, State.RECONNECTING -> {
                Log.i(TAG, "authorized connection lost: $why")
                initiateReconnect()
            }
            State.CLOSED, State.FAILED -> Unit // intentional end or terminal failure
            else -> {
                Log.i(TAG, "connection lost before auth: $why")
                if (hasAuthorizedOnce) {
                    // A reconnect attempt failed — keep the backoff ladder going.
                    initiateReconnect()
                } else {
                    tryNextHostOrGiveUp()
                }
            }
        }
    }

    /**
     * webrtc.md disconnect rules: retry with backoff 1s → 2s → 5s → 10s → 30s
     * cap while the QR session has not expired; expiry itself never retries
     * (the user re-scans — the desktop shows a fresh QR).
     */
    private fun initiateReconnect() {
        if (_state.value == State.CLOSED || _state.value == State.FAILED) return
        cancelTimers()
        if (nowMs() / 1000 >= expiresAtUnixSeconds) {
            Log.i(TAG, "session expired — no auto-retry, user must re-scan")
            fail(SignalingError.EXPIRED)
            return
        }
        val attempt = reconnectAttempt.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)
        reconnectAttempt += 1
        val delayMs = RECONNECT_BACKOFF_MS[attempt]
        _state.value = State.RECONNECTING
        Log.i(TAG, "reconnecting in ${delayMs} ms (attempt ${reconnectAttempt})")
        onEvent?.invoke(Event.Reconnecting(delayMs))
        reconnectHandle = scheduler.postDelayed(delayMs) {
            if (_state.value == State.RECONNECTING) {
                connectHost(0)
            }
        }
    }

    private fun handleEnvelope(envelope: Envelope) {
        when (envelope.type) {
            Envelope.TYPE_CHALLENGE -> {
                val nonce = Payloads.challengeNonce(envelope)
                if (nonce == null) {
                    Log.w(TAG, "challenge without nonce")
                    fail(SignalingError.BAD_AUTH)
                    return
                }
                val mac = Handshake.computeMac(secret, sessionId, nonce)
                sendEnvelope(Envelope.TYPE_AUTH, Payloads.auth(mac))
            }
            Envelope.TYPE_AUTH_OK -> {
                val name = Payloads.authOkName(envelope)
                val proto = Payloads.authOkProto(envelope)
                if (name == null || proto == null) {
                    Log.w(TAG, "auth-ok without name/proto")
                    fail(SignalingError.BAD_AUTH)
                    return
                }
                Log.i(TAG, "authorized by desktop")
                hasAuthorizedOnce = true
                reconnectAttempt = 0
                _state.value = State.AUTHORIZED
                startHeartbeat()
                onEvent?.invoke(Event.Authorized(name, proto))
            }
            Envelope.TYPE_ERROR -> {
                val code = Payloads.errorCode(envelope)
                if (code == ErrorCodes.BAD_MESSAGE) {
                    // Recoverable (webrtc.md): the frame we sent was malformed;
                    // the connection stays open.
                    Log.w(TAG, "desktop rejected a frame: $code")
                } else {
                    Log.w(TAG, "terminal error from desktop: $code")
                    fail(SignalingError.fromCode(code))
                }
            }
            Envelope.TYPE_PING -> {
                if (_state.value != State.AUTHORIZED) return
                val t = Payloads.pingT(envelope)
                if (t == null) {
                    Log.w(TAG, "ping without t — ignoring")
                } else {
                    sendEnvelope(Envelope.TYPE_PONG, Payloads.ping(t))
                }
            }
            Envelope.TYPE_PONG -> Unit // liveness was recorded on arrival
            Envelope.TYPE_SDP_ANSWER -> {
                val pc = Payloads.pc(envelope)
                val sdp = Payloads.sdpText(envelope)
                if (pc == null || sdp == null) {
                    Log.w(TAG, "malformed sdp-answer — ignoring")
                } else {
                    onEvent?.invoke(Event.SdpAnswer(pc, sdp))
                }
            }
            Envelope.TYPE_ICE -> {
                val pc = Payloads.pc(envelope)
                val candidate = Payloads.iceCandidate(envelope)
                if (pc == null || candidate == null) {
                    Log.w(TAG, "malformed ice — ignoring")
                } else {
                    onEvent?.invoke(Event.IceCandidate(pc, candidate))
                }
            }
            Envelope.TYPE_SESSION_INFO ->
                // m→d only (webrtc.md) — the desktop never sends it.
                Log.w(TAG, "unexpected session-info from desktop — ignoring")
            Envelope.TYPE_BYE -> {
                val reason = Payloads.byeReason(envelope)
                Log.i(TAG, "bye from desktop: ${reason ?: "no reason"}")
                cancelTimers()
                _state.value = State.CLOSED
                transport.close(1000, "bye")
                onEvent?.invoke(Event.SessionEnded(reason))
            }
            else -> Log.d(TAG, "ignoring ${envelope.type}")
        }
    }

    /** Heartbeat (webrtc.md): ping every interval; silence beyond the timeout drops the link. */
    private fun startHeartbeat() {
        lastInboundAtMs = nowMs()
        scheduleHeartbeat()
    }

    private fun scheduleHeartbeat() {
        heartbeatHandle = scheduler.postDelayed(heartbeatIntervalMs) {
            if (_state.value != State.AUTHORIZED) return@postDelayed
            val silentForMs = nowMs() - lastInboundAtMs
            if (silentForMs > heartbeatTimeoutMs) {
                Log.w(TAG, "desktop silent for ${silentForMs} ms — dropping the connection")
                // Closing triggers onConnectionLost → initiateReconnect.
                transport.close(1000, "heartbeat-timeout")
                return@postDelayed
            }
            sendEnvelope(Envelope.TYPE_PING, Payloads.ping(nowMs()))
            scheduleHeartbeat()
        }
    }

    private fun sendEnvelope(type: String, payload: JsonObject) {
        sendSeq += 1
        transport.send(EnvelopeCodec.encode(type, sendSeq, sessionId, payload))
    }

    private fun tryNextHostOrGiveUp() {
        if (_state.value == State.FAILED) return
        if (hostIndex + 1 >= hosts.size) {
            fail(SignalingError.CONNECT_UNREACHABLE)
            return
        }
        connectHost(hostIndex + 1)
    }

    private fun fail(error: SignalingError) {
        cancelTimers()
        _state.value = State.FAILED
        onEvent?.invoke(Event.Failed(error))
    }

    private fun cancelTimers() {
        heartbeatHandle?.invoke()
        heartbeatHandle = null
        reconnectHandle?.invoke()
        reconnectHandle = null
    }

    companion object {
        private const val TAG = "SignalingClient"
        private const val SIGNALING_PATH = "/zfc/v1"

        /** webrtc.md: app-level heartbeat every 5 s. */
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** webrtc.md: a peer silent > 15 s is considered gone. */
        const val HEARTBEAT_TIMEOUT_MS = 15000L

        /** webrtc.md: 1 s → 2 s → 5 s → 10 s → 30 s cap. */
        val RECONNECT_BACKOFF_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    }
}

/** Signaling failures mapped to the pairing.md failure-mode table. */
enum class SignalingError {
    CONNECT_UNREACHABLE,
    EXPIRED,
    BAD_AUTH,
    BUSY,
    BAD_VERSION,
    ;

    companion object {
        fun fromCode(code: String?): SignalingError = when (code) {
            ErrorCodes.UNKNOWN_SESSION -> EXPIRED // stale QR: same user message (pairing.md)
            ErrorCodes.EXPIRED -> EXPIRED
            ErrorCodes.BAD_AUTH -> BAD_AUTH
            ErrorCodes.BUSY -> BUSY
            ErrorCodes.BAD_VERSION -> BAD_VERSION
            else -> BAD_AUTH // unrecognized terminal error: safest generic pairing failure
        }
    }
}
