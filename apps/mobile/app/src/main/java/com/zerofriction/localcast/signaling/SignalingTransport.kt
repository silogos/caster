package com.zerofriction.localcast.signaling

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Transport seam for the signaling WebSocket. The protocol logic in
 * [SignalingClient] is plain Kotlin and unit-testable; the OkHttp
 * implementation is the only Android-adjacent piece.
 */
interface SignalingTransport {
    interface Listener {
        fun onTransportOpen()

        fun onTransportText(message: String)

        fun onTransportClosed(code: Int, reason: String)

        fun onTransportFailure(cause: Throwable)
    }

    fun open(url: String, listener: Listener)

    fun send(text: String): Boolean

    fun close(code: Int, reason: String)

    /**
     * Drop the socket *now* — no closing handshake, and no further listener
     * callbacks from it. [close] is polite: it waits for the peer's close
     * frame, which a vanished desktop never sends (OkHttp then waits ~60 s
     * before reporting anything). The caller handles the loss itself.
     */
    fun abort()
}

/** Production transport: OkHttp WebSocket (pinned in libs.versions.toml). */
class OkHttpSignalingTransport : SignalingTransport {

    private val client = OkHttpClient()
    @Volatile
    private var webSocket: WebSocket? = null

    override fun open(url: String, listener: SignalingTransport.Listener) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                // Only the current socket speaks: an aborted or replaced
                // socket's late callbacks must not touch the new connection.
                private fun current(webSocket: WebSocket) = webSocket === this@OkHttpSignalingTransport.webSocket

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (current(webSocket)) listener.onTransportOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (current(webSocket)) listener.onTransportText(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (current(webSocket)) listener.onTransportClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (current(webSocket)) listener.onTransportFailure(t)
                }
            },
        )
    }

    override fun send(text: String): Boolean = webSocket?.send(text) ?: false

    override fun close(code: Int, reason: String) {
        webSocket?.close(code, reason)
    }

    override fun abort() {
        val dropped = webSocket ?: return
        webSocket = null // silences its callbacks before cancel() fires them
        dropped.cancel()
    }
}
