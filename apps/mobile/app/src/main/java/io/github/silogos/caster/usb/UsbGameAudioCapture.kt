package io.github.silogos.caster.usb

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.silogos.caster.audio.GameAudioMonitor
import io.github.silogos.caster.audio.GameAudioState
import io.github.silogos.caster.audio.PcmSilence
import kotlin.concurrent.thread

/**
 * The game-audio half of a USB cast (ADR-034): playback capture (the same
 * usage filter as audio.md) read in 10 ms PCM16 48 kHz stereo blocks — the
 * DataChannel path's exact samples, so the receiver's engine sees the same
 * input. No WebRTC ADM in between: over the cable the record is ours.
 */
class UsbGameAudioCapture(
    private val context: Context,
    private val projection: MediaProjection,
    /** One 10 ms block; false = the link is gone, stop reading. */
    private val onBlock: (ptsUs: Long, pcm: ByteArray) -> Boolean,
    onState: (GameAudioState) -> Unit,
) {
    private val monitor = GameAudioMonitor(onState = onState)
    private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var muted = false

    val state: GameAudioState get() = monitor.state.value

    /** False when the capture can't start (no RECORD_AUDIO, or the platform refused) — the cast goes on without sound. */
    @SuppressLint("MissingPermission") // checked just below
    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no RECORD_AUDIO — game audio off for this cast")
            monitor.onCaptureFailed()
            return false
        }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBytes, BLOCK_BYTES * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "playback capture refused: ${e.message}")
            monitor.onCaptureFailed()
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "playback capture record not initialized")
            r.release()
            monitor.onCaptureFailed()
            return false
        }
        r.startRecording()
        record = r
        running = true
        monitor.setStreaming(true)
        Log.i(TAG, "game audio capture: ${SAMPLE_RATE} Hz stereo PCM16, ${BLOCK_BYTES} B blocks")
        thread(name = "usb-game-audio") { readLoop(r) }
        return true
    }

    private fun readLoop(r: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = ByteArray(BLOCK_BYTES)
        while (running) {
            var filled = 0
            while (filled < BLOCK_BYTES && running) {
                val n = r.read(block, filled, BLOCK_BYTES - filled)
                if (n < 0) {
                    Log.w(TAG, "capture read error $n")
                    running = false
                    monitor.onCaptureFailed()
                    return
                }
                filled += n
            }
            if (!running) break
            monitor.onFrame(SystemClock.elapsedRealtime(), PcmSilence.isSilent(block))
            if (muted) block.fill(0)
            if (!onBlock(SystemClock.elapsedRealtimeNanos() / 1000, block)) break
        }
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        monitor.setUserMuted(muted)
    }

    fun stop() {
        running = false
        monitor.setStreaming(false)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val BLOCK_MS = 10
        /** 10 ms of 48 kHz stereo PCM16: the DataChannel chunk (usb-link.md). */
        const val BLOCK_BYTES = SAMPLE_RATE / 1000 * BLOCK_MS * CHANNELS * 2
        private const val TAG = "UsbGameAudio"
    }
}
