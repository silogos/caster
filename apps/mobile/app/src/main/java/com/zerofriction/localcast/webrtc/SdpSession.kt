package com.zerofriction.localcast.webrtc

/**
 * The SDP session id — the second field of the `o=` line. JSEP keeps it fixed
 * for one PeerConnection across renegotiations, so an answer whose id differs
 * from the previous answer on the same pc comes from a different remote pc:
 * the desktop restarted (and answered our ICE restart from a fresh process).
 */
object SdpSession {
    fun id(sdp: String): String? =
        sdp.lineSequence()
            .firstOrNull { it.startsWith("o=") }
            ?.removePrefix("o=")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)

    /** True when [answer] comes from a different remote pc than [previous] (null = first answer). */
    fun isNewRemote(previous: String?, answer: String?): Boolean =
        previous != null && answer != null && previous != answer
}
