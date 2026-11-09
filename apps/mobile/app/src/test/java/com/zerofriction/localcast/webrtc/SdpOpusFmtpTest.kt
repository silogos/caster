package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Opus-stereo fmtp munging is the offer's contract with the encoder
 * (webrtc.md codec policy): without `stereo=1` the negotiated format is mono
 * 32 kbps and the stereo capture is downmixed. Behavior, not implementation:
 * the tests assert the resulting fmtp line and that nothing else moves.
 */
class SdpOpusFmtpTest {

    /** The realistic shape: libwebrtc's audio section, fmtp already present. */
    private fun audioSdp(fmtp: String? = "a=fmtp:111 minptime=10;useinbandfec=1"): String =
        (
            listOfNotNull(
                "v=0",
                "o=- 1 2 IN IP4 127.0.0.1",
                "s=-",
                "t=0 0",
                "m=audio 9 UDP/TLS/RTP/SAVPF 111",
                "a=rtpmap:111 opus/48000/2",
                fmtp,
                "a=msid:media gameaudio",
            ) + listOf(
                "m=video 9 UDP/TLS/RTP/SAVPF 96 97",
                "a=rtpmap:96 VP8/90000",
                "a=fmtp:96 x-google-start-bitrate=800",
                "a=rtpmap:97 H264/90000",
            )
        ).joinToString("\r\n") + "\r\n"

    private fun fmtpLine(sdp: String): String = sdp.lineSequence().first { it.startsWith("a=fmtp:111") }

    @Test
    fun `adds stereo params to the existing opus fmtp`() {
        val result = SdpOpusFmtp.preferStereo(audioSdp())
        assertEquals(
            "a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1;maxaveragebitrate=32000",
            fmtpLine(result),
        )
    }

    @Test
    fun `creates the fmtp line after the rtpmap when missing`() {
        val result = SdpOpusFmtp.preferStereo(audioSdp(fmtp = null))
        val lines = result.split("\r\n")
        assertEquals(
            "a=rtpmap:111 opus/48000/2",
            lines[lines.indexOf("a=fmtp:111 stereo=1;sprop-stereo=1;maxaveragebitrate=32000") - 1],
        )
    }

    @Test
    fun `already stereo sdp is returned untouched`() {
        val sdp = audioSdp(fmtp = "a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1;maxaveragebitrate=32000")
        assertEquals(sdp, SdpOpusFmtp.preferStereo(sdp))
    }

    @Test
    fun `a same-named parameter is never duplicated or overridden`() {
        val sdp = audioSdp(fmtp = "a=fmtp:111 stereo=1;maxaveragebitrate=64000")
        val result = SdpOpusFmtp.preferStereo(sdp)
        assertEquals("a=fmtp:111 stereo=1;maxaveragebitrate=64000;sprop-stereo=1", fmtpLine(result))
    }

    @Test
    fun `custom bitrate is honored`() {
        val result = SdpOpusFmtp.preferStereo(audioSdp(), maxAverageBitrateBps = 510000)
        assertEquals(
            "a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1;maxaveragebitrate=510000",
            fmtpLine(result),
        )
    }

    @Test
    fun `opus found by name and payload type, case-insensitively`() {
        val sdp = (
            listOf(
                "m=audio 9 UDP/TLS/RTP/SAVPF 63 111",
                "a=rtpmap:63 telephone-event/8000",
                "a=fmtp:63 0-15",
                "a=rtpmap:111 OPUS/48000/2",
            ).joinToString("\r\n") + "\r\n"
        )
        val result = SdpOpusFmtp.preferStereo(sdp)
        assertEquals("a=fmtp:63 0-15", result.lineSequence().first { it.startsWith("a=fmtp:63") })
        assertEquals(
            "a=fmtp:111 stereo=1;sprop-stereo=1;maxaveragebitrate=32000",
            result.lineSequence().first { it.startsWith("a=fmtp:111") },
        )
    }

    @Test
    fun `non-audio fmtp lines are untouched`() {
        val result = SdpOpusFmtp.preferStereo(audioSdp())
        assertEquals("a=fmtp:96 x-google-start-bitrate=800", result.lineSequence().first { it.startsWith("a=fmtp:96") })
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 96 97", result.lineSequence().first { it.startsWith("m=video") })
        assertEquals("a=msid:media gameaudio", result.lineSequence().last { it.startsWith("a=msid") })
    }

    @Test
    fun `sdp without audio is returned as-is`() {
        val sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 VP8/90000\r\n"
        assertEquals(sdp, SdpOpusFmtp.preferStereo(sdp))
    }

    @Test
    fun `trailing crlf survives the round trip`() {
        val result = SdpOpusFmtp.preferStereo(audioSdp())
        assertEquals("\r\n", result.takeLast(2))
    }

    @Test
    fun `opusFmtp reports the negotiated line for answer logging`() {
        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1", SdpOpusFmtp.opusFmtp(audioSdp()))
        assertNull(SdpOpusFmtp.opusFmtp("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"))
    }
}
