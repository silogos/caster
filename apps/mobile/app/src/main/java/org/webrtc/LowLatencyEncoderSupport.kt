package org.webrtc

import android.media.MediaCrypto
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/*
 * Low-latency MediaCodec keys for libwebrtc's hardware encoder (ADR-018).
 *
 * The pinned prebuilt's HardwareVideoEncoder builds its own MediaFormat and
 * offers no hook for extra keys. Its codec comes from a package-private
 * MediaCodecWrapperFactory field, so this file lives in package org.webrtc
 * (same class loader as the AAR) to wrap that factory: every codec it makes
 * gets the extra keys added just before configure(). Everything else
 * delegates untouched. Reflection touches one field of a pinned class; if
 * the field is gone (library changed) the encoder runs as stock and says so.
 */

private const val TAG = "LowLatencyEncoder"
internal const val CODEC_FACTORY_FIELD = "mediaCodecWrapperFactory"

/** Wraps a hardware encoder factory so each encoder it creates configures with [keys]. */
class LowLatencyHardwareEncoderFactory(
    private val inner: VideoEncoderFactory,
    private val keys: Map<String, Int>,
) : VideoEncoderFactory {

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        val encoder = inner.createEncoder(info) ?: return null
        if (encoder is HardwareVideoEncoder && keys.isNotEmpty()) patch(encoder)
        return encoder
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = inner.supportedCodecs

    private fun patch(encoder: HardwareVideoEncoder) {
        try {
            val field = HardwareVideoEncoder::class.java.getDeclaredField(CODEC_FACTORY_FIELD)
            field.isAccessible = true
            val original = field.get(encoder) as MediaCodecWrapperFactory
            field.set(encoder, KeyedCodecWrapperFactory(original, keys))
        } catch (t: Throwable) {
            Log.w(TAG, "could not add low-latency keys (library changed?) — stock encoder: ${t.message}")
        }
    }
}

/** libwebrtc's default composition (hardware first, software fallback) around [hardware]. */
fun defaultEncoderFactoryAround(hardware: VideoEncoderFactory): VideoEncoderFactory =
    DefaultVideoEncoderFactory(hardware)

internal class KeyedCodecWrapperFactory(
    private val delegate: MediaCodecWrapperFactory,
    private val keys: Map<String, Int>,
) : MediaCodecWrapperFactory {
    override fun createByCodecName(name: String): MediaCodecWrapper =
        KeyedCodecWrapper(delegate.createByCodecName(name), keys, name)
}

private class KeyedCodecWrapper(
    private val delegate: MediaCodecWrapper,
    private val keys: Map<String, Int>,
    private val name: String,
) : MediaCodecWrapper by delegate {
    override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        for ((key, value) in keys) format.setInteger(key, value)
        delegate.configure(format, surface, crypto, flags)
        // Unsupported keys are ignored by the codec, not refused: report what stuck.
        val applied = keys.keys.associateWith { key ->
            runCatching { delegate.inputFormat.takeIf { it.containsKey(key) }?.getInteger(key) }.getOrNull()
        }
        Log.i(TAG, "$name configured with $keys; codec input format reports $applied")
    }

}
