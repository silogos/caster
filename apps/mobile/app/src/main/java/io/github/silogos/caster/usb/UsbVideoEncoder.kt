package io.github.silogos.caster.usb

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The screen → H.264 half of a USB cast (ADR-034): a MediaCodec surface
 * encoder fed by a VirtualDisplay, its access units handed out as they leave
 * the codec. libwebrtc owns the encoder on Wi-Fi; over the cable there is no
 * WebRTC, so this drives MediaCodec directly with the ADR-018 latency keys.
 *
 * Ownership: after [start], the drain thread alone touches the codec, and it
 * stops and releases it on its way out. [stop] only ends the picture and asks
 * the thread to finish, so the codec is never released under a call in flight.
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
    /** The profile's H.264 profile: High, or Baseline. */
    private val highProfile: Boolean = true,
    /**
     * One access unit as a whole link frame ([UsbFrame.framed], kind video,
     * flags [UsbFrame.FLAG_KEYFRAME] / [UsbFrame.FLAG_CONFIG]). False = stop.
     */
    private val onFrame: (frame: ByteArray) -> Boolean,
    /** The encoder ended on its own (link gone, codec error); called at most once, never after [stop]. */
    private val onStopped: (reason: String) -> Unit,
) {
    /** True from [start] until the first of [stop] or the drain loop ending on its own claims the end. */
    private val running = AtomicBoolean(false)
    @Volatile private var display: VirtualDisplay? = null
    private var drainThread: Thread? = null

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
            // No B-frames: every frame is decodable as it arrives. Android's
            // default is already 0; stated so no vendor default can differ.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            // A still screen sends no frames; repeat the last one so the
            // receiver's picture never looks stalled (and its NO INPUT stays off).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            // ADR-018: ask for realtime priority and a one-frame latency. Both
            // are hints the codec may ignore; the output format logged below
            // says what it took.
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(
                MediaFormat.KEY_PROFILE,
                if (highProfile) MediaCodecInfo.CodecProfileLevel.AVCProfileHigh else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
        }
        val c = pickEncoder(format)
        val surface = try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.createInputSurface().also { s ->
                try {
                    c.start()
                } catch (e: RuntimeException) {
                    s.release()
                    throw e
                }
            }
        } catch (e: RuntimeException) {
            c.release()
            throw IOException("encoder refused ${width}x$height@$fps: ${e.message}", e)
        }
        codecName = c.name
        val vd = try {
            projection.createVirtualDisplay(
                "caster-usb", width, height, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
            )
        } catch (e: RuntimeException) {
            runCatching { c.stop() }
            c.release()
            surface.release()
            throw e
        }
        display = vd
        running.set(true)
        val kind = if (c.codecInfo.isSoftwareOnly) "software" else "hardware"
        Log.i(TAG, "encoder ${c.name} ($kind) ${width}x$height @$fps fps, ${bitrateBps / 1000} kbps")
        drainThread = thread(name = "usb-video-drain") { drain(c, surface) }
    }

    private fun drain(c: MediaCodec, surface: Surface) {
        val info = MediaCodec.BufferInfo()
        var reason = "stopped"
        try {
            while (running.get()) {
                val index = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // What the codec actually runs with (profile, level,
                    // latency, …), not what was asked for.
                    Log.i(TAG, "encoder output format: ${c.outputFormat}")
                    continue
                }
                // INFO_TRY_AGAIN_LATER, or the deprecated INFO_OUTPUT_BUFFERS_CHANGED
                // (getOutputBuffer needs nothing from it).
                if (index < 0) continue
                if (!handOut(c, index, info)) {
                    reason = "link gone"
                    break
                }
            }
        } catch (e: IllegalStateException) {
            reason = "codec error: ${e.message}"
        }
        // Whoever flips `running` first owns the end: here only when the
        // loop ended on its own, otherwise stop() already did.
        val endedOnItsOwn = running.getAndSet(false)
        if (endedOnItsOwn) releaseDisplay()
        runCatching { c.stop() }
        runCatching { c.release() }
        surface.release()
        if (endedOnItsOwn) {
            Log.w(TAG, "encoder loop ended: $reason")
            onStopped(reason)
        } else {
            Log.i(TAG, "encoder released")
        }
    }

    /** Copies one output buffer straight into a link frame and gives the buffer back before sending. */
    private fun handOut(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo): Boolean {
        val buffer = c.getOutputBuffer(index)
        if (buffer == null || info.size == 0) {
            c.releaseOutputBuffer(index, false)
            return true
        }
        var flags = 0
        if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or UsbFrame.FLAG_KEYFRAME
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or UsbFrame.FLAG_CONFIG
        val frame = UsbFrame.framed(UsbFrame.KIND_VIDEO, flags, info.presentationTimeUs, info.size)
        buffer.position(info.offset)
        buffer.get(frame, UsbFrame.HEADER_LEN, info.size)
        c.releaseOutputBuffer(index, false)
        return onFrame(frame)
    }

    /**
     * The profile's Encoder choice: the first software (or hardware) H.264
     * encoder that takes this format; the platform's default when none does,
     * so a choice the phone can't honor never fails the cast. Every candidate
     * and the reason for a fallback are logged.
     */
    private fun pickEncoder(format: MediaFormat): MediaCodec {
        val wanted = if (software) "software" else "hardware"
        var chosen: String? = null
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes.map(String::lowercase) }
        for (info in candidates) {
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val withinRates = caps.videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
            val accepts = caps.isFormatSupported(format)
            Log.i(
                TAG,
                "candidate ${info.name}: ${if (info.isSoftwareOnly) "software" else "hardware"}, " +
                    "${width}x$height@$fps ${if (withinRates) "within" else "beyond"} its advertised rates, " +
                    "format ${if (accepts) "accepted" else "refused"}",
            )
            if (chosen == null && info.isSoftwareOnly == software && accepts) chosen = info.name
        }
        chosen?.let { return MediaCodec.createByCodecName(it) }
        val fallback = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val fallbackKind = if (fallback.codecInfo.isSoftwareOnly) "software" else "hardware"
        Log.w(TAG, "no $wanted H.264 encoder accepts ${width}x$height@$fps — falling back to the platform default ${fallback.name} ($fallbackKind)")
        return fallback
    }

    /**
     * Ends the cast's picture and lets the drain thread release the codec.
     * Waits for it at most [STOP_WAIT_MS] (called on the main thread); a
     * thread stuck in a USB write still releases the codec when it returns.
     */
    fun stop() {
        if (!running.getAndSet(false)) return
        releaseDisplay()
        val t = drainThread ?: return
        if (t !== Thread.currentThread()) {
            t.join(STOP_WAIT_MS)
            if (t.isAlive) Log.w(TAG, "drain thread still busy after $STOP_WAIT_MS ms; it releases the codec when it returns")
        }
    }

    private fun releaseDisplay() {
        display?.let { runCatching { it.release() } }
        display = null
    }

    private companion object {
        const val TAG = "UsbVideo"
        const val KEYFRAME_INTERVAL_S = 2
        const val REPEAT_AFTER_US = 100_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val STOP_WAIT_MS = 250L
    }
}
