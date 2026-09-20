package com.zerofriction.localcast.signaling

import android.os.Handler
import android.os.Looper

/**
 * Timer seam for the signaling lifecycle (heartbeat, reconnect backoff).
 * [SignalingClient] is plain Kotlin and unit-tested on the JVM; the production
 * implementation posts to the main looper, tests drive a fake clock.
 */
interface SignalingScheduler {
    /**
     * Run `action` after `delayMs`. Returns a cancel handle; after cancellation
     * the action never runs.
     */
    fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit
}

/** Production scheduler: the main-thread Handler (transport callbacks arrive on OkHttp threads). */
class HandlerSignalingScheduler : SignalingScheduler {

    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}
