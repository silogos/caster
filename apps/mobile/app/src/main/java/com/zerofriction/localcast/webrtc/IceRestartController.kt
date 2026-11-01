package com.zerofriction.localcast.webrtc

/**
 * The ICE-restart policy (issue #86, webrtc.md §Disconnect): a pure,
 * JVM-testable state machine — no Android types, no WebRTC types.
 * `MediaCastSession` feeds it the pc's connection-state transitions plus a
 * periodic tick, and executes the commands it announces (send a restart
 * offer / give up honestly).
 *
 * Why this exists — the live-debugged mid-game death (2026-11-01, issue #86):
 * a transient LAN UDP outage of tens of seconds (AP band-steering under load,
 * deprioritized background UDP, radio firmware under game load) outlives
 * libwebrtc's ICE consent (~3 missed STUN checks), the pc declares FAILED,
 * and the cast died — while the signaling TCP answered pong in 14 ms during
 * the exact window the UDP flow was dead. That failure profile is
 * *transient*: the calls-app playbook (Zoom/Meet) is an ICE restart — a
 * fresh offer with fresh ICE credentials on the same pc — not session death.
 *
 * Policy per webrtc.md's disconnect rules:
 *  - `disconnected` alone is survivable and common — wait out the grace
 *    ([DISCONNECT_GRACE_MS]); most blips reconnect by themselves.
 *  - `failed` (or a disconnect that outlives the grace) triggers restart #1
 *    immediately; each restart gets [RESTART_DEADLINE_MS] to reconnect.
 *  - A restart that dies (`failed` again) or times out starts the next after
 *    a backoff rung ([RESTART_BACKOFF_MS]) — bounded by [MAX_RESTARTS].
 *  - Two honest bounds close the ladder: restart exhaustion, or a bad
 *    stretch older than [SUSTAINED_FAILURE_GIVE_UP_MS] — whichever first —
 *    surface the existing ConnectionLost failure. A genuinely dead network
 *    still fails honestly, within a bounded window.
 *  - Any `connected` resets everything: each transient outage gets the full
 *    budget again (recovery is the common case, not the exception).
 *
 * A full renegotiation ([onNegotiating] — cast start, or the re-auth rebuild
 * after a socket reconnect) is *also* a fresh ICE attempt: it consumes the
 * deadline watch but not the restart budget (the socket's own reconnect
 * ladder bounds that path).
 */
class IceRestartController(
    /** Execute: send a fresh offer with `iceRestart` semantics ([attempt] is 1-based). */
    private val onRestart: (attempt: Int) -> Unit,

    /** The ladder is exhausted — surface the honest connection-lost failure. */
    private val onGiveUp: (summary: String) -> Unit,
) {

    /** When the current not-connected stretch began — null while healthy. */
    private var badSinceMs: Long? = null

    /** `failed` was seen in this stretch — restart without waiting out the grace. */
    private var failedSeen = false

    /** Restart offers sent in this stretch (full pc rebuilds excluded). */
    private var restartsSent = 0

    /** When the last fresh offer (restart or full negotiate) went out — its deadline watch. */
    private var lastOfferAtMs: Long? = null

    /** When a backoff-delayed restart is scheduled to fire — null when none is pending. */
    private var nextRestartAtMs: Long? = null

    private var gaveUp = false

    /**
     * True while a bad stretch is active — the session keeps its watchdog
     * ticking exactly while this is true (and cancels it on recovery).
     */
    val busy: Boolean get() = !gaveUp && badSinceMs !== null

    /** The pc connected — the stretch (whatever it was) is over. Fresh budget next time. */
    fun onConnected(nowMs: Long) {
        if (badSinceMs === null && !gaveUp) return // already healthy
        badSinceMs = null
        failedSeen = false
        restartsSent = 0
        lastOfferAtMs = null
        nextRestartAtMs = null
        gaveUp = false
    }

    fun onDisconnected(nowMs: Long) {
        if (gaveUp) return
        badSinceMs = badSinceMs ?: nowMs
    }

    fun onFailed(nowMs: Long) {
        if (gaveUp) return
        badSinceMs = badSinceMs ?: nowMs
        failedSeen = true
        // A failed offer in flight concludes that attempt early; anything
        // else (first failure, or one arriving during a backoff) is left to
        // the evaluation below — never bypass the backoff.
        val lastOffer = lastOfferAtMs
        if (nextRestartAtMs === null && lastOffer !== null && nowMs - lastOffer < RESTART_DEADLINE_MS) {
            attemptFailed(nowMs)
        } else {
            evaluate(nowMs)
        }
    }

    /**
     * A fresh full offer went out (cast start, or the re-auth rebuild after a
     * socket reconnect) — it carries new ICE credentials too, so it joins the
     * deadline watch; the restart budget is untouched (the socket's own
     * ladder bounds that path).
     */
    fun onNegotiating(nowMs: Long) {
        if (gaveUp) return
        lastOfferAtMs = nowMs
        nextRestartAtMs = null
    }

    /** Periodic evaluation — the caller's tick is this policy's clock. */
    fun onTick(nowMs: Long) = evaluate(nowMs)

    private fun evaluate(nowMs: Long) {
        if (gaveUp) return
        val badSince = badSinceMs ?: return // healthy — nothing to decide
        if (nowMs - badSince >= SUSTAINED_FAILURE_GIVE_UP_MS) {
            giveUp(nowMs, "sustained failure ${(nowMs - badSince) / 1000}s without recovery")
            return
        }
        val nextRestart = nextRestartAtMs
        if (nextRestart !== null) {
            if (nowMs < nextRestart) return
            sendRestart(nowMs)
            return
        }
        val lastOffer = lastOfferAtMs
        if (lastOffer !== null) {
            if (nowMs - lastOffer < RESTART_DEADLINE_MS) return // offer in flight — keep waiting
            attemptFailed(nowMs) // deadline expired without connecting
            return
        }
        // Nothing in flight, nothing scheduled: first restart of the stretch.
        if (failedSeen || nowMs - badSince >= DISCONNECT_GRACE_MS) {
            if (restartsSent >= MAX_RESTARTS) {
                giveUp(nowMs, "$restartsSent ice restarts exhausted")
                return
            }
            sendRestart(nowMs)
        }
    }

    private fun sendRestart(nowMs: Long) {
        restartsSent += 1
        lastOfferAtMs = nowMs
        nextRestartAtMs = null
        onRestart(restartsSent)
    }

    /** The in-flight attempt concluded without connecting — back off or give up. */
    private fun attemptFailed(nowMs: Long) {
        lastOfferAtMs = null
        if (restartsSent >= MAX_RESTARTS) {
            giveUp(nowMs, "$restartsSent ice restarts exhausted")
            return
        }
        nextRestartAtMs = nowMs + RESTART_BACKOFF_MS[(minOf(restartsSent, RESTART_BACKOFF_MS.size) - 1)]
    }

    private fun giveUp(nowMs: Long, summary: String) {
        badSinceMs = null
        failedSeen = false
        restartsSent = 0
        lastOfferAtMs = null
        nextRestartAtMs = null
        gaveUp = true
        onGiveUp(summary)
    }

    companion object {
        /**
         * webrtc.md's documented Wi-Fi-blip window: a lone `disconnected` gets
         * this long to heal by itself before the first restart offer.
         */
        const val DISCONNECT_GRACE_MS = 10_000L

        /** Bounded retries (issue #86): restart offers per bad stretch. */
        const val MAX_RESTARTS = 3

        /**
         * A fresh offer gets this long to reconnect before the attempt is
         * declared failed — generous vs the 1–3 s a recovering LAN needs,
         * short vs libwebrtc's own consent timeouts.
         */
        const val RESTART_DEADLINE_MS = 15_000L

        /**
         * Backoff before restart #2, #3, … — the same shape as the socket
         * ladder's rungs (small, LAN-scale; nothing needs 30 s here).
         */
        val RESTART_BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L)

        /**
         * The honest bound: a bad stretch this old means the network is
         * genuinely dead, not steering — surface ConnectionLost (existing UX)
         * even if the restart ladder has not formally exhausted.
         */
        const val SUSTAINED_FAILURE_GIVE_UP_MS = 60_000L
    }
}
