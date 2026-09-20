package com.zerofriction.localcast.pairing

import android.util.Log
import com.zerofriction.localcast.signaling.Handshake
import com.zerofriction.localcast.signaling.HandlerSignalingScheduler
import com.zerofriction.localcast.signaling.OkHttpSignalingTransport
import com.zerofriction.localcast.signaling.SignalingClient
import com.zerofriction.localcast.signaling.SignalingError
import com.zerofriction.localcast.signaling.SignalingScheduler
import com.zerofriction.localcast.signaling.SignalingTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pairing state machine (docs/architecture/mobile.md: `pairing` owns QR scan,
 * payload parsing and this machine; it knows nothing about UI or media).
 *
 * Flow: scanned QR text → parse payload v1 → connect (hosts in order) →
 * hello/challenge/auth/auth-ok → authorized. Phase4: an authorized connection
 * that drops auto-reconnects (SignalingClient) while the session is valid;
 * `bye` from the desktop ends the session cleanly.
 */
class PairingClient(
    private val transportFactory: () -> SignalingTransport,
    private val schedulerFactory: () -> SignalingScheduler,
    private val userAgentProvider: () -> String,
) {
    sealed interface State {
        data object Idle : State

        data object Connecting : State

        data object Authenticating : State

        data class Connected(val desktopName: String) : State

        /** Auto-reconnecting after a drop; retrying until the session expires. */
        data class Reconnecting(val desktopName: String?) : State

        /** The desktop ended the session (`bye`) — scan again for a fresh one. */
        data object Ended : State

        data class Failed(val error: PairingError) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var signaling: SignalingClient? = null
    /** Name from the last successful auth — shown during reconnect ("Reconnecting to …"). */
    private var lastDesktopName: String? = null

    /** Entry point for both the camera scan and the debug manual payload input. */
    fun startFromQrText(qrText: String) {
        reset()
        val payload = QrPayloadParser.parse(qrText).getOrElse { exception ->
            val error = (exception as PairingParseException).error
            Log.w(TAG, "QR payload rejected: $error")
            _state.value = State.Failed(PairingError.fromQr(error))
            return
        }

        val client = SignalingClient(
            hosts = payload.h,
            port = payload.p,
            sessionId = payload.s,
            secret = Handshake.decodeSecret(payload.k),
            userAgent = userAgentProvider(),
            expiresAtUnixSeconds = payload.e,
            transport = transportFactory(),
            scheduler = schedulerFactory(),
        )
        signaling = client
        client.connect { event ->
            when (event) {
                SignalingClient.Event.Authenticating -> _state.value = State.Authenticating
                is SignalingClient.Event.Authorized -> {
                    lastDesktopName = event.desktopName
                    _state.value = State.Connected(event.desktopName)
                }
                is SignalingClient.Event.Reconnecting ->
                    _state.value = State.Reconnecting(lastDesktopName)
                is SignalingClient.Event.SdpAnswer,
                is SignalingClient.Event.IceCandidate,
                -> Unit // media plumbing — consumed by the webrtc module (Phase5)
                is SignalingClient.Event.SessionEnded -> {
                    lastDesktopName = null
                    _state.value = State.Ended
                }
                is SignalingClient.Event.Failed -> _state.value = State.Failed(PairingError.fromSignaling(event.error))
            }
        }
    }

    /** User-initiated end: sends `bye`; the desktop returns to a fresh QR. */
    fun disconnect() {
        signaling?.disconnect()
        _state.value = State.Idle
    }

    fun reset() {
        signaling?.disconnect()
        _state.value = State.Idle
    }

    private companion object {
        const val TAG = "PairingClient"
    }
}

/** All user-facing failures of the pairing flow — the UI maps these to strings. */
enum class PairingError {
    /** "This isn't a Zero-Friction Cast QR code." */
    NOT_ZFC_QR,

    /** "Desktop app is newer/older — please update." */
    UNSUPPORTED_VERSION,

    /** "Couldn't reach the desktop. Make sure both devices are on the same Wi-Fi network." */
    CONNECT_UNREACHABLE,

    /** "This QR code has expired. Generate a new one on the desktop." */
    EXPIRED,

    /** "Couldn't pair with this desktop. Scan the QR code shown on the desktop." */
    BAD_AUTH,

    /** "The desktop is already connected to another device." */
    BUSY,

    /** "Desktop app is newer/older — please update." (negotiation-level mismatch) */
    BAD_VERSION,
    ;

    companion object {
        fun fromQr(error: QrPayloadError): PairingError = when (error) {
            QrPayloadError.NOT_ZFC_QR -> NOT_ZFC_QR
            QrPayloadError.UNSUPPORTED_VERSION -> UNSUPPORTED_VERSION
        }

        fun fromSignaling(error: SignalingError): PairingError = when (error) {
            SignalingError.CONNECT_UNREACHABLE -> CONNECT_UNREACHABLE
            SignalingError.EXPIRED -> EXPIRED
            SignalingError.BAD_AUTH -> BAD_AUTH
            SignalingError.BUSY -> BUSY
            SignalingError.BAD_VERSION -> BAD_VERSION
        }
    }
}

/** Production defaults — the UI constructs PairingClient with these. */
fun defaultTransportFactory(): SignalingTransport = OkHttpSignalingTransport()

fun defaultSignalingScheduler(): SignalingScheduler = HandlerSignalingScheduler()
