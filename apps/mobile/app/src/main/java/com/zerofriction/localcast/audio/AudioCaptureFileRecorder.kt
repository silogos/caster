package com.zerofriction.localcast.audio

import android.util.Log
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/**
 * Debug diagnostics: writes the captured playback PCM — the very bytes the
 * WebRTC encoder consumes — to a WAV file, so a channel fold can be judged
 * by ear and kept as evidence, independent of the transport (issue #101).
 * Aggregate probes cannot distinguish real stereo from interleaving
 * artifacts where both per-channel means are alive; a playable file can.
 *
 * Bounded ([MAX_SECONDS]) and opt-in per cast — the capture path enables it
 * only when the `localcast_capture_dump` global setting is 1 (set via adb on
 * a development device), so no release cast ever writes audio to disk.
 * Never logs the samples — only the file path (logging rules).
 */
internal class AudioCaptureFileRecorder private constructor(
    private val file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val maxBytes: Long,
) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file))
    private var pcmBytes = 0L
    private var closed = false

    /** True once this dump finished (cap reached or [close] called). */
    val isClosed: Boolean get() = closed

    /** Feeds one interleaved PCM16 little-endian read (the encoder's bytes). */
    fun onSamples(samples: ByteArray) {
        if (closed) return
        try {
            out.write(samples)
            pcmBytes += samples.size
            if (pcmBytes >= maxBytes) close()
        } catch (t: Throwable) {
            Log.w(TAG, "capture dump write failed: ${t.message}")
            close()
        }
    }

    /** Patches the WAV sizes into the header and closes the stream. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            out.flush()
            out.close()
            val header = wavHeader(sampleRate, channelCount, pcmBytes)
            // The header was written up-front with placeholder sizes; patch
            // it in place rather than buffering the recording in memory.
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header)
            }
            Log.i(TAG, "capture dump closed: ${file.absolutePath} (${pcmBytes / 1024} KiB PCM)")
        } catch (t: Throwable) {
            Log.w(TAG, "capture dump close failed: ${t.message}")
        }
    }

    /** Minimal RIFF/WAVE header for 16-bit PCM — sizes patched on [close]. */
    private fun wavHeader(sampleRate: Int, channels: Int, pcmBytes: Long): ByteArray =
        wavHeaderFor(sampleRate, channels, pcmBytes)

    internal companion object {
        private const val TAG = "AudioCaptureDump"

        /** ~2 minutes of 48 kHz stereo PCM16 ≈ 23 MiB — bounded by design. */
        const val MAX_SECONDS = 120
        private const val BYTES_PER_SECOND_PER_CHANNEL = 2

        /** Minimal RIFF/WAVE header for 16-bit PCM (internal for tests). */
        internal fun wavHeaderFor(sampleRate: Int, channels: Int, pcmBytes: Long): ByteArray {
            val blockAlign = channels * 2
            val byteRate = sampleRate * blockAlign
            fun le32(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
                (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte(),
            )

            fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
            val dataChunkSize = pcmBytes.toInt() // recordings are bounded well under 2 GiB
            return byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                *le32(36 + dataChunkSize),
                'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
                'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
                *le32(16),
                *le16(1), // PCM
                *le16(channels),
                *le32(sampleRate),
                *le32(byteRate),
                *le16(blockAlign),
                *le16(16), // bits per sample
                'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
                *le32(dataChunkSize),
            )
        }

        /**
         * Starts a bounded dump of the given stream format into [dir], or
         * null when the recorder could not be created (dumping is optional
         * diagnostics — callers proceed without it).
         */
        fun start(dir: File, sampleRate: Int, channelCount: Int): AudioCaptureFileRecorder? {
            return try {
                dir.mkdirs()
                val name = "capture-dump-${System.currentTimeMillis()}-" +
                    "${sampleRate}Hz-${channelCount}ch.wav"
                val bytesPerSecond = sampleRate * channelCount * BYTES_PER_SECOND_PER_CHANNEL
                val recorder = AudioCaptureFileRecorder(
                    File(dir, name),
                    sampleRate,
                    channelCount,
                    bytesPerSecond.toLong() * MAX_SECONDS,
                )
                recorder.out.write(wavHeaderFor(sampleRate, channelCount, 0))
                Log.i(
                    TAG,
                    "capture dump started: ${recorder.file.absolutePath} " +
                        "($sampleRate Hz, $channelCount ch, ≤$MAX_SECONDS s)",
                )
                recorder
            } catch (t: Throwable) {
                Log.w(TAG, "capture dump start failed: ${t.message}")
                null
            }
        }
    }
}

