package com.zerofriction.localcast.usb

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.zerofriction.localcast.audio.MicDeviceResolver
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.config.MicDeviceSource
import kotlin.concurrent.thread

/**
 * The microphone of a USB cast (ADR-034): its own track, never mixed with the
 * game audio (ADR-003). The capture follows the Wi-Fi mic's rules (ADR-004):
 * source `MIC` so the game's own voice chat keeps working, the user's chosen
 * input (built-in, wired headset, USB, Bluetooth) at that input's own rate.
 * Sent as 10 ms of 48 kHz mono PCM16 (usb-link.md, kind 3) — the receiver's
 * mic engine runs at 48 kHz, so lower capture rates are resampled here.
 */
class UsbMicCapture(
    private val context: Context,
    private val source: MicDeviceSource,
    /** One 10 ms block at 48 kHz mono; false = the link is gone, stop reading. */
    private val onBlock: (ptsUs: Long, pcm: ByteArray) -> Boolean,
    private val onState: (MicState) -> Unit,
) {
    private var record: AudioRecord? = null
    @Volatile private var running = false

    /** False when the mic can't start; the state says why (the cast goes on without it). */
    @SuppressLint("MissingPermission") // checked just below
    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onState(MicState.NeedsPermission)
            return false
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val type = MicDeviceResolver.resolveType(source, inputs.map { it.type })
        val kind = MicDeviceResolver.kindForType(type)
        val rate = MicDeviceResolver.captureRateHz(kind)
        val device: AudioDeviceInfo? = inputs.firstOrNull { it.type == type }
        val blockSamples = rate / 1000 * BLOCK_MS
        val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, blockSamples * 2 * 4))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "mic record refused: ${e.message}")
            onState(MicState.Failed)
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            onState(MicState.Failed)
            return false
        }
        device?.let { r.setPreferredDevice(it) }
        r.startRecording()
        record = r
        running = true
        onState(MicState.Active)
        Log.i(TAG, "mic over USB: ${kind.label} at $rate Hz → 48 kHz mono, 10 ms blocks")
        thread(name = "usb-mic") { readLoop(r, rate, blockSamples) }
        return true
    }

    private fun readLoop(r: AudioRecord, rate: Int, blockSamples: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = ShortArray(blockSamples)
        var blocks = 0L
        var silenced = false
        while (running) {
            var filled = 0
            while (filled < blockSamples && running) {
                val n = r.read(block, filled, blockSamples - filled)
                if (n < 0) {
                    Log.w(TAG, "mic read error $n")
                    running = false
                    onState(MicState.Failed)
                    return
                }
                filled += n
            }
            if (!running) break
            // ADR-004: the platform may silence this capture while another app records.
            if (++blocks % SILENCE_CHECK_EVERY_BLOCKS == 0L) {
                val now = r.activeRecordingConfiguration?.isClientSilenced == true
                if (now != silenced) {
                    silenced = now
                    onState(if (now) MicState.Silenced else MicState.Active)
                }
            }
            val out = UsbAudioFormat.toPcm48kMono(block, rate)
            if (!onBlock(SystemClock.elapsedRealtimeNanos() / 1000, out)) break
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        onState(MicState.Off)
    }

    private companion object {
        const val TAG = "UsbMic"
        const val BLOCK_MS = 10
        const val SILENCE_CHECK_EVERY_BLOCKS = 50L
    }
}

/** The link's mic format (usb-link.md kind 3): 48 kHz mono PCM16LE. Pure — JVM-tested. */
object UsbAudioFormat {
    const val MIC_RATE_HZ = 48_000

    /** Little-endian PCM16 at 48 kHz from mono samples at [rate] (linear interpolation; 48 kHz passes through). */
    fun toPcm48kMono(samples: ShortArray, rate: Int): ByteArray {
        val outCount = (samples.size.toLong() * MIC_RATE_HZ / rate).toInt()
        val out = ByteArray(outCount * 2)
        for (i in 0 until outCount) {
            val v = if (rate == MIC_RATE_HZ) {
                samples[i].toInt()
            } else {
                val pos = i.toDouble() * rate / MIC_RATE_HZ
                val a = pos.toInt().coerceAtMost(samples.size - 1)
                val b = (a + 1).coerceAtMost(samples.size - 1)
                val t = pos - a
                (samples[a] * (1 - t) + samples[b] * t).toInt()
            }
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
