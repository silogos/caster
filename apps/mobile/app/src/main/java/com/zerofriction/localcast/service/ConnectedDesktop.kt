package com.zerofriction.localcast.service

import android.util.Log
import com.zerofriction.localcast.signaling.SignalingClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pairing connection that outlives a cast (designs/mobile-app.html:
 * "Stop share screen" stops the video only — the session stays connected).
 * The app-scoped holder for the signaling socket while **no cast runs**:
 * the scan screen's pairing machine hands a successful pairing over here,
 * the Home hub renders [state], and "Share screen" takes the socket from
 * [releaseForCast] into [CastService], which hands it back on a clean stop
 * ([ConnectedDesktop.adopt] again) and closes it on "Disconnect" (bye — the
 * desktop returns to its fresh QR).
 *
 * Honest limits (pairing.md, by design for now): the session is the QR
 * session — at most its 10-minute expiry — and it lives only as long as the
 * process. Expiry, the desktop's `bye`, a terminal socket failure, or the
 * process dying all land in [State.None]: Home falls back to the scan screen
 * and the user rescans. (Persistent pairing across app restarts is a
 * separate, protocol-level phase.)
 *
 * While a cast runs, this holder is passive ([releaseForCast] removed its
 * listener): the media session owns the socket and the failure paths, and
 * [CastService] clears or re-adopts the state when the cast ends.
 */
object ConnectedDesktop {

    /** None = not paired — the home screen falls back to the scan screen. */
    sealed interface State {
        data object None : State
        data class Connected(val desktopName: String) : State
    }

    private const val TAG = "ConnectedDesktop"

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state.asStateFlow()

    private var signaling: SignalingClient? = null

    /** True exactly while this holder (not a running cast) owns the socket. */
    private var ownsSocket = false

    private val listener: (SignalingClient.Event) -> Unit = { event ->
        when (event) {
            // The desktop said bye, or the socket's ladder ran out (expiry) —
            // the connected state is over no matter who holds the socket.
            is SignalingClient.Event.SessionEnded,
            is SignalingClient.Event.Failed,
            -> {
                Log.i(TAG, "session over (${event.javaClass.simpleName}) — connected state cleared")
                if (ownsSocket) signaling?.disconnect()
                clear()
            }

            else -> Unit
        }
    }

    /** Take over a live pairing (scan success, or a cast stopping back to the hub). */
    @Synchronized
    fun adopt(client: SignalingClient, desktopName: String) {
        clear()
        signaling = client
        client.addListener(listener)
        ownsSocket = true
        _state.value = State.Connected(desktopName)
        Log.i(TAG, "connected to \"$desktopName\" (session retained)")
    }

    /**
     * Hand the live connection to a starting cast — the service owns the
     * socket from here on. The connected *state* stays (Home shows the
     * cast), but this holder stops listening; [CastService] decides the
     * socket's fate when the cast ends.
     */
    @Synchronized
    fun releaseForCast(): SignalingClient? {
        val client = signaling ?: return null
        client.removeListener(listener)
        ownsSocket = false
        return client
    }

    /** "Disconnect": close the session (bye) wherever the socket is — the desktop shows a fresh QR. */
    @Synchronized
    fun disconnect() {
        val client = signaling
        signaling = null
        if (ownsSocket) client?.removeListener(listener)
        ownsSocket = false
        _state.value = State.None
        client?.disconnect()
        Log.i(TAG, "disconnected")
    }

    /** Drop the state without touching the socket — the holder's own terminal paths. */
    @Synchronized
    fun clear() {
        if (ownsSocket) signaling?.removeListener(listener)
        signaling = null
        ownsSocket = false
        _state.value = State.None
    }
}
