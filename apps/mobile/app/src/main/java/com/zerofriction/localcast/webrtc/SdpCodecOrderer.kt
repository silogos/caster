package com.zerofriction.localcast.webrtc

/**
 * Codec policy from webrtc.md, applied to the *offer* the mobile creates:
 * H.264 (hardware) first, VP8 fallback second, everything else after. The
 * offer therefore advertises the phone's chosen send order — the desktop
 * answers with the first codec it supports (Chromium/Electron ships both).
 *
 * SDP munging (reordering the m=video payload list) is the portable way to do
 * this in libwebrtc; `setCodecPreferences` is not uniformly available across
 * the prebuilt's API surface. Pure string work → plain-Kotlin unit-testable.
 */
object SdpCodecOrderer {

    private val VIDEO_CODEC_PREFERENCE = listOf("h264", "vp8")

    /**
     * Reorders the payload types of the first `m=video` line by [VIDEO_CODEC_PREFERENCE]
     * (names matched case-insensitively against their `a=rtpmap:<pt> <name>/…` lines).
     * Audio lines, attributes and non-video media are untouched; an unknown
     * codec keeps its relative order at the end. Returns the same [sdp] if
     * there is nothing to change.
     */
    fun preferVideoCodecs(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val videoIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoIndex == -1) return sdp

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
            if (spaceAt ==-1) continue
            val pt = body.substring(0, spaceAt)
            codecName[pt] = body.substring(spaceAt + 1).substringBefore('/').lowercase()
        }

        val ordered = payloads.sortedBy { pt ->
            val index = VIDEO_CODEC_PREFERENCE.indexOf(codecName[pt])
            if (index == -1) VIDEO_CODEC_PREFERENCE.size else index
        }
        if (ordered == payloads) return sdp

        lines[videoIndex] = (parts.subList(0, 3) + ordered).joinToString(" ")
        return lines.joinToString("\r\n")
    }
}
