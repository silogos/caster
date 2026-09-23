package com.zerofriction.localcast.webrtc

import android.media.MediaCodecInfo
import com.zerofriction.localcast.config.CastConfig
import com.zerofriction.localcast.config.EncoderImplementation
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.Predicate
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory

/**
 * Encoder-policy knob from the config module (designs/mobile-app.html,
 * Advanced → Encoder). [EncoderImplementation.HARDWARE] is today's behavior
 * verbatim — [DefaultVideoEncoderFactory]: the SoC's video hardware first,
 * libwebrtc's software encoders as the fallback. [EncoderImplementation.SOFTWARE]
 * restricts the MediaCodec path to software codecs
 * (OMX.google/c2.android — `!isHardwareAccelerated`) so the SoC's video
 * hardware stays free for a game (risk-register R5: the device-specific
 * escape hatch when a hardware encoder competes with gameplay), with
 * libwebrtc's pure-software factory behind it for anything MediaCodec lacks.
 *
 * The `enableH264HighProfile` flag rides along in both modes (Advanced →
 * H.264 profile): libwebrtc only offers the High-profile codec entry when it
 * is on, so an offer simply stops advertising High.
 */
object CastEncoderFactory {

    fun create(eglContext: EglBase.Context, config: CastConfig): VideoEncoderFactory =
        when (config.encoderImpl) {
            EncoderImplementation.HARDWARE ->
                DefaultVideoEncoderFactory(
                    eglContext,
                    /* enableIntelVp8Encoder = */ true,
                    /* enableH264HighProfile = */ config.h264HighProfile,
                )

            EncoderImplementation.SOFTWARE ->
                ChainedEncoderFactory(
                    primary = HardwareVideoEncoderFactory(
                        eglContext,
                        /* enableIntelVp8Encoder = */ true,
                        /* enableH264HighProfile = */ config.h264HighProfile,
                        softwareOnlyPredicate(),
                    ),
                    fallback = SoftwareVideoEncoderFactory(),
                )
        }

    /** Only MediaCodec's software encoders may back the "Software" setting. */
    private fun softwareOnlyPredicate(): Predicate<MediaCodecInfo> =
        Predicate { info: MediaCodecInfo -> !info.isHardwareAccelerated }
}

/**
 * A primary factory with a fallback behind it — the composition
 * [DefaultVideoEncoderFactory] itself uses internally, factored out so the
 * Software setting can chain its own pair. Codec support is the union; the
 * encoder comes from the first factory that can build it.
 */
class ChainedEncoderFactory(
    private val primary: VideoEncoderFactory,
    private val fallback: VideoEncoderFactory,
) : VideoEncoderFactory {

    override fun createEncoder(codecType: VideoCodecInfo): VideoEncoder? =
        primary.createEncoder(codecType) ?: fallback.createEncoder(codecType)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        (primary.supportedCodecs.toList() + fallback.supportedCodecs.toList())
            .distinct()
            .toTypedArray()
}
