package com.zerofriction.localcast.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.debug.LatencyClickProbe
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

    /** Reads the capture-dump global setting and resolves the dump's output dir. */
    private val appContext: Context = context

    /** Fed on the ADM's recording thread alongside the silence monitor. */
    private val stereoContentProbe = StereoContentProbe()

    /** Debug-only: how late a captured block reaches the app. */
    private val readGaps = com.zerofriction.localcast.debug.GapProbe("audio read")

    /** Self-heal for the mono-born tap (see [MonoTapRecovery]). */
    private val monoTapRecovery = MonoTapRecovery()

    /** What a mid-cast capture rebuild needs — cached at the initial substitution. */
    private class SwapContext(val audioInput: Any, val sampleRate: Int, val channelCount: Int, val readBytes: Int)

    private var swapContext: SwapContext? = null

    /**
     * The capture record opened by [preOpenCapture] — BEFORE the ADM's
     * microphone record exists — consumed by the initial substitution and
     * released there or by [release] if the cast never reaches record start.
     * Volatile: written on the session thread, consumed on the ADM's
     * recording thread.
     */
    @Volatile
    private var preOpenedRecord: AudioRecord? = null

    /**
     * Opt-in per-cast WAV dump of the encoder-consumed bytes (issue #101
     * diagnostics) — enabled by the `localcast_capture_dump` global setting
     * via adb, bounded by the recorder, closed at teardown.
     */
    @Volatile
    private var captureDump: AudioCaptureFileRecorder? = null

    /**
     * The dump setting, read once per capture swap — the samples callback
     * runs 100×/s, and it used to query `Settings.Global` (a binder call into
     * the settings provider) on every buffer of every cast while unarmed.
     */
    @Volatile
    private var captureDumpArmed = false

    /**
     * ADR-012: the encoder-input bytes offered to the session's DataChannel
     * sender (the bytes the prebuilt encoder folds to mono). Set by the
     * session when the game-audio channel is attached; invoked on the ADM's
     * recording thread — implementations must not block.
     */
    @Volatile
    var onEncoderInputSamples: ((ByteArray) -> Unit)? = null

    /** Plugged into factory A (`media` pc) — its recorder is substituted at start. */
    val adm: AudioDeviceModule = JavaAudioDeviceModule.builder(context)
        // Captured playback is finished audio; platform mic effects would only distort it.
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        // Shapes the record only — the *transport* is stereo only because the
        // offer's fmtp asks for it (SdpOpusFmtp in MediaCastSession).
        .setUseStereoInput(true)
        // Pinned so the pre-opened capture record ([preOpenCapture]) mirrors
        // the ADM record byte-for-byte; WebRTC's default may drift (16 kHz
        // builds existed) and a mismatched pre-open would be thrown away.
        .setInputSampleRate(PINNED_INPUT_SAMPLE_RATE)
        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() = substitutePlaybackCaptureRecord()
            override fun onWebRtcAudioRecordStop() = Unit
        })
        .setSamplesReadyCallback { samples ->
            monitor.onFrame(System.currentTimeMillis(), PcmSilence.isSilent(samples.data))
            if (BuildConfig.DEBUG) {
                LatencyClickProbe.onSamples(samples.data)
                readGaps.onEvent()
            }
            onEncoderInputSamples?.invoke(samples.data)
            // A capped dump steps aside so the next dump starts — a long
            // session gets successive bounded files instead of just the first.
            captureDump?.let { dump -> if (dump.isClosed) captureDump = null }
            if (captureDump === null && captureDumpArmed) maybeStartCaptureDump()
            captureDump?.onSamples(samples.data)
            // Stereo diagnostics + tap self-heal (audio.md): whether the mix
            // carries L≠R, one report per second, and a bounded capture-record
            // rebuild when the tap is born with a dead channel. (A mask-flavor
            // sweep once ran here too; retired 2026-11-04 — every extra client
            // re-configured the shared submix input and re-folded it — and
            // deleted 2026-11-09. Its findings live in issue #101 and audio.md.)
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

    /**
     * Opens the capture record as the FIRST audio session of the cast, before
     * WebRTC ever creates its microphone record — the session-ordering fix
     * for the one-channel fold (issue #101, live evidence 2026-11-04).
     *
     * The mechanism: the input stack allocates a voice-shaped
     * DSP session (16 kHz, mono, channel mask 0x10 — the exact signature of a
     * leaked VOICE_COMMUNICATION input found idling in audioserver) for the
     * ADM's microphone record, and a playback-capture request arriving while
     * that session exists gets remapped onto it — a 48 kHz stereo client fed
     * from a mono/0x10 stream reads one dead channel. Every observation fits:
     * fold flavors (L-only/R-only/L=R), the "perfect 48 kHz thread with folded
     * data", the hard reset never curing (each rebuild re-opens the mic record
     * first, re-applying the poison), and TikTok/scrcpy capturing stereo —
     * neither ever opens a microphone record. TikTok's app side was verified
     * exhaustively first (permissions, privileges, whitelists, dex of its
     * cast AudioThread, registered AudioPolicyConfig): its capture record is
     * API-identical to ours, so the differentiator is not identity but ORDER.
     *
     * Called after the projection is live (capturer started) and well before
     * the ADM record starts at transport time. Best-effort: any failure here
     * just falls back to the old build-at-start path.
     */
    fun preOpenCapture() {
        synchronized(this) {
            if (preOpenedRecord !== null) return
            val liveProjection = projection() ?: return
            val sampleRate = PINNED_INPUT_SAMPLE_RATE
            val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) return
            val record = try {
                buildCaptureRecord(liveProjection, sampleRate, channelCount = 2, readBytes = minBytes * 2)
            } catch (t: Throwable) {
                Log.w(TAG, "pre-opening the capture record failed: ${t.message}")
                null
            } ?: return
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                Log.w(TAG, "pre-opened capture record not initialized — will build at start instead")
                return
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                Log.w(TAG, "pre-opened capture record did not start — will build at start instead")
                return
            }
            preOpenedRecord = record
            Log.i(TAG, "capture record pre-opened: $sampleRate Hz, 2 ch — first audio session of the cast")
        }
    }

    /** Cast teardown: release a pre-opened record the substitution never consumed. */
    fun release() {
        synchronized(this) {
            preOpenedRecord?.let {
                stopAndRelease(it)
                preOpenedRecord = null
            }
        }
        captureDump?.close()
        captureDump = null
    }

    private fun readCaptureDumpSetting(): Boolean =
        try {
            android.provider.Settings.Global.getInt(appContext.contentResolver, CAPTURE_DUMP_SETTING, 0) == 1
        } catch (t: Throwable) {
            false
        }

    /**
     * Starts the bounded WAV dump of the encoder-consumed bytes when the
     * `localcast_capture_dump` global setting is 1 (a development toggle set
     * via adb — never on in normal use, issue #101 diagnostics). Once per
     * cast, at the swap where the encoder's stream begins.
     */
    private fun maybeStartCaptureDump() {
        if (captureDump !== null || !captureDumpArmed) return
        val dir = appContext.getExternalFilesDir(null) ?: return
        val context = swapContext ?: return
        captureDump = AudioCaptureFileRecorder.start(
            dir,
            context.sampleRate,
            context.channelCount,
        )
    }

    // ---- everything below runs on the ADM's recording thread unless noted ----

    private fun substitutePlaybackCaptureRecord() {
        val liveProjection = projection()
        if (liveProjection === null) {
            captureFailed("no live MediaProjection for playback capture")
            return
        }
        try {
            val audioInputField = AdmRecorderInternals.AUDIO_INPUT_FIELD
            val recordField = AdmRecorderInternals.RECORD_FIELD
            val byteBufferField = AdmRecorderInternals.BYTE_BUFFER_FIELD
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
            // The pre-opened record first (session-ordering fix, issue #101):
            // it was opened before this mic record existed, so the input
            // stack could not remap it onto the mic's voice session.
            // A format mismatch means the ADM negotiated differently than the
            // pinned 48 kHz stereo — discard the pre-open and fall back.
            val capture = consumePreOpenedIfCompatible(micRecord) ?: try {
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
            captureDumpArmed = readCaptureDumpSetting()
            maybeStartCaptureDump()
            Log.i(
                TAG,
                "playback capture active: ${capture.sampleRate} Hz, ${capture.channelCount} ch, ${capture.audioFormat}",
            )
        } catch (t: Throwable) {
            captureFailed("substituting playback capture failed: ${t.message}")
        }
    }

    /**
     * Takes the record [preOpenCapture] left, if it mirrors the ADM record's
     * negotiated format byte-for-byte (the recorder thread's reads and the
     * encoder's frame clock assume it). Returns null — releasing the pre-open
     * — when absent, unconsumable, or mismatched, so the caller falls back to
     * building a fresh record the old way. Runs on the ADM's recording thread.
     */
    private fun consumePreOpenedIfCompatible(micRecord: AudioRecord): AudioRecord? {
        synchronized(this) {
            val preOpened = preOpenedRecord ?: return null
            preOpenedRecord = null
            val compatible = isCompatibleSwapFormat(
                preOpened.sampleRate,
                preOpened.channelCount,
                preOpened.state,
                preOpened.recordingState,
                micRecord.sampleRate,
                micRecord.channelCount,
            )
            if (!compatible) {
                Log.w(
                    TAG,
                    "discarding pre-opened capture record " +
                        "(${preOpened.sampleRate} Hz, ${preOpened.channelCount} ch, state ${preOpened.state}) — " +
                        "ADM record is (${micRecord.sampleRate} Hz, ${micRecord.channelCount} ch)",
                )
                stopAndRelease(preOpened)
                return null
            }
            Log.i(TAG, "swapping in the pre-opened capture record — the mic record opened second")
            return preOpened
        }
    }

    /**
     * The capture record: public playback capture only
     * ([AudioPlaybackCaptureConfiguration]), which honours every app's
     * capture opt-out (`allowAudioPlaybackCapture=false`, `USAGE` filters).
     *
     * `AUDIO_SOURCE_REMOTE_SUBMIX` used to be tried first (TikTok's adapter
     * pattern). Removed 2026-11-09 (mobile review, group C): on a ROM that
     * grants it, the submix records the **whole** output mix and bypasses
     * those opt-outs — the privacy argument of audio.md rests on them. On
     * the devices we test it was refused anyway ("Cannot create
     * AudioRecord"), and the stereo it was meant to rescue is solved by the
     * pre-open ordering ([preOpenCapture]).
     */
    private fun buildCaptureRecord(
        projection: MediaProjection,
        micRecord: AudioRecord,
        audioInput: Any,
        byteBufferField: java.lang.reflect.Field,
    ): AudioRecord {
        val readBytes = (byteBufferField.get(audioInput) as java.nio.ByteBuffer).capacity()
        return buildCaptureRecord(projection, micRecord.sampleRate, micRecord.channelCount, readBytes)
    }

    /**
     * Mirror the ADM-negotiated format exactly: the recorder thread's reads
     * and the encoder's frame clock assume it. Playback capture supports a
     * fixed format set (PCM16, CHANNEL_IN_MONO/STEREO, standard rates); a
     * device that negotiated anything else fails honestly → [GameAudioState.Failed].
     * (Output-position and index channel masks were probed live 2026-11-03/04
     * and did not help — index masks are rejected outright; audio.md.)
     */
    private fun buildCaptureRecord(
        projection: MediaProjection,
        sampleRate: Int,
        channelCount: Int,
        readBytes: Int,
    ): AudioRecord {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val formatBuilder = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
        val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val format = formatBuilder.setChannelMask(channelMask).build()
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, encoding)
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            // audio.md usage filter: media, game, unknown — voice apps are
            // never capturable by platform design (their users' privacy).
            .apply {
                for (usage in CAPTURE_USAGES) addMatchingUsage(usage)
            }
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(format)
            // The recording thread reads byteBuffer.capacity() per iteration —
            // the capture record's buffer must hold at least that much.
            .setBufferSizeInBytes(maxOf(minBytes, readBytes))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    /**
     * The self-heal ([MonoTapRecovery]): swap in a *fresh*
     * playback-capture record — the same field write the initial substitution
     * uses, executed on the recording thread between two reads, so the cast
     * never breaks. The old record is stopped only after the swap. Best-effort
     * by design: a failed rebuild keeps the current record streaming and logs.
     *
     * (A "hard" variant that released every record and slept 3 s on this —
     * the ADM's recording — thread was removed 2026-11-09: it was built on the
     * "stuck HAL thread" theory the session-ordering finding replaced, it
     * never held, it cost ~3 s of silence, and a failed rebuild after it left
     * game audio dead for the cast.)
     */
    private fun rebuildCaptureRecord(reason: String) {
        val context = swapContext ?: return
        val liveProjection = projection() ?: return
        val recordField = AdmRecorderInternals.RECORD_FIELD ?: return
        try {
            val fresh = buildCaptureRecord(liveProjection, context.sampleRate, context.channelCount, context.readBytes)
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

    /** Stop whatever record the ADM still holds (best-effort — the lookup may have failed). */
    private fun stopCurrentRecord(audioInputField: java.lang.reflect.Field?) {
        if (audioInputField === null) return
        try {
            (AdmRecorderInternals.RECORD_FIELD?.get(audioInputField.get(adm)) as? AudioRecord)?.let { stopAndRelease(it) }
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
         * The capture format both the ADM record and the pre-opened record are
         * pinned to (audio.md's negotiated format) — see [preOpenCapture] and
         * the builder's [setInputSampleRate] call.
         */
        private const val PINNED_INPUT_SAMPLE_RATE = 48_000

        /** Settings.Global key arming the bounded encoder-input WAV dump. */
        private const val CAPTURE_DUMP_SETTING = "localcast_capture_dump"

        /**
         * audio.md usage filter: media, game, unknown. (TikTok's exact set
         * {MEDIA, 13, 14} was tried live 2026-11-04 with the request proven
         * byte-identical in the APM log — the fold persisted, so the usage
         * set is NOT the differentiator and GAME must stay for PUBG.)
         */
        private val CAPTURE_USAGES = intArrayOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
        )
    }
}

/**
 * Whether the pre-opened capture record may swap in for the ADM record: the
 * recorder thread's reads and the encoder's frame clock assume one format —
 * rate and channels must match exactly, and the record must be initialized
 * AND actively recording (a pre-open that failed to start is worthless).
 */
internal fun isCompatibleSwapFormat(
    preOpenedRate: Int,
    preOpenedChannels: Int,
    preOpenedState: Int,
    preOpenedRecordingState: Int,
    micRate: Int,
    micChannels: Int,
): Boolean = preOpenedRate == micRate &&
    preOpenedChannels == micChannels &&
    preOpenedState == AudioRecord.STATE_INITIALIZED &&
    preOpenedRecordingState == AudioRecord.RECORDSTATE_RECORDING
