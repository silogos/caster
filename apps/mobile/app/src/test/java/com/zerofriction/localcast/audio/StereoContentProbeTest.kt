package com.zerofriction.localcast.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stereo-content probing is the diagnostic that separates "the platform
 * delivered mono content" from "the pipeline collapsed stereo" — behavior,
 * not implementation: what comes out of a known PCM stream.
 */
class StereoContentProbeTest {

    /** One interleaved PCM16 LE stereo sample pair. */
    private fun frame(leftSample: Int, rightSample: Int): ByteArray = byteArrayOf(
        (leftSample and 0xFF).toByte(), ((leftSample shr 8) and 0xFF).toByte(),
        (rightSample and 0xFF).toByte(), ((rightSample shr 8) and 0xFF).toByte(),
    )

    @Test
    fun `identical channels report zero difference`() {
        val probe = StereoContentProbe(framesPerReport = 2)
        assertNull(probe.onSamples(frame(1000, 1000)))
        val report = probe.onSamples(frame(-2000, -2000))!!.summarize()
        assertTrue(report.endsWith("(0% of amplitude)"))
        assertTrue(report.contains("mean|L-R|=0"))
    }

    @Test
    fun `hard-panned content reports a high difference`() {
        val probe = StereoContentProbe(framesPerReport = 2)
        assertNull(probe.onSamples(frame(3000, 0)))
        val report = probe.onSamples(frame(-3000, 0))!!.summarize()
        assertTrue(report.endsWith("(100% of amplitude)"))
    }

    @Test
    fun `silence reports zero without division by zero`() {
        val probe = StereoContentProbe(framesPerReport = 1)
        val report = probe.onSamples(frame(0, 0))!!.summarize()
        assertTrue(report.endsWith("(0% of amplitude)"))
    }

    @Test
    fun `partial stereo content lands between the bounds`() {
        val probe = StereoContentProbe(framesPerReport = 4)
        probe.onSamples(frame(1000, 900))
        probe.onSamples(frame(1000, 500))
        probe.onSamples(frame(-1000, -900))
        val report = probe.onSamples(frame(-1000, -500))!!.summarize()
        // mean|L|=1000, mean|R|=700, mean|L-R|=300 → 30%
        assertTrue(report.endsWith("(30% of amplitude)"))
    }

    @Test
    fun `the window resets after a report`() {
        val probe = StereoContentProbe(framesPerReport = 1)
        val first = probe.onSamples(frame(1000, 0))
        val second = probe.onSamples(frame(1000, 0))
        org.junit.Assert.assertNotNull(first)
        org.junit.Assert.assertNotNull(second)
    }

    @Test
    fun `odd trailing bytes are ignored`() {
        val probe = StereoContentProbe(framesPerReport = 1)
        val report = probe.onSamples(frame(1000, 0) + byteArrayOf(7))!!.summarize()
        assertTrue(report.endsWith("(100% of amplitude)"))
    }
}
