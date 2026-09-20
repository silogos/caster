package com.zerofriction.localcast.pairing

import android.util.Log
import com.zerofriction.localcast.signaling.Handshake
import com.zerofriction.localcast.signaling.OkHttpSignalingTransport
import com.zerofriction.localcast.signaling.SignalingClient
import com.zerofriction.localcast.signaling.SignalingError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pairing state machine (docs/architecture/mobile.md: `pairing` owns QR scan,
 * payload parsing and this machine; it knows nothing about UI or media).
 *
 * Flow: scanned QR text → parse payload v1 → connect (hosts in order) →
 * hello/challenge/auth/auth-ok. States map 1:1 to what the scan screen shows.
 */
class PairingClient(
    private val transportFactory: () -> com.zerofriction.localcast.signaling.SignalingTransport,
    private val userAgentProvider: () -> String,
) {
    sealed interface State {
        data object Idle : State

        data object Connecting : State

        data object Authenticating : State

        data class Connected(val desktopName: String) : State

        data class Disconnected(val desktopName: String) : State

        data class Failed(val error: PairingError) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var signaling: SignalingClient? = null

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
            transport = transportFactory(),
        )
        signaling = client
        client.connect { event ->
            when (event) {
                SignalingClient.Event.Authenticating -> _state.value = State.Authenticating
                is SignalingClient.Event.Authorized ->
                    _state.update { State.Connected(event.desktopName) }
                is SignalingClient.Event.Failed -> _state.value = State.Failed(PairingError.fromSignaling(event.error))
                SignalingClient.Event.Disconnected ->
                    _state.update { current ->
                        (current as? State.Connected)?.let { State.Disconnected(it.desktopName) } ?: State.Idle
                    }
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
fun defaultTransportFactory(): com.zerofriction.localcast.signaling.SignalingTransport =
    OkHttpSignalingTransport()
