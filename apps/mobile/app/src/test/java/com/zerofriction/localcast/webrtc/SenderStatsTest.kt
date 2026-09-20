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
                rttMs = 1L,
                encoderImplementation = "OMX.qcom.video.encoder.avc",
            ),
            sample,
        )
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
        assertNull(sample.rttMs)
        assertNull(sample.encoderImplementation)
    }
}
