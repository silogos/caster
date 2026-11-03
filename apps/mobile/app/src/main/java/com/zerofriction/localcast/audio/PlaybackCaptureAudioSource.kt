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

    /** For the start-of-capture diagnostics ([AudioPolicySinkProbe]). */
    private val appContext: Context = context

    /** Fed on the ADM's recording thread alongside the silence monitor. */
    private val stereoContentProbe = StereoContentProbe()

    /** Self-heal for the mono-born tap (see [MonoTapRecovery]). */
    private val monoTapRecovery = MonoTapRecovery()

    /** What a mid-cast capture rebuild needs — cached at the initial substitution. */
    private class SwapContext(val audioInput: Any, val sampleRate: Int, val channelCount: Int, val readBytes: Int)

    private var swapContext: SwapContext? = null

    /** Plugged into factory A (`media` pc) — its recorder is substituted at start. */
    val adm: AudioDeviceModule = JavaAudioDeviceModule.builder(context)
        // Captured playback is finished audio; platform mic effects would only distort it.
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        // Shapes the record only — the *transport* is stereo only because the
        // offer's fmtp asks for it (SdpOpusFmtp in MediaCastSession).
        .setUseStereoInput(true)
        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() = substitutePlaybackCaptureRecord()
            override fun onWebRtcAudioRecordStop() = Unit
        })
        .setSamplesReadyCallback { samples ->
            monitor.onFrame(System.currentTimeMillis(), PcmSilence.isSilent(samples.data))
            // Stereo diagnostics + tap self-heal (audio.md): whether the mix
            // carries L≠R, one report per second, and a bounded capture-record
            // rebuild when the tap is born with a dead channel.
            stereoContentProbe.onSamples(samples.data)?.let { report ->
                Log.i(TAG, "capture stereo content: ${report.summarize()}")
                monoTapRecovery.onReport(report)?.let { reason -> rebuildCaptureRecord(reason) }
            }
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
            val readBytes = (byteBufferField.get(audioInput) as java.nio.ByteBuffer).capacity()
            swapContext = SwapContext(audioInput, capture.sampleRate, capture.channelCount, readBytes)
            Log.i(
                TAG,
                "playback capture active: ${capture.sampleRate} Hz, ${capture.channelCount} ch, ${capture.audioFormat}",
            )
            if (capture.channelCount == 2) {
                probePlatformCapture(liveProjection, capture.sampleRate)
                AudioPolicySinkProbe.run(appContext)
            }
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
        val readBytes = (byteBufferField.get(audioInput) as java.nio.ByteBuffer).capacity()
        return try {
            buildCaptureRecord(projection, micRecord.sampleRate, micRecord.channelCount, readBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "index-mask build failed (${t.message}) — falling back to the position mask")
            buildCaptureRecord(projection, micRecord.sampleRate, micRecord.channelCount, readBytes, channelIndexMask = false)
        }
    }

    private fun buildCaptureRecord(
        projection: MediaProjection,
        sampleRate: Int,
        channelCount: Int,
        readBytes: Int,
        channelIndexMask: Boolean = false,
    ): AudioRecord {
        // Channel *index* mask first, position mask as fallback (both found
        // live 2026-11-03: this ROM's playback-capture remix delivers one
        // channel for position-mask records, and rejects index-mask input
        // outright — "Unsupported channel configuration for input" — so the
        // position mask is the default and index stays a probe-only flavor).
        val mask = if (channelCount == 2) {
            if (channelIndexMask) STEREO_CHANNEL_INDEX_MASK else AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val formatBuilder = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
        val format =
            if (channelCount == 2 && channelIndexMask) formatBuilder.setChannelIndexMask(mask).build()
            else formatBuilder.setChannelMask(mask).build()
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            // audio.md usage filter: media, game, unknown — voice apps are
            // never capturable by platform design (their users' privacy).
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        // The recording thread reads byteBuffer.capacity() per iteration —
        // the capture record's buffer must hold at least that much.
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, readBytes))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    /**
     * The self-heal ([MonoTapRecovery]): swap in a *fresh* playback-capture
     * record — the same field write the initial substitution uses, executed
     * on the recording thread between two reads, so the cast never breaks.
     * The old record is stopped only after the swap. Best-effort by design:
     * a failed rebuild keeps the current record streaming and logs.
     */
    private fun rebuildCaptureRecord(reason: String) {
        val context = swapContext ?: return
        val liveProjection = projection() ?: return
        val recordField = RECORD_FIELD ?: return
        try {
            val fresh = try {
                buildCaptureRecord(liveProjection, context.sampleRate, context.channelCount, context.readBytes)
            } catch (t: Throwable) {
                try {
                    Log.w(TAG, "index-mask rebuild failed (${t.message}) — retrying with the position mask")
                    buildCaptureRecord(
                        liveProjection,
                        context.sampleRate,
                        context.channelCount,
                        context.readBytes,
                        channelIndexMask = false,
                    )
                } catch (t2: Throwable) {
                    Log.w(TAG, "capture rebuild failed: ${t2.message}")
                    return
                }
            }
            if (fresh.state != AudioRecord.STATE_INITIALIZED) {
                fresh.release()
                Log.w(TAG, "capture rebuild failed: record not initialized")
                return
            }
            fresh.startRecording()
            if (fresh.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fresh.release()
                Log.w(TAG, "capture rebuild failed: record did not start")
                return
            }
            val stale = recordField.get(context.audioInput) as? AudioRecord
            recordField.set(context.audioInput, fresh)
            stale?.let { stopAndRelease(it) }
            Log.i(TAG, "capture record rebuilt — $reason")
        } catch (t: Throwable) {
            Log.w(TAG, "capture rebuild failed: ${t.message}")
        }
    }

    /**
     * Reads standalone playback-capture records **outside the ADM** for ~2 s
     * per channel-mask flavor at capture start (concurrent captures of the
     * mix are allowed), reporting the same stereo-content aggregate for each.
     * The found-live one-channel tap (stereo tracks in AudioFlinger, one
     * channel zero in the ADM's PCM) needed both a splitter (platform vs
     * ADM/swap path) and a mask comparison — AudioFlinger's index-mask remix
     * route differs from the position-mask one. Diagnostic numbers only.
     */
    private fun probePlatformCapture(projection: MediaProjection, sampleRate: Int) {
        Thread({
            for (mask in listOf(false to "position mask", true to "index mask")) {
                try {
                    val minBytes = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    val record = buildCaptureRecord(
                        projection,
                        sampleRate,
                        channelCount = 2,
                        readBytes = minBytes * 4,
                        channelIndexMask = mask.first,
                    )
                    record.startRecording()
                    val probe = StereoContentProbe(framesPerReport = sampleRate)
                    val buffer = ByteArray(minBytes)
                    var reports = 0
                    while (reports < 2) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read <= 0) break
                        probe.onSamples(buffer.copyOf(read))?.let { report ->
                            Log.i(TAG, "platform capture check (${mask.second}): ${report.summarize()}")
                            reports++
                        }
                    }
                    stopAndRelease(record)
                } catch (t: Throwable) {
                    Log.w(TAG, "platform capture check (${mask.second}) failed: ${t.message}")
                }
            }
        }, "platform-capture-probe").start()
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

        /** Channel-index mask bits 0,1 — the first two channels, L and R. */
        private const val STEREO_CHANNEL_INDEX_MASK = 0x3

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
