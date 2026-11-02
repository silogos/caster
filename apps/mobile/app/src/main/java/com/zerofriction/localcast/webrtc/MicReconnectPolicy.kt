package com.zerofriction.localcast.webrtc

/**
 * The mic pc's reconnect policy when its transport dies mid-cast
 * (reliability.md A8, formalized after the live finding 2026-11-02: a
 * connection reset kept screen + game audio — the media pc rides ICE
 * restart + the recovery ladder — but parked the mic as `Failed`, so the
 * user lost the mic until they re-toggled it).
 *
 * Deliberately the same ladder shape as the signaling reconnect
 * (reliability.md: 1→2→5→10→30 s). A **full re-negotiate** is the mic's
 * restart: unlike the media pc it has no quality state to preserve, so the
 * simpler path — close the pc, build a fresh one, send a fresh offer, the
 * same thing a signaling `Authorized` re-auth already does — is the
 * recovery. Capture-side errors never come here: no transport retry fixes
 * a dead `AudioRecord` (that path is the service's builtin degrade).
 */
object MicReconnectPolicy {

    /** The ladder; the last rung repeats until the budget is spent. */
    val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    /** Give-up budget: park the mic as `Failed` after this many attempts. */
    const val MAX_ATTEMPTS = 5

    /**
     * The delay before reconnect attempt [attempt] (0-based), or null when
     * the budget is spent — the caller parks. The last rung repeats so an
     * odd-shaped outage never indexes past the ladder.
     */
    fun backoffMsFor(attempt: Int): Long? {
        if (attempt < 0 || attempt >= MAX_ATTEMPTS) return null
        return BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1)]
    }
}
