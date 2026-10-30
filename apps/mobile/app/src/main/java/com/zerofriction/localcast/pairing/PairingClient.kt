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
    /**
     * Persistent install-scoped identity sent as hello's `deviceId` (ADR-005).
     * Null (the default) omits the field — the unidentified shape the desktop
     * accepts; the real app always injects it (ScanScreen's VM factory).
     */
    private val deviceIdProvider: (() -> String)? = null,
    /**
     * Persistent session (ADR-006): invoked after every successful auth that
     * the desktop identified itself in — first pairings carry the derived
     * trustKey (a full save), resume re-auths carry refreshed hosts/port only.
     * The UI factories wire this to [SavedDesktops]; keeping it a callback
     * leaves this machine Context-free and JVM-testable.
     */
    private val onDesktopIdentity: ((desktopName: String, grant: SignalingClient.Event.TrustGrant) -> Unit)? = null,
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

    /** This machine's event handler — kept so [releaseSignaling] can unregister it. */
    private var eventHandler: ((SignalingClient.Event) -> Unit)? = null

    /** Name from the last successful auth — shown during reconnect ("Reconnecting to …"). */
    private var lastDesktopName: String? = null

    /**
     * The live signaling connection, once a QR scan started one — only while
     * this machine still owns it. Once a cast starts the connection is handed
     * to [releaseSignaling] and the cast service owns its lifecycle (Phase6).
     */
    fun signalingClient(): SignalingClient? = signaling

    /**
     * Hand the live signaling connection over to the cast service (Phase6
     * session ownership): the socket stays open, but this machine stops
     * tracking it — its events are the cast's business now and the UI renders
     * `CastService.state` instead. After the handover [reset] and
     * [disconnect] are no-ops: they cannot touch what they no longer own, so
     * leaving the scan screen never ends a running cast.
     *
     * Returns the detached client, or null if nothing live is owned.
     */
    fun releaseSignaling(): SignalingClient? {
        val client = signaling ?: return null
        eventHandler?.let { client.removeListener(it) }
        eventHandler = null
        signaling = null
        _state.value = State.Idle
        return client
    }

    /** Entry point for both the camera scan and the debug manual payload input. */
    fun startFromQrText(qrText: String) {
        reset()
        val payload = QrPayloadParser.parse(qrText).getOrElse { exception ->
            val error = (exception as PairingParseException).error
            Log.w(TAG, "QR payload rejected: $error")
            _state.value = State.Failed(PairingError.fromQr(error))
            return
        }

        // A valid QR means a desktop was found — Phase13's immediate
        // post-scan feedback ("Desktop found") before any socket opens.
        _state.value = State.Connecting

        val client = SignalingClient(
            hosts = payload.h,
            port = payload.p,
            sessionId = payload.s,
            secret = Handshake.decodeSecret(payload.k),
            userAgent = userAgentProvider(),
            deviceId = deviceIdProvider?.invoke(),
            expiresAtUnixSeconds = payload.e,
            transport = transportFactory(),
            scheduler = schedulerFactory(),
        )
        signaling = client
        val handler: (SignalingClient.Event) -> Unit = { event ->
            when (event) {
                SignalingClient.Event.Authenticating -> _state.value = State.Authenticating
                is SignalingClient.Event.Authorized -> {
                    lastDesktopName = event.desktopName
                    event.trust?.let { grant -> onDesktopIdentity?.invoke(event.desktopName, grant) }
                    _state.value = State.Connected(event.desktopName)
                }
                is SignalingClient.Event.Reconnecting ->
                    _state.value = State.Reconnecting(lastDesktopName)
                is SignalingClient.Event.SdpAnswer,
                is SignalingClient.Event.IceCandidate,
                -> Unit // media plumbing — consumed by the webrtc module
                is SignalingClient.Event.SessionEnded -> {
                    lastDesktopName = null
                    _state.value = State.Ended
                }
                is SignalingClient.Event.Failed -> _state.value = State.Failed(PairingError.fromSignaling(event.error))
            }
        }
        eventHandler = handler
        client.connect(handler)
    }

    /**
     * Reconnect to the saved desktop without a QR (ADR-006): hello carries
     * `resume: true`, the envelope sid is this install's deviceId and the
     * stored trustKey answers the challenge. No expiry — [SignalingClient]
     * bounds the reconnect ladder instead.
     */
    fun startSaved(saved: SavedDesktop, trustKey: ByteArray) {
        reset()
        val deviceId = deviceIdProvider?.invoke()
        if (deviceId == null) {
            Log.w(TAG, "resume needs an install deviceId — falling back to re-scan")
            _state.value = State.Failed(PairingError.DESKTOP_FORGOT)
            return
        }
        _state.value = State.Connecting

        val client = SignalingClient(
            hosts = saved.hosts,
            port = saved.port,
            sessionId = deviceId,
            secret = trustKey,
            userAgent = userAgentProvider(),
            deviceId = deviceId,
            expiresAtUnixSeconds = null,
            transport = transportFactory(),
            scheduler = schedulerFactory(),
            resume = true,
        )
        signaling = client
        val handler: (SignalingClient.Event) -> Unit = { event ->
            when (event) {
                SignalingClient.Event.Authenticating -> _state.value = State.Authenticating
                is SignalingClient.Event.Authorized -> {
                    lastDesktopName = event.desktopName
                    event.trust?.let { grant -> onDesktopIdentity?.invoke(event.desktopName, grant) }
                    _state.value = State.Connected(event.desktopName)
                }
                is SignalingClient.Event.Reconnecting ->
                    _state.value = State.Reconnecting(lastDesktopName)
                is SignalingClient.Event.SdpAnswer,
                is SignalingClient.Event.IceCandidate,
                -> Unit // media plumbing — consumed by the webrtc module
                is SignalingClient.Event.SessionEnded -> {
                    lastDesktopName = null
                    _state.value = State.Ended
                }
                is SignalingClient.Event.Failed -> _state.value = State.Failed(PairingError.fromSignaling(event.error))
            }
        }
        eventHandler = handler
        client.connect(handler)
    }

    /**
     * User-initiated end of the *connection* — a plain `bye` (the saved
     * session survives, ADR-006; the next Connect is one tap). Deleting the
     * session itself is Terminate, which lives beyond this QR-shaped machine.
     */
    fun disconnect() {
        signaling?.disconnect()
        eventHandler = null
        signaling = null
        _state.value = State.Idle
    }

    fun reset() {
        signaling?.disconnect()
        eventHandler = null
        signaling = null
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

    /** "This desktop no longer remembers this phone. Scan the QR code to pair again." (ADR-006) */
    DESKTOP_FORGOT,
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
            SignalingError.UNKNOWN_DEVICE -> DESKTOP_FORGOT
        }
    }
}

/** Production defaults — the UI constructs PairingClient with these. */
fun defaultTransportFactory(): SignalingTransport = OkHttpSignalingTransport()

fun defaultSignalingScheduler(): SignalingScheduler = HandlerSignalingScheduler()
