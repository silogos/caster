package com.zerofriction.localcast.service

import android.content.Context
import android.util.Log
import com.zerofriction.localcast.pairing.SavedDesktops
import com.zerofriction.localcast.signaling.ByeReasons
import com.zerofriction.localcast.signaling.SignalingClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The desktop relationship that outlives a cast (designs/mobile-app.html:
 * "Stop share screen" stops the video only — the connection stays). The
 * app-scoped holder for the signaling socket while **no cast runs**: the
 * pairing machines hand a successful connection over here, the Home hub
 * renders [state], and "Share screen" takes the socket from [releaseForCast]
 * into [CastService], which hands it back on a clean stop ([adopt] again) and
 * closes it on "Disconnect".
 *
 * Persistent session (ADR-006): the phone remembers ONE desktop across
 * launches — [State.Saved] is the offline shape of that relationship (Home
 * shows Connect), [State.Connected] the live one. The saved session ends only
 * through Disconnect on either side (`bye forget`); app kills, Wi-Fi drops,
 * dead sockets and ended casts all fall back to Saved, never to None.
 *
 * While a cast runs, this holder is passive ([releaseForCast] removed its
 * listener): the media session owns the socket and the failure paths, and
 * [CastService] clears or re-adopts the state when the cast ends.
 */
object ConnectedDesktop {

    /** None = no saved session at all — the home screen falls back to the scan screen. */
    sealed interface State {
        data object None : State

        /** A saved desktop exists but is offline — one tap on Connect resumes it (ADR-006). */
        data class Saved(val desktopName: String) : State

        data class Connected(val desktopName: String) : State
    }

    private const val TAG = "ConnectedDesktop"

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state.asStateFlow()

    private var signaling: SignalingClient? = null

    /** True exactly while this holder (not a running cast) owns the socket. */
    private var ownsSocket = false

    /** Application context captured at hydrate — the trust store is app-scoped state. */
    private var appContext: Context? = null

    private fun store(): SavedDesktops? = appContext?.let { SavedDesktops(it) }

    private val listener: (SignalingClient.Event) -> Unit = { event ->
        when (event) {
            // The desktop said bye, or the socket's ladder ran out. A `forget`
            // bye (its Disconnect) revoked us — the saved session is gone and
            // the user rescans. Anything else keeps the trust: the holder
            // falls back to Saved (offline), one tap away.
            is SignalingClient.Event.SessionEnded,
            is SignalingClient.Event.Failed,
            -> {
                val forgot = event is SignalingClient.Event.SessionEnded && event.reason == ByeReasons.FORGET
                Log.i(TAG, "connection over (${event.javaClass.simpleName}${if (forgot) ", forgotten" else ""})")
                if (ownsSocket) signaling?.disconnect()
                if (forgot) store()?.clear()
                transitionOffline()
            }

            else -> Unit
        }
    }

    /**
     * Read the saved desktop into the state at app start (MainActivity): None
     * becomes Saved when the store has a usable entry. Captures the
     * application context for the store — safe: this holder is app-scoped.
     */
    fun hydrateSaved(context: Context) {
        appContext = context.applicationContext
        if (_state.value !is State.None) return
        val saved = store()?.load() ?: return
        _state.value = State.Saved(saved.name)
        Log.i(TAG, "saved desktop \"${saved.name}\" hydrated")
    }

    /** Take over a live pairing (scan success, saved-session connect, or a cast stopping back to the hub). */
    @Synchronized
    fun adopt(client: SignalingClient, desktopName: String) {
        clearSocket()
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

    /**
     * "Disconnect" (ADR-006, refined 2026-10-30): close the *connection* only
     * — `bye user-ended` on whichever socket this side still owns, the trust
     * store untouched. The desktop stays on its no-input stage and this side
     * lands in Saved: the next Connect is one tap.
     */
    @Synchronized
    fun disconnect() {
        val client = signaling
        clearSocket()
        _state.value = store()?.load()?.let { State.Saved(it.name) } ?: State.None
        client?.disconnect(ByeReasons.USER_ENDED)
        Log.i(TAG, "disconnected — connection only, saved session kept")
    }

    /**
     * "Terminate": delete the *session* — `bye forget` (the desktop revokes
     * its trust and returns to the QR hero) and the local store cleared.
     * State lands in None: the next pairing is a fresh QR scan.
     */
    @Synchronized
    fun terminate() {
        val client = signaling
        clearSocket()
        store()?.clear()
        _state.value = State.None
        client?.disconnect(ByeReasons.FORGET)
        Log.i(TAG, "terminated — saved session revoked on both sides")
    }

    /**
     * The socket is gone without a Disconnect (dead connection, ended cast,
     * system kill): drop it and land in Saved when the trust store still
     * holds an entry — None otherwise.
     */
    @Synchronized
    fun transitionOffline() {
        clearSocket()
        val saved = store()?.load()
        _state.value = if (saved !== null) State.Saved(saved.name) else State.None
    }

    /** Drop the state to None without touching the socket or the store (scan flow restarts). */
    @Synchronized
    fun clear() {
        clearSocket()
        _state.value = State.None
    }

    private fun clearSocket() {
        if (ownsSocket) signaling?.removeListener(listener)
        signaling = null
        ownsSocket = false
    }
}
