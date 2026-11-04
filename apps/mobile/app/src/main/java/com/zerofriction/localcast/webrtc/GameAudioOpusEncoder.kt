package com.zerofriction.localcast.webrtc

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-013: the game-audio DataChannel payload encoder — the platform MediaCodec
 * Opus encoder (hardware `c2.android.opus.encoder` on Android 10+), synchronous
 * mode on its own thread. The ADM's recording thread only enqueues via [feed]
 * (the same never-block rule as the PCM sender); every encoded 20 ms access
 * unit leaves as one DataChannel binary message, preceded — once, from the
 * sender thread, just before the first packet — by the channel payload v1
 * announce ([GameAudioOpusFormat]).
 *
 * Failure model (ADR-013): availability is probed before wiring
 * ([selectEncoder]); anything that then throws on the encoder thread happens
 * at most once and is reported via [onUnavailable] — the session rewires the
 * tap to the raw-PCM sender (ADR-012 path). Because the announce is queued
 * only when the first encoded packet exists, a pre-output failure is
 * invisible to the desktop: it simply sees a PCM stream (or silence, if the
 * encoder died after packets — the quiet timer then hands the track back).
 *
 * Numbers only in the logs — never the samples (logging rules).
 */
class GameAudioOpusEncoder(
    private val channel: GameAudioDataChannel,
    private val onUnavailable: (dead: GameAudioOpusEncoder, reason: String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val input = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var thread: Thread? = null

    /** Enqueue-only (the ADM recording thread calls this) — drop-oldest, never block. */
    fun feed(samples: ByteArray) {
        if (!running.get()) return
        val chunk = samples.copyOf() // the producer's buffer is reused downstream
        if (!input.offer(chunk)) {
            input.poll()
            if (!input.offer(chunk)) return
        }
    }

    fun start(encoderName: String) {
        check(running.compareAndSet(false, true)) { "encoder already started" }
        thread = Thread { run(encoderName) }.apply {
            name = "game-audio-opus"
            start()
        }
    }

    /** Idempotent; safe from the encoder thread itself (the failure path). */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        Log.i(TAG, "game audio opus encoder stopped")
    }

    private fun run(encoderName: String) {
        var codec: MediaCodec? = null
        try {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE_HZ, CHANNELS).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
            }
            val newCodec = MediaCodec.createByCodecName(encoderName)
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            newCodec.start()
            codec = newCodec
            Log.i(TAG, "game audio opus encoder started ($encoderName, ${BITRATE_BPS} bps)")

            var preskip = -1
            var announced = false
            var ptsUs = 0L
            var pending: ByteArray? = null
            var pendingPos = 0
            val info = MediaCodec.BufferInfo()

            while (running.get()) {
                if (pending === null || pendingPos >= pending.size) {
                    pending = input.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    pendingPos = 0
                }

                // --- one input buffer per loop; chunks larger than it carry over.
                val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    if (buffer !== null) {
                        val take = minOf(pending.size - pendingPos, buffer.remaining())
                        buffer.put(pending, pendingPos, take)
                        codec.queueInputBuffer(inIndex, 0, take, ptsUs, 0)
                        ptsUs += (take * MICROS_PER_SECOND) / BYTES_PER_SECOND
                        pendingPos += take
                    }
                }

                // --- drain everything already encoded.
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (preskip < 0) preskip = preskipFromFormat(codec.outputFormat) ?: -1
                        continue
                    }
                    if (outIndex < 0) continue
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer !== null && info.size > 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // csd-0 OpusHead — the preskip the decoder needs.
                            if (preskip < 0) {
                                val csd = ByteArray(info.size)
                                buffer.get(csd)
                                preskip = GameAudioOpusFormat.preskipFromOpusHead(csd) ?: -1
                            }
                        } else {
                            val packet = ByteArray(info.size)
                            buffer.get(packet)
                            if (!announced) {
                                announced = true
                                val effective =
                                    if (preskip >= 0) preskip else GameAudioOpusFormat.DEFAULT_PRESKIP_SAMPLES
                                Log.i(TAG, "game audio announce queued (preskip=$effective)")
                                channel.queueAnnounce(GameAudioOpusFormat.announce(effective))
                            }
                            channel.onEncodedPacket(packet)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Log.w(TAG, "game audio opus encoder failed: ${t.message}")
                stop()
                onUnavailable(this, t.message ?: "unknown encoder failure")
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
                // A codec that failed mid-loop may already be stopped/released.
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
                // Same — release is best-effort on the failure path.
            }
        }
    }

    private fun preskipFromFormat(format: MediaFormat): Int? {
        val csd = format.getByteBuffer("csd-0") ?: return null
        val bytes = ByteArray(csd.remaining())
        csd.get(bytes)
        return GameAudioOpusFormat.preskipFromOpusHead(bytes)
    }

    companion object {
        private const val TAG = "GameAudioOpusEnc"

        const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_OPUS

        /**
         * 128 kbps stereo — the transparency bar (ADR-013: "indistinguishable
         * from the PCM path"); ~12× lighter than the 1.5 Mbps raw-PCM payload.
         */
        const val BITRATE_BPS = 128_000

        const val SAMPLE_RATE_HZ = GameAudioOpusFormat.SAMPLE_RATE_HZ
        const val CHANNELS = GameAudioOpusFormat.CHANNELS

        /** 48 kHz × 2 ch × PCM16. */
        private const val BYTES_PER_SECOND = 192_000L
        private const val MICROS_PER_SECOND = 1_000_000L

        /** Poll waits that keep stop() responsive without spinning. */
        private const val POLL_MS = 20L
        private const val INPUT_TIMEOUT_US = 10_000L

        /**
         * ~1 s of 48 kHz stereo PCM16 input at the ADM's ~10 ms chunks — the
         * encoder thread owns the newest audio (drop-oldest), like the channel.
         */
        const val QUEUE_CAPACITY = 100

        /**
         * The platform's encoder for our format, or null — the wiring decision
         * (Opus vs the ADR-012 PCM path) is made from this, once, at attach.
         */
        fun selectEncoder(): String? {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE_HZ, CHANNELS)
            return try {
                MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format)
            } catch (t: Throwable) {
                Log.w(TAG, "encoder probe failed: ${t.message}")
                null
            }
        }
    }
}
