package com.zerofriction.localcast.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Phase7 game-audio capture (ADR-003, docs/architecture/audio.md): the
 * `media` factory's ADM, wrapping an [AudioRecord] built with
 * [AudioPlaybackCaptureConfiguration].
 *
 * The honest constraint (verified against stream-webrtc-android1.3.10, the
 * pinned prebuilt — findings in features/game-audio.md): libwebrtc's public
 * Java API cannot feed arbitrary PCM into an ADM. [JavaAudioDeviceModule]
 * only records from the microphone, `WebRtcAudioRecord` creates its record
 * in a private method, and the builder's `AudioRecordDataCallback` is never
 * wired anywhere. The platform does require this app to hold RECORD_AUDIO
 * for playback capture anyway, and the ADM only ever gets past init with a
 * microphone record in place — so the microphone record is *substituted* at
 * recording start:
 *
 *  - `onWebRtcAudioRecordStart` runs on the ADM's recording thread *before
 *    its first read*, which makes the swap deterministic rather than racy;
 *  - the substitute record mirrors the ADM-negotiated format (48 kHz stereo
 *    PCM16, audio.md) so the encoder's 10 ms frame clock stays correct;
 *  - the microphone record is stopped and released immediately — no
 *    microphone sample is ever read or encoded. Only the two field accesses
 *    need reflection (`audioInput` is public but of an inaccessible type;
 *    `audioRecord` is private); everything else is public API.
 *
 * Failure is honest and non-fatal to the cast: the video track keeps
 * streaming and the UI shows the game-audio state, never a technical error.
 */
class PlaybackCaptureAudioSource(
    context: Context,
    /**
     * One consent = one projection: audio reuses the screen capturer's live
     * MediaProjection (audio capture is impossible without it, audio.md).
     * The record starts only after the projection exists, hence a supplier.
     */
    private val projection: () -> MediaProjection?,
    /** State transitions ride this back to the session (which owns the UI flow). */
    private val onState: (GameAudioState) -> Unit = {},
    private val monitor: GameAudioMonitor = GameAudioMonitor(onState = onState),
) {
    val state: GameAudioState get() = monitor.state.value

    /** Plugged into factory A (`media` pc) — its recorder is substituted at start. */
    val adm: AudioDeviceModule = JavaAudioDeviceModule.builder(context)
        // Captured playback is finished audio; platform mic effects would only distort it.
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        .setUseStereoInput(true) // audio.md:48 kHz stereo PCM16 → Opus stereo
        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() = substitutePlaybackCaptureRecord()
            override fun onWebRtcAudioRecordStop() = Unit
        })
        .setSamplesReadyCallback { samples ->
            monitor.onFrame(System.currentTimeMillis(), PcmSilence.isSilent(samples.data))
        }
        .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) = captureFailed("record init: $errorMessage")
            override fun onWebRtcAudioRecordStartError(
                errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                errorMessage: String,
            ) = captureFailed("record start ($errorCode): $errorMessage")
            override fun onWebRtcAudioRecordError(errorMessage: String) = captureFailed("record error: $errorMessage")
        })
        .createAudioDeviceModule()

    /** User mute: the ADM's built-in mute zeroes the frames; Opus keeps flowing (no renegotiation). */
    fun setMuted(muted: Boolean) {
        monitor.setUserMuted(muted)
        adm.setMicrophoneMute(muted)
    }

    fun setStreaming(streaming: Boolean) = monitor.setStreaming(streaming)

    // ---- everything below runs on the ADM's recording thread ----

    private fun substitutePlaybackCaptureRecord() {
        val liveProjection = projection()
        if (liveProjection === null) {
            captureFailed("no live MediaProjection for playback capture")
            return
        }
        try {
            // Lazy, defensive lookups (stream-webrtc-android 1.3.8 keeps these
            // private,1.3.10 makes one public — getDeclaredField covers both).
            val audioInputField = AUDIO_INPUT_FIELD
            val recordField = RECORD_FIELD
            val byteBufferField = BYTE_BUFFER_FIELD
            if (audioInputField === null || recordField === null || byteBufferField === null) {
                stopCurrentRecord(audioInputField)
                captureFailed("ADM recorder internals not found (library changed?)")
                return
            }
            val audioInput = audioInputField.get(adm) ?: run {
                captureFailed("ADM recorder is missing")
                return
            }
            val micRecord = recordField.get(audioInput) as? AudioRecord
            if (micRecord === null) {
                captureFailed("ADM recorder has no AudioRecord to substitute")
                return
            }
            val capture = try {
                buildCaptureRecord(liveProjection, micRecord, audioInput, byteBufferField)
            } catch (t: Throwable) {
                Log.w(TAG, "building the playback-capture record failed: ${t.message}")
                null
            }
            if (capture === null || capture.state != AudioRecord.STATE_INITIALIZED) {
                capture?.release()
                stopAndRelease(micRecord)
                captureFailed("playback-capture AudioRecord could not be built (unsupported format?)")
                return
            }
            capture.startRecording()
            if (capture.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                capture.release()
                stopAndRelease(micRecord)
                captureFailed("playback-capture AudioRecord failed to start recording")
                return
            }
            recordField.set(audioInput, capture)
            // The microphone record the platform required to start the ADM is
            // never read and never encoded — release it immediately.
            stopAndRelease(micRecord)
            Log.i(
                TAG,
                "playback capture active: ${capture.sampleRate} Hz, ${capture.channelCount} ch, ${capture.audioFormat}",
            )
        } catch (t: Throwable) {
            captureFailed("substituting playback capture failed: ${t.message}")
        }
    }

    /**
     * Mirror the ADM-negotiated format exactly: the recorder thread's reads
     * and the encoder's frame clock assume it. Playback capture supports a
     * fixed format set (PCM16, CHANNEL_IN_MONO/STEREO, standard rates); a
     * device that negotiated anything else fails honestly → [GameAudioState.Failed].
     */
    private fun buildCaptureRecord(
        projection: MediaProjection,
        micRecord: AudioRecord,
        audioInput: Any,
        byteBufferField: java.lang.reflect.Field,
    ): AudioRecord {
        val channelMask = if (micRecord.channelCount == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(micRecord.sampleRate)
            .setChannelMask(channelMask)
            .build()
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            // audio.md usage filter: media, game, unknown — voice apps are
            // never capturable by platform design (their users' privacy).
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        // The recording thread reads byteBuffer.capacity() per iteration —
        // the capture record's buffer must hold at least that much.
        val readBytes = (byteBufferField.get(audioInput) as java.nio.ByteBuffer).capacity()
        val minBytes = AudioRecord.getMinBufferSize(micRecord.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, readBytes))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    /** Stop whatever record the ADM still holds (best-effort — the lookup may have failed). */
    private fun stopCurrentRecord(audioInputField: java.lang.reflect.Field?) {
        if (audioInputField === null) return
        try {
            (RECORD_FIELD?.get(audioInputField.get(adm)) as? AudioRecord)?.let { stopAndRelease(it) }
        } catch (t: Throwable) {
            Log.d(TAG, "stopping the ADM's record after a failed swap: ${t.message}")
        }
    }

    private fun stopAndRelease(record: AudioRecord) {
        try {
            record.stop()
        } catch (t: Throwable) {
            // Already stopped (e.g. record never started) — nothing to save.
            Log.d(TAG, "stopping the replaced record: ${t.message}")
        }
        record.release()
    }

    private fun captureFailed(detail: String) {
        Log.w(TAG, "game audio unavailable: $detail")
        monitor.onCaptureFailed()
    }

    companion object {
        private const val TAG = "PlaybackCaptureAudio"

        /**
         * Lazy lookups, never class-init: a library update that renames the
         * internals degrades this cast to video-only ([GameAudioState.Failed])
         * instead of crashing the session. Visibility differs between pinned
         * artifact versions (1.3.8: private, 1.3.10: public for `audioInput`)
         * — `getDeclaredField` covers both.
         */
        private val AUDIO_INPUT_FIELD: java.lang.reflect.Field? = lookupField(
            JavaAudioDeviceModule::class.java,
            "audioInput",
        )
        private val RECORD_FIELD: java.lang.reflect.Field? =
            AUDIO_INPUT_FIELD?.let { lookupField(it.type, "audioRecord") }
        private val BYTE_BUFFER_FIELD: java.lang.reflect.Field? =
            AUDIO_INPUT_FIELD?.let { lookupField(it.type, "byteBuffer") }

        private fun lookupField(container: Class<*>, name: String): java.lang.reflect.Field? = try {
            container.getDeclaredField(name).apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.w(TAG, "field $name not found on ${container.name} — game audio unavailable")
            null
        }
    }
}
