package com.zerofriction.localcast.signaling

import android.util.Log
import com.zerofriction.localcast.signaling.SignalingClient.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

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
    /**
     * hello's optional `deviceId` (pairing.md, ADR-005): the persistent
     * install-scoped UUID the desktop's device registry keys on — never an
     * auth secret; null omits the field (the unidentified-client shape the
     * desktop must accept). On [resume] it doubles as the envelope `sid`: the
     * desktop looks the trust entry up by it.
     */
    private val deviceId: String?,
    /**
     * Expiry `e` from the QR payload, Unix seconds — reconnects stop at it
     * (webrtc.md). Null on the resume path (ADR-006): a trusted session has
     * no expiry, so reconnects run a bounded ladder instead and then stop.
     */
    private val expiresAtUnixSeconds: Long?,
    private val transport: SignalingTransport,
    private val scheduler: SignalingScheduler,
    /** Injected clock (epoch ms) — drives heartbeat silence and expiry checks. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Named defaults (webrtc.md); injectable so unit tests use tight values. */
    private val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
    private val heartbeatTimeoutMs: Long = HEARTBEAT_TIMEOUT_MS,
    /**
     * Persistent-session resume (pairing.md §Persistent sessions, ADR-006):
     * hello carries `resume: true`, the envelope `sid` is the phone's
     * deviceId and [secret] is the stored trustKey — no QR session involved.
     */
    private val resume: Boolean = false,
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

        /**
         * What auth-ok revealed about the desktop (pairing.md §Persistent
         * sessions, ADR-006) — the phone persists/refreshes its saved entry
         * from it. [trustKey] is present only on the QR path (both sides
         * derive it there); a resume re-auth carries the identity fields
         * without a key.
         */
        data class TrustGrant(
            val desktopId: String?,
            val hosts: List<String>,
            val port: Int,
            val trustKey: ByteArray?,
        )

        /** Fired on every successful auth — the initial one and every re-auth. */
        data class Authorized(val desktopName: String, val proto: Int, val trust: TrustGrant?) : Event

        /**
         * The desktop's virtual-camera state (webrtc.md proto 2, ADR-007) —
         * pushed as the vc-query reply and on every change. Only a proto-2
         * desktop ever sends it.
         */
        data class VcStatusEvent(val status: VcStatus) : Event

        /**
         * The desktop's audio output routing state (proto 2) — where its mix
         * plays. The query reply and every change; drives the phone's mirror.
         */
        data class AudioRoutingStatusEvent(val status: AudioRoutingStatus) : Event

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

    /**
     * All event consumers (pairing state machine, and from Phase5 the cast
     * session for media plumbing). CopyOnWriteArrayList: listeners can be
     * added/removed from any thread while events fire on transport threads.
     */
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    private var hostIndex = 0
    private var sendSeq = 0

    /** True once any handshake succeeded — distinguishes reconnect attempts from first pairing. */
    private var hasAuthorizedOnce = false
    /** Index into [RECONNECT_BACKOFF_MS] for the pending reconnect attempt. */
    private var reconnectAttempt = 0
    private var heartbeatHandle: (() -> Unit)? = null
    private var reconnectHandle: (() -> Unit)? = null

    /** Handshake watchdog — armed while a socket is open but auth-ok hasn't arrived. */
    private var handshakeTimeoutHandle: (() -> Unit)? = null
    /** Epoch ms of the last inbound frame — heartbeat silence is measured from it. */
    private var lastInboundAtMs = 0L

    private val listener = object : SignalingTransport.Listener {
        override fun onTransportOpen() {
            _state.value = State.AUTHENTICATING
            Log.i(TAG, "connected, sending hello")
            armHandshakeTimeout()
            emit(Event.Authenticating)
            sendEnvelope(Envelope.TYPE_HELLO, Payloads.hello(userAgent, deviceId, Handshake.PROTOCOL_MIN, Handshake.PROTOCOL_MAX, resume))
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
        listeners.add(onEvent)
        connectHost(0)
    }

    /**
     * Extra event consumers beyond the one passed to [connect] — the cast
     * session (Phase5) subscribes to media plumbing (sdp-answer, ice) this way.
     */
    fun addListener(listener: (Event) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Event) -> Unit) {
        listeners.remove(listener)
    }

    private fun emit(event: Event) {
        for (listener in listeners) {
            listener(event)
        }
    }

    /**
     * Graceful end: `bye` invalidates the session on the desktop (fresh QR
     * there). No reconnect is scheduled afterwards. The reserved `forget`
     * reason (ADR-006) additionally revokes the persistent trust on both
     * sides — that is what Disconnect sends.
     */
    fun disconnect(reason: String = ByeReasons.USER_ENDED) {
        if (_state.value == State.AUTHORIZED) {
            sendEnvelope(Envelope.TYPE_BYE, Payloads.bye(reason))
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

    // ---- Virtual camera control (proto 2, ADR-007): the phone's toggle ----

    /** Ask the desktop to re-send its vc-status (cast start, every re-auth). */
    fun sendVcQuery() {
        sendEnvelope(Envelope.TYPE_VC_QUERY, Payloads.vcCommand())
    }

    /** Turn the desktop's virtual camera on (registers on first use). */
    fun sendVcStart() {
        sendEnvelope(Envelope.TYPE_VC_START, Payloads.vcCommand())
    }

    /** Turn the desktop's virtual camera off. */
    fun sendVcStop() {
        sendEnvelope(Envelope.TYPE_VC_STOP, Payloads.vcCommand())
    }

    // ---- Audio output routing control (proto 2): the phone's mirror of the desktop setting ----

    /** Ask the desktop to re-send its audio-routing status (cast start, re-auth). */
    fun sendAudioRoutingQuery() {
        sendEnvelope(Envelope.TYPE_AUDIO_ROUTING_QUERY, Payloads.vcCommand())
    }

    /** Pick the desktop's output route; the next status carries the effective mode. */
    fun sendAudioRoutingSet(mode: String) {
        sendEnvelope(Envelope.TYPE_AUDIO_ROUTING_SET, Payloads.audioRoutingSet(mode))
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
        cancelHandshakeTimeout()
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
        if (expiresAtUnixSeconds == null) {
            // Trusted session (ADR-006): no expiry — reconnects run a bounded
            // ladder instead, then stop with an unreachable failure (the
            // desktop may simply be off; the saved entry stays).
            if (reconnectAttempt >= TRUSTED_RECONNECT_MAX_ATTEMPTS) {
                Log.i(TAG, "trusted reconnect ladder exhausted — no auto-retry")
                fail(SignalingError.CONNECT_UNREACHABLE)
                return
            }
        } else if (nowMs() / 1000 >= expiresAtUnixSeconds) {
            Log.i(TAG, "session expired — no auto-retry, user must re-scan")
            fail(SignalingError.EXPIRED)
            return
        }
        val attempt = reconnectAttempt.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)
        reconnectAttempt += 1
        val delayMs = RECONNECT_BACKOFF_MS[attempt]
        _state.value = State.RECONNECTING
        Log.i(TAG, "reconnecting in ${delayMs} ms (attempt ${reconnectAttempt})")
        emit(Event.Reconnecting(delayMs))
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
                // Persistent session (ADR-006): newer desktops identify
                // themselves and their LAN candidates — and on the QR path
                // both sides derive the trustKey locally from this session.
                val desktopId = Payloads.authOkDesktopId(envelope)
                val hosts = Payloads.authOkHosts(envelope)
                val trust = if (desktopId == null && hosts.isEmpty()) {
                    null
                } else {
                    val key = if (!resume && desktopId != null) Handshake.deriveTrustKey(secret, sessionId) else null
                    Event.TrustGrant(desktopId, hosts, port, key)
                }
                Log.i(TAG, if (resume) "authorized by desktop (resumed session)" else "authorized by desktop")
                hasAuthorizedOnce = true
                reconnectAttempt = 0
                cancelHandshakeTimeout()
                _state.value = State.AUTHORIZED
                startHeartbeat()
                emit(Event.Authorized(name, proto, trust))
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
                    emit(Event.SdpAnswer(pc, sdp))
                }
            }
            Envelope.TYPE_ICE -> {
                val pc = Payloads.pc(envelope)
                val candidate = Payloads.iceCandidate(envelope)
                if (pc == null || candidate == null) {
                    Log.w(TAG, "malformed ice — ignoring")
                } else {
                    emit(Event.IceCandidate(pc, candidate))
                }
            }
            Envelope.TYPE_SESSION_INFO ->
                // m→d only (webrtc.md) — the desktop never sends it.
                Log.w(TAG, "unexpected session-info from desktop — ignoring")
            Envelope.TYPE_VC_STATUS -> {
                val status = Payloads.vcStatus(envelope)
                if (status == null) {
                    Log.w(TAG, "malformed vc-status — ignoring")
                } else {
                    emit(Event.VcStatusEvent(status))
                }
            }
            Envelope.TYPE_VC_QUERY, Envelope.TYPE_VC_START, Envelope.TYPE_VC_STOP ->
                // m→d only (webrtc.md) — the desktop never sends them.
                Log.w(TAG, "unexpected ${envelope.type} from desktop — ignoring")
            Envelope.TYPE_AUDIO_ROUTING_STATUS -> {
                val status = Payloads.audioRoutingStatus(envelope)
                if (status == null) {
                    Log.w(TAG, "malformed audio-routing-status — ignoring")
                } else {
                    emit(Event.AudioRoutingStatusEvent(status))
                }
            }
            Envelope.TYPE_AUDIO_ROUTING_QUERY, Envelope.TYPE_AUDIO_ROUTING_SET ->
                Log.w(TAG, "unexpected ${envelope.type} from desktop — ignoring")
            Envelope.TYPE_BYE -> {
                val reason = Payloads.byeReason(envelope)
                Log.i(TAG, "bye from desktop: ${reason ?: "no reason"}")
                cancelTimers()
                _state.value = State.CLOSED
                transport.close(1000, "bye")
                emit(Event.SessionEnded(reason))
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
        // A terminal error ends the connection from this side too — the peer
        // sends error-then-close, but this side must not *wait* for the close
        // (found live 2026-10-30: an unknown-device answer left the socket —
        // and the desktop's handshake timer — hanging for the full window).
        transport.close(1000, "error-$error")
        emit(Event.Failed(error))
    }

    /**
     * The handshake watchdog (found live 2026-10-30): a peer that answers
     * nothing — or with frames this client cannot parse (an older desktop
     * sent sid-less handshake errors) — must not leave the UI on
     * "Connecting…" forever. Mirrors pairing.md's desktop-side 10 s rule.
     */
    private fun armHandshakeTimeout() {
        cancelHandshakeTimeout()
        handshakeTimeoutHandle = scheduler.postDelayed(HANDSHAKE_TIMEOUT_MS) {
            handshakeTimeoutHandle = null
            if (_state.value != State.AUTHORIZED) {
                Log.w(TAG, "handshake did not complete in ${HANDSHAKE_TIMEOUT_MS} ms — giving up")
                fail(SignalingError.BAD_AUTH)
            }
        }
    }

    private fun cancelHandshakeTimeout() {
        handshakeTimeoutHandle?.invoke()
        handshakeTimeoutHandle = null
    }

    private fun cancelTimers() {
        heartbeatHandle?.invoke()
        heartbeatHandle = null
        reconnectHandle?.invoke()
        reconnectHandle = null
        cancelHandshakeTimeout()
    }

    companion object {
        private const val TAG = "SignalingClient"
        private const val SIGNALING_PATH = "/zfc/v1"

        /** webrtc.md: app-level heartbeat every 5 s. */
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** webrtc.md: a peer silent > 15 s is considered gone. */
        const val HEARTBEAT_TIMEOUT_MS = 15000L

        /** pairing.md: the full handshake must complete within 10 s — the phone enforces its own half. */
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        /** webrtc.md: 1 s → 2 s → 5 s → 10 s → 30 s cap. */
        val RECONNECT_BACKOFF_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        /**
         * Trusted reconnects (no expiry, ADR-006) stop after this many ladder
         * steps (10 × backoff ≈ a 3.3-minute window) — the desktop may just be
         * off; the user retries with one tap or rescans.
         */
        const val TRUSTED_RECONNECT_MAX_ATTEMPTS = 10
    }
}

/** Signaling failures mapped to the pairing.md failure-mode table. */
enum class SignalingError {
    CONNECT_UNREACHABLE,
    EXPIRED,
    BAD_AUTH,
    BUSY,
    BAD_VERSION,

    /** The desktop no longer trusts this phone (ADR-006) — forget the saved entry and re-scan. */
    UNKNOWN_DEVICE,
    ;

    companion object {
        fun fromCode(code: String?): SignalingError = when (code) {
            ErrorCodes.UNKNOWN_SESSION -> EXPIRED // stale QR: same user message (pairing.md)
            ErrorCodes.EXPIRED -> EXPIRED
            ErrorCodes.BAD_AUTH -> BAD_AUTH
            ErrorCodes.BUSY -> BUSY
            ErrorCodes.BAD_VERSION -> BAD_VERSION
            ErrorCodes.UNKNOWN_DEVICE -> UNKNOWN_DEVICE
            else -> BAD_AUTH // unrecognized terminal error: safest generic pairing failure
        }
    }
}
