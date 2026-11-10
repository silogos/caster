package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

/**
 * A stalled video encoder is re-created (ADR-022). The stand-in encoder
 * behaves like libwebrtc's MediaCodec encoder as seen live: it holds up to
 * three frames waiting for output and refuses every frame after that.
 */
class EncoderStallRecoveryTest {

    private class FakeEncoder : VideoEncoder {
        /** False: every frame comes out at once. True: the codec is silent. */
        var silent = false

        /** Whether a codec made by the next `initEncode` is silent too. */
        var freshCodecIsSilent = false
        var initStatus = VideoCodecStatus.OK

        val inits = mutableListOf<VideoEncoder.Settings>()
        var releases = 0
        val keyFrameRequests = mutableListOf<Boolean>()
        val ratesSeen = mutableListOf<VideoEncoder.RateControlParameters>()
        private var callback: VideoEncoder.Callback? = null
        private var waiting = 0

        override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
            inits += settings
            this.callback = callback
            waiting = 0
            silent = freshCodecIsSilent
            return initStatus
        }

        override fun release(): VideoCodecStatus {
            releases++
            waiting = 0
            return VideoCodecStatus.OK
        }

        override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
            if (!silent) {
                keyFrameRequests += info.frameTypes.contains(EncodedImage.FrameType.VideoFrameKey)
                callback!!.onEncodedFrame(ENCODED, VideoEncoder.CodecSpecificInfo())
                return VideoCodecStatus.OK
            }
            if (waiting >= HELD_FRAMES) return VideoCodecStatus.NO_OUTPUT
            waiting++
            return VideoCodecStatus.OK
        }

        override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int) = VideoCodecStatus.OK

        override fun setRates(parameters: VideoEncoder.RateControlParameters): VideoCodecStatus {
            ratesSeen += parameters
            return VideoCodecStatus.OK
        }

        override fun getScalingSettings(): VideoEncoder.ScalingSettings = VideoEncoder.ScalingSettings.OFF
        override fun getImplementationName() = "c2.test.avc.encoder"
    }

    /** The encoder under test, driven by a 60 fps frame clock. */
    private class Harness {
        val codec = FakeEncoder()
        var nowMs = 0L
        val encoder = StallRecoveringEncoder(codec) { nowMs }
        var framesOut = 0
        val statuses = mutableListOf<VideoCodecStatus>()

        init {
            assertEquals(VideoCodecStatus.OK, encoder.initEncode(SETTINGS) { _, _ -> framesOut++ })
            encoder.setRates(RATES)
        }

        /** Feeds frames for [ms] milliseconds, one every [everyMs]. */
        fun cast(ms: Long, everyMs: Long = FRAME_MS) {
            val end = nowMs + ms
            while (nowMs < end) {
                statuses += encoder.encode(FRAME, DELTA)
                nowMs += everyMs
            }
        }
    }

    @Test
    fun `a healthy encoder is left alone`() {
        val h = Harness()
        h.cast(60_000)
        assertEquals(0, h.codec.releases)
        assertEquals(1, h.codec.inits.size)
        assertTrue(h.statuses.all { it == VideoCodecStatus.OK })
    }

    @Test
    fun `an encoder that falls behind for a moment is not re-created`() {
        val h = Harness()
        h.codec.silent = true
        h.cast(EncoderStallWatch.STALL_TIMEOUT_MS - FRAME_MS) // refusing, just short of a stall
        h.codec.silent = false
        h.cast(1_000) // frames come out again, by itself
        h.codec.silent = true
        h.cast(EncoderStallWatch.STALL_TIMEOUT_MS - FRAME_MS) // behind again: the clock started over
        assertEquals(0, h.codec.releases)
    }

    @Test
    fun `a quiet screen is not a stall`() {
        val h = Harness()
        // A still screen: one frame every ten seconds, each answered.
        h.cast(60_000, everyMs = 10_000)
        assertEquals(0, h.codec.releases)
    }

    @Test
    fun `an encoder that stops answering is re-created the way libwebrtc configured it`() {
        val h = Harness()
        h.cast(5_000)
        val outBeforeStall = h.framesOut

        h.codec.silent = true // stays silent until it is re-created
        h.cast(EncoderStallWatch.STALL_TIMEOUT_MS - FRAME_MS)
        assertEquals("not yet: it may only be behind", 0, h.codec.releases)
        assertEquals(outBeforeStall, h.framesOut)

        h.cast(2_000)

        assertEquals(1, h.codec.releases)
        assertEquals(2, h.codec.inits.size)
        assertSame(SETTINGS, h.codec.inits.last())
        assertSame("the rates libwebrtc last set", RATES, h.codec.ratesSeen.last())
        assertTrue("the picture flows again", h.framesOut > outBeforeStall)
    }

    @Test
    fun `the first frame after a recovery is a key frame`() {
        val h = Harness()
        h.cast(1_000)
        h.codec.keyFrameRequests.clear()
        h.codec.silent = true
        h.cast(EncoderStallWatch.STALL_TIMEOUT_MS + 1_000)
        assertEquals(1, h.codec.releases)
        assertEquals(true, h.codec.keyFrameRequests.first())
        assertTrue("only the first", h.codec.keyFrameRequests.drop(1).none { it })
    }

    @Test
    fun `an encoder that cannot be re-created asks for the software encoder`() {
        val h = Harness()
        h.codec.silent = true
        h.codec.initStatus = VideoCodecStatus.ERROR
        h.cast(EncoderStallWatch.STALL_TIMEOUT_MS + 1_000)
        assertTrue(VideoCodecStatus.FALLBACK_SOFTWARE in h.statuses)
    }

    @Test
    fun `an encoder that stays silent through every re-creation asks for the software encoder`() {
        val h = Harness()
        h.codec.silent = true
        h.codec.freshCodecIsSilent = true
        // Each fresh codec takes its three frames, then refuses for a timeout.
        val perAttempt = EncoderStallWatch.STALL_TIMEOUT_MS + 1_000
        h.cast(perAttempt * EncoderStallWatch.MAX_RECOVERIES_WITHOUT_OUTPUT)
        assertEquals(EncoderStallWatch.MAX_RECOVERIES_WITHOUT_OUTPUT, h.codec.releases)
        assertTrue("still trying", VideoCodecStatus.FALLBACK_SOFTWARE !in h.statuses)

        h.cast(1_000)
        assertTrue(VideoCodecStatus.FALLBACK_SOFTWARE in h.statuses)
        assertEquals("no further re-creation before asking", EncoderStallWatch.MAX_RECOVERIES_WITHOUT_OUTPUT, h.codec.releases)
    }

    private companion object {
        const val FRAME_MS = 16L

        /** libwebrtc's MediaCodec encoder refuses the fourth waiting frame. */
        const val HELD_FRAMES = 3

        val SETTINGS = VideoEncoder.Settings(
            /* numberOfCores = */ 8,
            /* width = */ 1920,
            /* height = */ 1200,
            /* startBitrate = */ 8_000,
            /* maxFramerate = */ 60,
            /* numberOfSimulcastStreams = */ 1,
            /* automaticResizeOn = */ false,
        )
        val RATES = VideoEncoder.RateControlParameters(VideoEncoder.BitrateAllocation(arrayOf(intArrayOf(10_000_000))), 60.0)
        val DELTA = VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameDelta))

        val FRAME = VideoFrame(
            object : VideoFrame.Buffer {
                override fun getWidth() = 1920
                override fun getHeight() = 1200
                override fun toI420(): VideoFrame.I420Buffer = error("not read")
                override fun retain() = Unit
                override fun release() = Unit
                override fun cropAndScale(cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int): VideoFrame.Buffer = this
            },
            /* rotation = */ 0,
            /* timestampNs = */ 0L,
        )

        val ENCODED: EncodedImage = EncodedImage.builder()
            .setBuffer(ByteBuffer.allocateDirect(1), null)
            .setFrameType(EncodedImage.FrameType.VideoFrameDelta)
            .createEncodedImage()
    }
}
