package com.zerofriction.localcast.webrtc

/**
 * Opus fmtp munging for the `media` offer's audio section: libwebrtc's Opus
 * default is **mono at ~32 kbps**, and `setUseStereoInput(true)` only shapes
 * the ADM's record — the encoder follows the *negotiated* format. Without
 * `stereo=1` in the fmtp, the stereo playback capture is downmixed L+R (game
 * mixes' stereo widening sums to comb filtering) and 32 kbps smears transients
 * (pre-echo) — heard on the desktop as an echoey tint the phone's own stereo
 * output doesn't have (user report 2026-11-03).
 *
 * Same portability stance as [SdpCodecOrderer]: fmtp munging is the only lever
 * the pinned prebuilt exposes (no channelCount on `RtpParameters`), and
 * Chromium's answerer mirrors the offer's fmtp parameters into its answer —
 * the negotiated send format then carries stereo to the encoder. The mic PC
 * is untouched: its mono stream is by design (webrtc.md codec policy). Pure
 * string work → plain-Kotlin unit-testable.
 */
object SdpOpusFmtp {

    /**
     * Stereo game/music content at ~transparency on LAN Wi-Fi — still a
     * rounding error next to the video bitrate (6× the 32 kbps default,
     * <3% of even the lowest video preset).
     */
    const val STEREO_MAX_AVERAGE_BITRATE_BPS = 192000

    /**
     * Adds `stereo=1; sprop-stereo=1; maxaveragebitrate=<bps>` to the Opus
     * fmtp (payload type matched case-insensitively against its `a=rtpmap`
     * line; a missing fmtp line is created after the rtpmap). Existing
     * parameters keep their order and value — a parameter already present
     * under the same name is never duplicated or overridden. Nothing outside
     * the Opus fmtp moves. Returns the same [sdp] if there is nothing to
     * change (no audio section / no Opus / nothing missing).
     */
    fun preferStereo(sdp: String, maxAverageBitrateBps: Int = STEREO_MAX_AVERAGE_BITRATE_BPS): String {
        val opusPayload = opusPayloadType(sdp) ?: return sdp
        val wanted = listOf("stereo=1", "sprop-stereo=1", "maxaveragebitrate=$maxAverageBitrateBps")
        val lines = sdp.split("\r\n").toMutableList()
        val fmtpIndex = lines.indexOfFirst { it.startsWith("a=fmtp:$opusPayload ") }
        if (fmtpIndex == -1) {
            val rtpmapIndex = lines.indexOfFirst { it.startsWith("a=rtpmap:$opusPayload ") }
            if (rtpmapIndex == -1) return sdp
            lines.add(rtpmapIndex + 1, "a=fmtp:$opusPayload " + wanted.joinToString(";"))
            return lines.joinToString("\r\n")
        }
        val existing = lines[fmtpIndex].split(" ", limit = 2).getOrNull(1).orEmpty().split(";")
        val existingNames = existing.map { it.substringBefore('=') }.toSet()
        val missing = wanted.filter { it.substringBefore('=') !in existingNames }
        if (missing.isEmpty()) return sdp
        lines[fmtpIndex] = lines[fmtpIndex] + missing.joinToString(separator = ";", prefix = ";")
        return lines.joinToString("\r\n")
    }

    /** The Opus `a=fmtp` line as it stands in [sdp], for answer-side logging. */
    fun opusFmtp(sdp: String): String? {
        val opusPayload = opusPayloadType(sdp) ?: return null
        return sdp.lineSequence().firstOrNull { it.startsWith("a=fmtp:$opusPayload ") }
    }

    private fun opusPayloadType(sdp: String): String? =
        sdp.lineSequence()
            .filter { it.startsWith("a=rtpmap:") }
            .mapNotNull { line ->
                val body = line.removePrefix("a=rtpmap:")
                val spaceAt = body.indexOf(' ')
                if (spaceAt == -1) null else body.substring(0, spaceAt) to body.substring(spaceAt + 1)
            }
            .firstOrNull { (_, codec) -> codec.substringBefore('/').equals("opus", ignoreCase = true) }
            ?.first
}
