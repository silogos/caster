package com.zerofriction.localcast.signaling

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The mobile side of the pairing handshake (docs/architecture/pairing.md):
 * connect to ws://<host>:<port>/zfc/v1 (hosts tried in order), then
 * hello → challenge → auth → auth-ok. After auth-ok the connection sits idle —
 * Phase4 adds heartbeat, reconnect-with-backoff and media messages.
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
    private val transport: SignalingTransport,
) {
    enum class State {
        CONNECTING,
        AUTHENTICATING,
        AUTHORIZED,
        FAILED,
        CLOSED,
    }

    sealed interface Event {
        data object Authenticating : Event

        data class Authorized(val desktopName: String, val proto: Int) : Event

        data class Failed(val error: SignalingError) : Event

        /** An authorized connection dropped; reconnect-with-backoff is Phase4. */
        data object Disconnected : Event
    }

    private val _state = MutableStateFlow(State.CONNECTING)
    val state: StateFlow<State> = _state.asStateFlow()

    private var onEvent: ((Event) -> Unit)? = null
    private var hostIndex = 0
    private var sendSeq = 0

    private val listener = object : SignalingTransport.Listener {
        override fun onTransportOpen() {
            _state.value = State.AUTHENTICATING
            Log.i(TAG, "connected, sending hello")
            onEvent?.invoke(Event.Authenticating)
            sendEnvelope(Envelope.TYPE_HELLO, Payloads.hello(userAgent, Handshake.PROTOCOL_MIN, Handshake.PROTOCOL_MAX))
        }

        override fun onTransportText(message: String) {
            when (val parsed = EnvelopeCodec.parse(message)) {
                EnvelopeParseResult.BadVersion -> fail(SignalingError.BAD_VERSION)
                EnvelopeParseResult.BadMessage -> Log.w(TAG, "ignoring malformed frame")
                is EnvelopeParseResult.Valid -> handleEnvelope(parsed.envelope)
            }
        }

        override fun onTransportClosed(code: Int, reason: String) {
            if (_state.value == State.AUTHORIZED) {
                Log.i(TAG, "authorized connection closed ($code $reason)")
                _state.value = State.CLOSED
                onEvent?.invoke(Event.Disconnected)
            } else {
                Log.i(TAG, "connection closed before auth ($code $reason)")
                tryNextHostOrGiveUp()
            }
        }

        override fun onTransportFailure(cause: Throwable) {
            Log.w(TAG, "transport failure: ${cause.message}")
            if (_state.value == State.AUTHORIZED) {
                _state.value = State.CLOSED
                onEvent?.invoke(Event.Disconnected)
            } else {
                tryNextHostOrGiveUp()
            }
        }
    }

    init {
        require(hosts.isNotEmpty()) { "no candidate hosts to connect to" }
    }

    fun connect(onEvent: (Event) -> Unit) {
        this.onEvent = onEvent
        connectHost(0)
    }

    /** Graceful end: `bye` invalidates the session on the desktop (fresh QR there). */
    fun disconnect() {
        if (_state.value == State.AUTHORIZED) {
            sendEnvelope(Envelope.TYPE_BYE, Payloads.bye())
        }
        transport.close(1000, "bye")
        _state.value = State.CLOSED
    }

    private fun connectHost(index: Int) {
        _state.value = State.CONNECTING
        hostIndex = index
        val url = "ws://${hosts[index]}:$port$SIGNALING_PATH"
        Log.i(TAG, "connecting to $url")
        transport.open(url, listener)
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
                _state.value = State.AUTHORIZED
                onEvent?.invoke(Event.Authorized(name, proto))
            }
            Envelope.TYPE_ERROR -> {
                val code = Payloads.errorCode(envelope)
                Log.w(TAG, "terminal error from desktop: $code")
                fail(SignalingError.fromCode(code))
            }
            else -> Log.i(TAG, "ignoring ${envelope.type} before Phase4")
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
        _state.value = State.FAILED
        onEvent?.invoke(Event.Failed(error))
    }

    private companion object {
        const val TAG = "SignalingClient"
        const val SIGNALING_PATH = "/zfc/v1"
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
