package com.zerofriction.localcast.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.webrtc.audio.JavaAudioDeviceModule
import java.lang.reflect.Field
import java.nio.ByteBuffer

/**
 * Factory B's privacy-insensitive mic record (ADR-004, docs/architecture/
 * audio.md).
 *
 * The stock ADM records the microphone with `VOICE_COMMUNICATION` — the
 * source that brings the platform's AEC/NS/AGC — but that source is
 * *privacy-sensitive by default*: under Android's concurrent-capture policy
 * a privacy-sensitive capture wins priority and every other recording is
 * silenced (the live-found bug: turning the cast mic on muted PUBG's voice
 * chat). The flag can only be set when the record is created
 * (`AudioRecord.Builder.setPrivacySensitive`) and the fork's ADM has no API
 * for it — so, exactly like the playback-capture substitution in factory A,
 * the ADM's record is swapped at recording start for a twin: same source
 * (the HAL voice processing is tied to the source, so AEC/NS/AGC survive),
 * same negotiated format, but explicitly privacy-insensitive. Two
 * non-sensitive captures are allowed to coexist, which is the same shape of
 * traffic a live-streaming app and a game's voice chat produce.
 *
 * Deterministic: `onWebRtcAudioRecordStart` runs on the ADM's recording
 * thread before its first read. Only the two field accesses need reflection
 * (`audioInput` is public but of an inaccessible type; `audioRecord` is
 * private) — the same lazy, defensive lookups as [PlaybackCaptureAudioSource]
 * but its own copy: factory A's verified substitution is not touched.
 *
 * Degradation, not failure: if anything here can't run (old API level,
 * reflection lookup, twin record rejected), the ADM's own record stays and
 * the mic works exactly as before this fix — with the documented
 * coexistence cost — logged, never surfaced as a mic failure.
 */
class MicRecordSubstituter(private val context: Context, private val adm: JavaAudioDeviceModule) {

    /** Called from the ADM's `onWebRtcAudioRecordStart` — its recording thread. */
    fun onRecordStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // setPrivacySensitive() is an API-30 platform method; below that
            // there is nothing to do — the stock record stays.
            Log.i(TAG, "below API 30 the privacy-sensitive flag cannot be cleared — stock mic record stays")
            return
        }
        try {
            val audioInputField = AUDIO_INPUT_FIELD ?: run { logDegrade("ADM recorder internals not found"); return }
            val recordField = RECORD_FIELD ?: run { logDegrade("ADM record field not found"); return }
            val byteBufferField = BYTE_BUFFER_FIELD ?: run { logDegrade("ADM byte buffer field not found"); return }
            val audioInput = audioInputField.get(adm) ?: run { logDegrade("ADM recorder is missing"); return }
            val stock = recordField.get(audioInput) as? AudioRecord
                ?: run { logDegrade("ADM recorder has no AudioRecord to substitute"); return }
            val twin = buildTwin(stock, audioInput, byteBufferField)
            if (twin.state != AudioRecord.STATE_INITIALIZED) {
                twin.release()
                logDegrade("the twin record could not be initialized")
                return
            }
            routeToBuiltinMic(twin)
            twin.startRecording()
            if (twin.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                twin.release()
                logDegrade("the twin record failed to start recording")
                return
            }
            recordField.set(audioInput, twin)
            stopAndRelease(stock)
            Log.i(TAG, "privacy-insensitive mic record active (${twin.sampleRate} Hz, ${twin.channelCount} ch)")
        } catch (t: Throwable) {
            logDegrade("substitution failed: ${t.message}")
        }
    }

    /**
     * The twin mirrors the stock record's negotiated format exactly (the
     * recorder thread's reads and the encoder's 10 ms frame clock assume it)
     * and keeps the voice source — only the privacy-sensitive flag differs.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildTwin(stock: AudioRecord, audioInput: Any, byteBufferField: Field): AudioRecord {
        val channelMask = if (stock.channelCount == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val format = AudioFormat.Builder()
            .setEncoding(stock.audioFormat)
            .setSampleRate(stock.sampleRate)
            .setChannelMask(channelMask)
            .build()
        // The recording thread reads byteBuffer.capacity() per iteration —
        // the twin's buffer must hold at least that much.
        val readBytes = (byteBufferField.get(audioInput) as ByteBuffer).capacity()
        val minBytes = AudioRecord.getMinBufferSize(stock.sampleRate, channelMask, stock.audioFormat)
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, readBytes))
            .setPrivacySensitive(false)
            .build()
    }

    /**
     * Route the twin to the built-in microphone explicitly. The default
     * routing is not ours to choose: when another app's voice chat captures
     * concurrently, the platform split our record onto
     * `AUDIO_DEVICE_IN_BACK_MIC` while the game kept `AUDIO_DEVICE_IN_BUILTIN_
     * MIC` (found live via dumpsys) — a different processing path that fed the
     * 48 kHz record raw 16 kHz data (the desktop heard chipmunk) and, at
     * best, a mic the user isn't speaking into. Naming the device ties the
     * cast mic to the mic the user actually speaks into, and with the
     * session's input rate matching the voice-chat rate
     * (SHARED_VOICE_INPUT_RATE_HZ, MicCastSession) the twin shares the same
     * device+rate configuration the game's capture already runs.
     */
    private fun routeToBuiltinMic(record: AudioRecord) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val builtinMic = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        } ?: run {
            Log.w(TAG, "no built-in mic device found — default routing stays")
            return
        }
        if (record.setPreferredDevice(builtinMic)) {
            Log.i(TAG, "cast mic routed to the built-in microphone")
        } else {
            logDegrade("the built-in mic was not accepted as preferred device")
        }
    }

    private fun stopAndRelease(record: AudioRecord) {
        try {
            record.stop()
        } catch (t: Throwable) {
            // Already stopped — nothing to save.
            Log.d(TAG, "stopping the replaced record: ${t.message}")
        }
        record.release()
    }

    private fun logDegrade(detail: String) {
        Log.w(TAG, "stock mic record stays (privacy-sensitive): $detail")
    }

    companion object {
        private const val TAG = "MicRecordSubstituter"

        /**
         * Lazy lookups, never class-init: a library update that renames the
         * internals degrades this fix to the stock record instead of crashing
         * the session (same shape as PlaybackCaptureAudioSource's).
         */
        private val AUDIO_INPUT_FIELD: Field? = lookupField(JavaAudioDeviceModule::class.java, "audioInput")
        private val RECORD_FIELD: Field? = AUDIO_INPUT_FIELD?.let { lookupField(it.type, "audioRecord") }
        private val BYTE_BUFFER_FIELD: Field? = AUDIO_INPUT_FIELD?.let { lookupField(it.type, "byteBuffer") }

        private fun lookupField(container: Class<*>, name: String): Field? = try {
            container.getDeclaredField(name).apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.w(TAG, "field $name not found on ${container.name} — mic substitution unavailable")
            null
        }
    }
}
