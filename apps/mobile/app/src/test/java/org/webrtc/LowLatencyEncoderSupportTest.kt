package org.webrtc

import android.media.MediaFormat
import com.zerofriction.localcast.webrtc.EncoderLatencyKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class LowLatencyEncoderSupportTest {

    /** A codec stand-in that records the format it was configured with. */
    private class RecordingCodec {
        var configured: MediaFormat? = null
        val calls = mutableListOf<String>()
        val wrapper: MediaCodecWrapper = Proxy.newProxyInstance(
            MediaCodecWrapper::class.java.classLoader,
            arrayOf(MediaCodecWrapper::class.java),
        ) { _, method, args ->
            calls += method.name
            when (method.name) {
                "configure" -> { configured = args[0] as MediaFormat; null }
                "getInputFormat" -> configured
                else -> if (method.returnType == Int::class.javaPrimitiveType) NO_BUFFER else null
            }
        } as MediaCodecWrapper
    }

    private companion object {
        const val NO_BUFFER = -1 // MediaCodec.INFO_TRY_AGAIN_LATER
    }

    private fun keyed(codec: RecordingCodec): MediaCodecWrapper =
        KeyedCodecWrapperFactory({ codec.wrapper }, EncoderLatencyKeys.hardware()).createByCodecName("c2.test.avc.encoder")

    @Test
    fun the_encoder_is_configured_with_the_low_latency_keys() {
        val codec = RecordingCodec()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 800)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)

        keyed(codec).configure(format, null, null, 1)

        assertNotNull(codec.configured)
        val seen = codec.configured!!
        assertEquals(1, seen.getInteger(MediaFormat.KEY_LATENCY))
        assertEquals(0, seen.getInteger(MediaFormat.KEY_PRIORITY))
        // libwebrtc's own settings pass through untouched.
        assertEquals(6_000_000, seen.getInteger(MediaFormat.KEY_BIT_RATE))
    }

    @Test
    fun every_other_codec_call_reaches_the_real_codec() {
        val codec = RecordingCodec()
        val wrapper = keyed(codec)
        wrapper.start()
        wrapper.dequeueOutputBuffer(android.media.MediaCodec.BufferInfo(), 0)
        wrapper.release()
        assertEquals(listOf("start", "dequeueOutputBuffer", "release"), codec.calls)
    }

    /** Mirror guard: the pinned prebuilt still has the field the patch replaces. */
    @Test
    fun the_pinned_encoder_still_takes_its_codec_from_the_patched_field() {
        val field = HardwareVideoEncoder::class.java.getDeclaredField(CODEC_FACTORY_FIELD)
        assertSame(MediaCodecWrapperFactory::class.java, field.type)
    }
}
