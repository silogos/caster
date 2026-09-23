package com.zerofriction.localcast.webrtc

import com.zerofriction.localcast.config.PreferredVideoCodec

/**
 * Codec policy from webrtc.md, applied to the *offer* the mobile creates:
 * the config's preferred codec first (H.264 hardware by default), the other
 * supported codecs after. The offer therefore advertises the phone's chosen
 * send order — the desktop answers with the first codec it supports
 * (Chromium/Electron ships both).
 *
 * SDP munging (reordering the m=video payload list) is the portable way to do
 * this in libwebrtc; `setCodecPreferences` is not uniformly available across
 * the prebuilt's API surface. Pure string work → plain-Kotlin unit-testable.
 */
object SdpCodecOrderer {

    /** webrtc.md's default policy: H.264 first, VP8 fallback, everything else after. */
    val DEFAULT_PREFERENCE = listOf("h264", "vp8")

    /** The offer order for the config's preferred codec ([PreferredVideoCodec]). */
    fun preferenceFor(preferred: PreferredVideoCodec): List<String> =
        when (preferred) {
            PreferredVideoCodec.H264 -> DEFAULT_PREFERENCE
            PreferredVideoCodec.VP8 -> DEFAULT_PREFERENCE.reversed()
        }

    /**
     * Reorders the payload types of the first `m=video` line by [preference]
     * (names matched case-insensitively against their `a=rtpmap:<pt> <name>/…` lines).
     * Audio lines, attributes and non-video media are untouched; an unknown
     * codec keeps its relative order at the end. Returns the same [sdp] if
     * there is nothing to change.
     */
    fun preferVideoCodecs(sdp: String, preference: List<String> = DEFAULT_PREFERENCE): String {
        val lines = sdp.split("\r\n").toMutableList()
        val videoIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoIndex ==-1) return sdp

        val mLine = lines[videoIndex]
        val parts = mLine.split(" ")
        if (parts.size < 4) return sdp
        val payloads = parts.subList(3, parts.size)

        // pt → codec name from this media section's rtpmap lines.
        val codecName = HashMap<String, String>()
        for (line in lines) {
            if (!line.startsWith("a=rtpmap:")) continue
            val body = line.removePrefix("a=rtpmap:")
            val spaceAt = body.indexOf(' ')
            if (spaceAt == -1) continue
            val pt = body.substring(0, spaceAt)
            codecName[pt] = body.substring(spaceAt + 1).substringBefore('/').lowercase()
        }

        val ordered = payloads.sortedBy { pt ->
            val index = preference.indexOf(codecName[pt])
            if (index == -1) preference.size else index
        }
        if (ordered == payloads) return sdp

        lines[videoIndex] = (parts.subList(0, 3) + ordered).joinToString(" ")
        return lines.joinToString("\r\n")
    }
}
