package io.github.silogos.caster.usb

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The screen → H.264 half of a USB cast (ADR-034): a MediaCodec surface
 * encoder fed by a VirtualDisplay, its access units handed out as they leave
 * the codec. libwebrtc owns the encoder on Wi-Fi; over the cable there is no
 * WebRTC, so this drives MediaCodec directly with the ADR-018 latency keys.
 */
class UsbVideoEncoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val densityDpi: Int,
    /** The profile's Encoder choice: a software H.264 encoder instead of the hardware one. */
    private val software: Boolean = false,
    /** The profile's H.264 profile: High, or Baseline (the lightest per-frame work). */
    private val highProfile: Boolean = true,
    /** One access unit; `flags` uses [UsbFrame.FLAG_KEYFRAME] / [UsbFrame.FLAG_CONFIG]. False = stop. */
    private val onAccessUnit: (flags: Int, ptsUs: Long, data: ByteArray) -> Boolean,
    private val onStopped: (reason: String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    @Volatile private var running = false

    /** The codec's name once started (logs, the Status readout). */
    var codecName: String? = null
        private set

    /** Throws IOException / IllegalStateException when no encoder accepts the format. */
    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // KEY_FRAME_RATE is only a rate-control hint: a 144 Hz panel fed
            // the encoder every frame (median 90, max 144 fps measured on the
            // TB321FU, 2026-11-17). This is the cap: frames above the
            // profile's rate are dropped before encoding (surface input only).
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
            // A still screen sends no frames; repeat the last one so the
            // receiver's picture never looks stalled (and its NO INPUT stays off).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            // ADR-018: realtime priority, no frames held inside the codec.
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(
                MediaFormat.KEY_PROFILE,
                if (highProfile) MediaCodecInfo.CodecProfileLevel.AVCProfileHigh else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
        }
        val c = pickEncoder(format)
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: RuntimeException) {
            c.release()
            throw IOException("encoder refused ${width}x$height@$fps: ${e.message}", e)
        }
        val surface = c.createInputSurface()
        c.start()
        codec = c
        codecName = c.name
        running = true
        display = projection.createVirtualDisplay(
            "caster-usb", width, height, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
        )
        Log.i(TAG, "encoder ${c.name} ${width}x$height @$fps fps, ${bitrateBps / 1000} kbps")
        thread(name = "usb-video-drain") { drain(c) }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var reason = "stopped"
        try {
            while (running) {
                val index = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index < 0) continue
                val buffer = c.getOutputBuffer(index)
                if (buffer == null || info.size == 0) {
                    c.releaseOutputBuffer(index, false)
                    continue
                }
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                c.releaseOutputBuffer(index, false)
                var flags = 0
                if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or UsbFrame.FLAG_KEYFRAME
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or UsbFrame.FLAG_CONFIG
                if (!onAccessUnit(flags, info.presentationTimeUs, data)) {
                    reason = "link gone"
                    break
                }
            }
        } catch (e: IllegalStateException) {
            reason = "codec error: ${e.message}"
        }
        if (running) {
            Log.w(TAG, "encoder loop ended: $reason")
            stop()
            onStopped(reason)
        }
    }

    /**
     * The profile's Encoder choice: the first software (or hardware) H.264
     * encoder that takes this format; the platform's default when none does,
     * so a choice the phone can't honor never fails the cast.
     */
    private fun pickEncoder(format: MediaFormat): MediaCodec {
        val name = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes.map(String::lowercase) }
            .firstOrNull { it.isSoftwareOnly == software && it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format) }
            ?.name
        if (name == null) Log.w(TAG, "no ${if (software) "software" else "hardware"} H.264 encoder takes this format — the default one")
        return if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    fun stop() {
        if (!running && codec == null) return
        running = false
        runCatching { display?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        display = null
        codec = null
    }

    private companion object {
        const val TAG = "UsbVideo"
        const val KEYFRAME_INTERVAL_S = 2
        const val REPEAT_AFTER_US = 100_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
