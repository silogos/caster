package com.zerofriction.localcast.webrtc

import android.os.SystemClock
import android.util.Log
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame

/*
 * Recovery from a video encoder that stopped answering (ADR-022).
 *
 * Found live 2026-11-10: 37 minutes into a cast, on a tablet at thermal
 * status SEVERE, the hardware encoder stopped emitting frames. libwebrtc's
 * MediaCodec encoder holds at most three frames waiting for output; with
 * three waiting it refuses every new frame ("Dropped frame, encoder queue
 * full"), so the codec gets no more input and never answers again. The
 * picture froze for good while the sound carried on, until the cast was
 * restarted by hand.
 *
 * The signature is exact: the encoder refuses frames and emits nothing. A
 * slow encoder also refuses frames, but it keeps emitting some. So an encoder
 * that has refused frames for [EncoderStallWatch.STALL_TIMEOUT_MS] without one
 * frame out is released and initialized again, with the settings and rates
 * libwebrtc last gave it.
 */

private const val TAG = "EncoderStall"

/**
 * The policy: when is an encoder stalled, and what to do about it. Frames
 * arrive on the encoder thread and output on the codec's output thread, so
 * every entry point is synchronized.
 */
class EncoderStallWatch {

    enum class Verdict {
        /** Answering, or not refusing for long enough to tell. */
        HEALTHY,

        /** Stalled: release the encoder and initialize it again. */
        RECREATE,

        /**
         * Re-creating has not helped: ask libwebrtc for its software encoder.
         * It has one for VP8, VP9 and AV1. It has none for H.264, and there
         * the request changes nothing: the watch starts over and keeps
         * re-creating the codec (seen live 2026-11-10).
         */
        FALL_BACK,
    }

    private var refusedSinceMs: Long? = null
    private var recoveriesWithoutOutput = 0

    /** The encoder emitted a frame. */
    @Synchronized
    fun onOutput() {
        refusedSinceMs = null
        recoveriesWithoutOutput = 0
    }

    /** The encoder was handed a frame at [nowMs] and took it, or [refused] it. */
    @Synchronized
    fun onFrame(nowMs: Long, refused: Boolean): Verdict {
        if (!refused) return Verdict.HEALTHY
        val since = refusedSinceMs ?: nowMs.also { refusedSinceMs = it }
        if (nowMs - since < STALL_TIMEOUT_MS) return Verdict.HEALTHY
        refusedSinceMs = null
        if (recoveriesWithoutOutput >= MAX_RECOVERIES_WITHOUT_OUTPUT) {
            recoveriesWithoutOutput = 0
            return Verdict.FALL_BACK
        }
        recoveriesWithoutOutput++
        return Verdict.RECREATE
    }

    /** How many times in a row the encoder was re-created without a frame out. */
    @get:Synchronized
    val recoveries: Int get() = recoveriesWithoutOutput

    companion object {
        /**
         * Refusing frames this long with nothing out is a stall. A hot,
         * throttled encoder was seen crawling at 1–3 frames a second just
         * before it stalled; its gaps stay well under this.
         */
        const val STALL_TIMEOUT_MS = 3_000L

        /** After this many re-creations in a row without a frame out, ask for the software encoder. */
        const val MAX_RECOVERIES_WITHOUT_OUTPUT = 3
    }
}

/**
 * A video encoder that re-creates [inner] when it stalls. Everything else
 * passes through untouched, so libwebrtc sees the same encoder.
 */
class StallRecoveringEncoder(
    private val inner: VideoEncoder,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : VideoEncoder {

    private val watch = EncoderStallWatch()

    // Encoder-thread state: what libwebrtc last configured, to configure a
    // re-created encoder the same way.
    private var settings: VideoEncoder.Settings? = null
    private var output: VideoEncoder.Callback? = null
    private var rates: VideoEncoder.RateControlParameters? = null

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        this.settings = settings
        val watched = VideoEncoder.Callback { image, info ->
            watch.onOutput()
            callback.onEncodedFrame(image, info)
        }
        output = watched
        return inner.initEncode(settings, watched)
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val status = inner.encode(frame, info)
        return when (watch.onFrame(nowMs(), refused = status == VideoCodecStatus.NO_OUTPUT)) {
            EncoderStallWatch.Verdict.HEALTHY -> status
            EncoderStallWatch.Verdict.RECREATE -> recreateAndEncode(frame)
            EncoderStallWatch.Verdict.FALL_BACK -> {
                Log.e(
                    TAG,
                    "video encoder ${inner.implementationName} still silent after ${EncoderStallWatch.MAX_RECOVERIES_WITHOUT_OUTPUT} re-creations — " +
                        "asking libwebrtc for a software encoder (it has none for H.264; re-creating continues)",
                )
                VideoCodecStatus.FALLBACK_SOFTWARE
            }
        }
    }

    private fun recreateAndEncode(frame: VideoFrame): VideoCodecStatus {
        val settings = settings
        val output = output
        if (settings == null || output == null) return VideoCodecStatus.UNINITIALIZED
        Log.w(
            TAG,
            "video encoder ${inner.implementationName} emitted nothing for ${EncoderStallWatch.STALL_TIMEOUT_MS} ms " +
                "while refusing frames — re-creating it (attempt ${watch.recoveries})",
        )
        val released = inner.release()
        val initialized = inner.initEncode(settings, output)
        if (initialized != VideoCodecStatus.OK) {
            Log.e(TAG, "re-creating the video encoder failed (release $released, init $initialized) — asking libwebrtc for a software encoder")
            return VideoCodecStatus.FALLBACK_SOFTWARE
        }
        rates?.let { inner.setRates(it) }
        Log.i(TAG, "video encoder re-created (release $released)")
        // The receiver's decoder has lost its reference frames: start clean.
        return inner.encode(frame, KEY_FRAME)
    }

    override fun release(): VideoCodecStatus = inner.release()

    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus {
        rates = VideoEncoder.RateControlParameters(allocation, framerate.toDouble())
        return inner.setRateAllocation(allocation, framerate)
    }

    override fun setRates(parameters: VideoEncoder.RateControlParameters): VideoCodecStatus {
        rates = parameters
        return inner.setRates(parameters)
    }

    override fun createNative(webrtcEnvRef: Long): Long = inner.createNative(webrtcEnvRef)
    override fun isHardwareEncoder(): Boolean = inner.isHardwareEncoder
    override fun getScalingSettings(): VideoEncoder.ScalingSettings = inner.scalingSettings
    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = inner.resolutionBitrateLimits
    override fun getImplementationName(): String = inner.implementationName
    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = inner.encoderInfo

    private companion object {
        val KEY_FRAME = VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameKey))
    }
}

/** Wraps a MediaCodec encoder factory so each encoder it creates recovers from a stall. */
class StallRecoveringEncoderFactory(private val inner: VideoEncoderFactory) : VideoEncoderFactory {

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? =
        inner.createEncoder(info)?.let { StallRecoveringEncoder(it) }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = inner.supportedCodecs
}
