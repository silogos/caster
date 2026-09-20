package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Codec-order munging is the offer's contract with the desktop (webrtc.md):
 * H.264 first, VP8 second, the rest after. Behavior, not implementation:
 * the tests assert the resulting m-line and that nothing else moves.
 */
class SdpCodecOrdererTest {

    private fun sdp(videoPayloads: String, vararg rtpmaps: String): String =
        listOf(
            "v=0",
            "o=- 1 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "m=video 9 UDP/TLS/RTP/SAVPF $videoPayloads",
            *rtpmaps,
        ).joinToString("\r\n") + "\r\n"

    private fun videoLine(sdp: String): String = sdp.lineSequence().first { it.startsWith("m=video") }

    @Test
    fun `reorders h264 ahead of vp8 in the m-line`() {
        val sdp = sdp(
            "96 97 98 99",
            "a=rtpmap:96 VP8/90000",
            "a=rtpmap:97 H264/90000",
            "a=rtpmap:98 rtx/90000",
            "a=rtpmap:99 VP9/90000",
        )
        val result = SdpCodecOrderer.preferVideoCodecs(sdp)
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 97 96 98 99", videoLine(result))
    }

    @Test
    fun `case-insensitive codec names`() {
        val sdp = sdp(
            "100 101",
            "a=rtpmap:100 h264/90000",
            "a=rtpmap:101 vp8/90000",
        )
        val result = SdpCodecOrderer.preferVideoCodecs(sdp)
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 100 101", videoLine(result))
    }

    @Test
    fun `unknown codecs keep their relative order after the preferred ones`() {
        val sdp = sdp(
            "96 97 98",
            "a=rtpmap:96 AV1/90000",
            "a=rtpmap:97 VP9/90000",
            "a=rtpmap:98 VP8/90000",
        )
        val result = SdpCodecOrderer.preferVideoCodecs(sdp)
        // VP8 moves up; the two unknowns keep their order behind it.
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 98 96 97", videoLine(result))
    }

    @Test
    fun `already ordered sdp is returned untouched`() {
        val sdp = sdp(
            "96 97",
            "a=rtpmap:96 H264/90000",
            "a=rtpmap:97 VP8/90000",
        )
        assertEquals(sdp, SdpCodecOrderer.preferVideoCodecs(sdp))
    }

    @Test
    fun `audio lines and other attributes are untouched`() {
        val sdp = (
            listOf(
                "m=audio 9 UDP/TLS/RTP/SAVPF 111",
                "a=rtpmap:111 opus/48000/2",
                "m=video 9 UDP/TLS/RTP/SAVPF 96 97",
                "a=rtpmap:96 VP8/90000",
                "a=rtpmap:97 H264/90000",
                "a=sendonly",
            ).joinToString("\r\n") + "\r\n"
        )
        val result = SdpCodecOrderer.preferVideoCodecs(sdp)
        assertEquals("m=audio 9 UDP/TLS/RTP/SAVPF 111", result.lineSequence().first { it.startsWith("m=audio") })
        assertEquals("a=sendonly", result.lineSequence().last { it.isNotEmpty() })
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 97 96", videoLine(result))
    }

    @Test
    fun `sdp without a video line is returned as-is`() {
        val sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        assertEquals(sdp, SdpCodecOrderer.preferVideoCodecs(sdp))
    }
}
