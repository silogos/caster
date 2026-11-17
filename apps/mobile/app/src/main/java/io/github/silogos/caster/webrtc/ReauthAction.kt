package io.github.silogos.caster.webrtc

/** What the media pc does when the signaling socket re-authorizes mid-cast. */
enum class ReauthAction {
    /** The pc is connected and nothing is pending: the desktop kept its pc; keep ours. */
    KEEP,

    /** The pc is connected but an offer went unanswered: re-offer on the same pc. */
    REOFFER,

    /** No pc, or not connected: build a new one. */
    REBUILD,
}

/**
 * Pure, JVM-tested. Before 2026-11-18 a connected pc was always kept, so an
 * ICE-restart offer lost while the socket was down was never resent and the
 * receiver waited for it forever (its pc had failed and stopped its frame
 * loop; ours had reconnected on the old credentials).
 */
fun reauthAction(hasPc: Boolean, pcConnected: Boolean, offerUnanswered: Boolean): ReauthAction = when {
    !hasPc || !pcConnected -> ReauthAction.REBUILD
    offerUnanswered -> ReauthAction.REOFFER
    else -> ReauthAction.KEEP
}
