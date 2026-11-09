package com.zerofriction.localcast.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-013: the game-audio AAC-LC encoder — the phone's own MediaCodec,
 * configured exactly like TikTok's production cast (teardown `AudioEncoder`:
 * `audio/mp4a-latm`, AAC-LC, 192 kbps CBR, 48 kHz stereo). A synchronous
 * codec driven on a dedicated thread: the ADM's recording thread only
 * enqueues (bounded drop-oldest — it may never wait), the encoder thread
 * slices 1024-sample access units ([PcmUnitAccumulator]), drains output,
 * wraps every frame in ADTS ([AdtsHeader]) and hands it to [onFrame].
 *
 * Any codec failure funnels to [onError] exactly once — the session degrades
 * to the ADR-012 PCM passthrough, never a crash (the same degrade ladder the
 * record substitution and the capture source use). The csd-0 output is
 * dropped like the reference's `handleOutputBuffer` drops it: the ADTS
 * header carries everything the decoder needs.
 *
 * Numbers only in the logs — never the samples (AGENTS.md logging).
 */
class GameAacEncoder(
    private val sampleRateHz: Int,
    private val channels: Int,
    private val bitrateBps: Int,
    /** Called on the encoder thread with one complete ADTS packet. */
    private val onFrame: (ByteArray) -> Unit,
    /** Called once, on the encoder thread, when the codec dies. */
    private val onError: () -> Unit,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CHUNKS)
    private val accumulator = PcmUnitAccumulator(
        unitFrames = AAC_FRAMES_PER_UNIT,
        bytesPerFrame = channels * PCM16_BYTES_PER_SAMPLE,
    )
    private val closed = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile private var codec: MediaCodec? = null
    private var framesEncoded = 0L
    private var droppedByOverflow = 0L
    private var droppedInputs = 0L
    /** Sample-clock PTS for queueInputBuffer (monotonic; the wire carries none). */
    private var totalFramesQueued = 0L

    /** Configures and starts the codec; all lifecycle runs on the encoder thread. */
    fun start() {
        val newThread = Thread { runEncoder() }.apply {
            name = "game-audio-aac"
            start()
        }
        thread = newThread
    }

    /** Called on the ADM's recording thread — enqueue only, never block. */
    fun queuePcm(chunk: ByteArray) {
        if (closed.get()) return
        val copy = chunk.copyOf() // the producer's buffer is reused downstream
        if (!queue.offer(copy)) {
            queue.poll()
            if (!queue.offer(copy)) return
            droppedByOverflow++
        }
    }

    /** Stops the thread and releases the codec; safe to call from teardown. */
    fun release() {
        if (!closed.compareAndSet(false, true)) return
        thread?.interrupt()
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
        Log.i(
            TAG,
            "aac encoder released — frames=$framesEncoded dropped=" +
                "$droppedByOverflow (queue overflow) / $droppedInputs (no input buffer)",
        )
    }

    private fun runEncoder() {
        var localCodec: MediaCodec? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, accumulator.bytesPerUnit)
            }
            val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            localCodec = created
            codec = created
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            Log.i(TAG, "aac encoder up — AAC-LC $bitrateBps bps CBR, ${sampleRateHz} Hz x$channels")
            while (!closed.get()) {
                val chunk = try {
                    queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    return
                }
                accumulator.push(chunk) { unit -> feed(created, unit) }
                drain(created)
            }
        } catch (t: Throwable) {
            if (!closed.get()) {
                Log.e(TAG, "aac encoder failed — the cast falls back to raw PCM", t)
                onError()
            }
        } finally {
            codec = null
            localCodec?.let { c ->
                try {
                    c.stop()
                } catch (_: Throwable) {
                    // A failed codec may refuse stop — release is the one that matters.
                }
                try {
                    c.release()
                } catch (t: Throwable) {
                    Log.d(TAG, "aac codec release: ${t.message}")
                }
            }
        }
    }

    private fun feed(codec: MediaCodec, unit: ByteArray) {
        val index = codec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
        if (index < 0) {
            droppedInputs++
            return // drop the unit, never block the loop
        }
        codec.getInputBuffer(index)?.let { buffer ->
            buffer.clear()
            buffer.put(unit)
            val ptsUs = totalFramesQueued * 1_000_000L / sampleRateHz
            codec.queueInputBuffer(index, 0, unit.size, ptsUs, 0)
            totalFramesQueued += accumulator.bytesPerUnit / (channels * PCM16_BYTES_PER_SAMPLE)
        }
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "aac output format: ${codec.outputFormat}")
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index >= 0 -> {
                    // csd-0 (codec config) is dropped — ADTS carries the config.
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer !== null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val raw = ByteArray(info.size)
                            buffer.get(raw)
                            onFrame(AdtsHeader.wrap(raw, sampleRateHz, channels))
                            framesEncoded++
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                else -> return
            }
        }
    }

    companion object {
        private const val TAG = "GameAacEncoder"

        /**
         * The encoder input is the pre-opened capture record's contract
         * (audio.md: pinned 48 kHz stereo PCM16) — change these together
         * with that pin or every unit is garbage.
         */
        const val CAPTURE_SAMPLE_RATE_HZ = 48_000
        const val CAPTURE_CHANNELS = 2

        /** PCM16 — the capture format (audio.md). */
        const val PCM16_BYTES_PER_SAMPLE = 2

        /** AAC-LC consumes fixed 1024-sample access units. */
        const val AAC_FRAMES_PER_UNIT = 1024

        /** TikTok's production profile (teardown ByteLinkSourceImpl2:607). */
        const val BITRATE_BPS = 192_000

        /** Capture chunks buffered for the encoder thread (~80 ms of 10 ms chunks). */
        const val QUEUE_CHUNKS = 8

        /** queue.poll timeout — the reference's 10 ms poll. */
        const val POLL_TIMEOUT_MS = 10L

        /**
         * The same 10 ms for `dequeueInputBuffer`, whose timeout is in
         * **microseconds** — passing [POLL_TIMEOUT_MS] there waited 10 µs, so
         * any momentarily busy codec dropped the unit (an audible gap).
         */
        const val INPUT_DEQUEUE_TIMEOUT_US = POLL_TIMEOUT_MS * 1_000L

        private const val JOIN_TIMEOUT_MS = 500L
    }
}
