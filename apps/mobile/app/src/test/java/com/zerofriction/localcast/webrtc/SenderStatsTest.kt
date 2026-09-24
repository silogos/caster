package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sender-stats extraction from raw RTCStats members (Phase5 logging input —
 * the measured basis for Phase12 adaptation; plain maps so this runs on JVM).
 */
class SenderStatsTest {

    @Test
    fun `extracts the video outbound-rtp entry and the nominated pair rtt`() {
        val sample = SenderStats.sampleVideoSend(
            listOf(
                mapOf("type" to "outbound-rtp", "kind" to "audio", "bytesSent" to 1L),
                mapOf(
                    "type" to "outbound-rtp",
                    "kind" to "video",
                    "bytesSent" to 750_000L,
                    "framesEncoded" to 900L,
                    "framesDropped" to 3L,
                    "framesPerSecond" to 29.5,
                    "frameWidth" to 800,
                    "frameHeight" to 1280,
                    "encoderImplementation" to "OMX.qcom.video.encoder.avc",
                ),
                mapOf("type" to "candidate-pair", "nominated" to true, "state" to "succeeded", "currentRoundTripTime" to 0.0018),
                mapOf("type" to "candidate-pair", "nominated" to false, "state" to "succeeded", "currentRoundTripTime" to 9.9),
            ),
        )
        assertEquals(
            SenderSample(
                bytesSent = 750_000L,
                framesEncoded = 900L,
                framesDropped = 3L,
                framesPerSecond = 29.5,
                frameWidth = 800,
                frameHeight = 1280,
                rttMs = 1L,
                encoderImplementation = "OMX.qcom.video.encoder.avc",
                fractionLost = null,
            ),
            sample,
        )
    }

    // ---- Phase12: the desktop receiver's own loss report ----

    @Test
    fun `extracts the video fractionLost from the remote receiver's report`() {
        val sample = SenderStats.sampleVideoSend(
            listOf(
                mapOf("type" to "outbound-rtp", "kind" to "video", "bytesSent" to 750_000L),
                mapOf("type" to "remote-inbound-rtp", "kind" to "video", "fractionLost" to 0.08),
                mapOf("type" to "remote-inbound-rtp", "kind" to "audio", "fractionLost" to 0.99),
            ),
        )!!
        assertEquals(0.08, sample.fractionLost!!, 1e-9)
    }

    @Test
    fun `fractionLost is null while the desktop has not reported yet`() {
        val sample = SenderStats.sampleVideoSend(
            listOf(mapOf("type" to "outbound-rtp", "kind" to "video", "bytesSent" to 10L)),
        )!!
        assertNull(sample.fractionLost)
    }

    @Test
    fun `null while there is no video outbound-rtp entry yet`() {
        val sample = SenderStats.sampleVideoSend(
            listOf(mapOf("type" to "inbound-rtp", "kind" to "video")),
        )
        assertNull(sample)
    }

    @Test
    fun `missing members degrade to zero or null`() {
        val sample = SenderStats.sampleVideoSend(
            listOf(
                mapOf("type" to "outbound-rtp", "kind" to "video", "bytesSent" to 10L),
                mapOf("type" to "candidate-pair", "nominated" to true, "state" to "failed"),
            ),
        )!!
        assertEquals(10L, sample.bytesSent)
        assertEquals(0L, sample.framesEncoded)
        assertEquals(0.0, sample.framesPerSecond, 0.0)
        assertEquals(0, sample.frameWidth)
        assertEquals(0, sample.frameHeight)
        assertNull(sample.rttMs)
        assertNull(sample.encoderImplementation)
    }

    // ---- Phase16: per-frame encode cost + packetizer retransmits ----

    @Test
    fun `extracts totalEncodeTime framesSent and retransmittedPacketsSent when the prebuilt reports them`() {
        val sample = SenderStats.sampleVideoSend(
            listOf(
                mapOf(
                    "type" to "outbound-rtp",
                    "kind" to "video",
                    "bytesSent" to 750_000L,
                    "framesEncoded" to 900L,
                    "totalEncodeTime" to 4.5,
                    "framesSent" to 898L,
                    "retransmittedPacketsSent" to 12L,
                ),
            ),
        )!!
        assertEquals(4.5, sample.totalEncodeTimeSeconds!!, 1e-9)
        assertEquals(898L, sample.framesSent)
        assertEquals(12L, sample.retransmittedPacketsSent)
    }

    @Test
    fun `encode-cost fields are null when the prebuilt does not report them`() {
        val sample = SenderStats.sampleVideoSend(
            listOf(mapOf("type" to "outbound-rtp", "kind" to "video", "bytesSent" to 10L)),
        )!!
        assertNull(sample.totalEncodeTimeSeconds)
        assertNull(sample.framesSent)
        assertNull(sample.retransmittedPacketsSent)
    }

    // ---- mic pc (Phase8): bytesSent proves the mic is actually flowing ----

    @Test
    fun `extracts the audio outbound-rtp entry and the nominated pair rtt`() {
        val sample = SenderStats.sampleAudioSend(
            listOf(
                mapOf("type" to "outbound-rtp", "kind" to "video", "bytesSent" to 750_000L),
                mapOf("type" to "outbound-rtp", "kind" to "audio", "bytesSent" to 42_000L),
                mapOf("type" to "candidate-pair", "nominated" to true, "state" to "succeeded", "currentRoundTripTime" to 0.0042),
                mapOf("type" to "candidate-pair", "nominated" to false, "state" to "succeeded", "currentRoundTripTime" to 9.9),
            ),
        )!!
        assertEquals(42_000L, sample.bytesSent)
        assertEquals(4L, sample.rttMs)
    }

    @Test
    fun `audio sample is null while there is no audio outbound-rtp entry yet`() {
        assertNull(
            SenderStats.sampleAudioSend(
                listOf(
                    mapOf("type" to "outbound-rtp", "kind" to "video", "bytesSent" to 750_000L),
                    mapOf("type" to "inbound-rtp", "kind" to "audio"),
                ),
            ),
        )
    }

    @Test
    fun `audio sample degrades to zero bytes and null rtt when members are missing`() {
        val sample = SenderStats.sampleAudioSend(
            listOf(mapOf("type" to "outbound-rtp", "kind" to "audio")),
        )!!
        assertEquals(0L, sample.bytesSent)
        assertNull(sample.rttMs)
    }
}
