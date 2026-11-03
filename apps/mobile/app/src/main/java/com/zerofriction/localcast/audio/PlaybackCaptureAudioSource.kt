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

    /**
     * The channel-mask flavor the main record builds with. The sweep adopts
     * a healthier flavor when it finds one (found live: the test ROM's tap
     * delivers one channel for [MaskKind.IN] records — see [sweepCaptureFlavors]).
     */
    @Volatile
    private var preferredMask: MaskKind = MaskKind.IN

    /** A rebuild requested off the recording thread (sweep adoption) — consumed per frame. */
    @Volatile
    private var pendingRebuildReason: String? = null

    /** The flavor sweep runs once, at the first loud window (a silent sweep measures nothing). */
    @Volatile
    private var sweepStarted = false

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
            // carries L≠R, one report per second, a bounded capture-record
            // rebuild when the tap is born with a dead channel, and a
            // mask-flavor sweep at the first loud window.
            stereoContentProbe.onSamples(samples.data)?.let { report ->
                Log.i(TAG, "capture stereo content: ${report.summarize()}")
                if (!sweepStarted && report.amplitude >= MonoTapRecovery.MIN_AMPLITUDE) {
                    sweepStarted = true
                    swapContext?.let { sweepCaptureFlavors(it.sampleRate) }
                }
                pendingRebuildReason?.let { reason ->
                    pendingRebuildReason = null
                    rebuildCaptureRecord(reason)
                }
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

    // ---- everything below runs on the ADM's recording thread unless noted ----

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
                buildBestCaptureRecord(liveProjection, micRecord, audioInput, byteBufferField)
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
            if (capture.channelCount == 2) AudioPolicySinkProbe.run(appContext)
        } catch (t: Throwable) {
            captureFailed("substituting playback capture failed: ${t.message}")
        }
    }

    /**
     * TikTok's adapter pattern (decoded from its cast APK, dumpsys-confirmed
     * on this ROM): try `AUDIO_SOURCE_REMOTE_SUBMIX` FIRST — the routed
     * loopback that stays true stereo on the Lenovo ROM whose playback-capture
     * remix folds to one channel. It must be attempted before any
     * playback-capture record exists (the submix input is single-client on
     * this ROM — "Cannot create AudioRecord" while our capture holds it), and
     * anywhere it is refused (other ROMs enforce CAPTURE_AUDIO_OUTPUT), the
     * public playback-capture path remains the fallback. The record mirrors
     * the ADM-negotiated format either way, so the swap-in is identical.
     */
    private fun buildBestCaptureRecord(
        projection: MediaProjection,
        micRecord: AudioRecord,
        audioInput: Any,
        byteBufferField: java.lang.reflect.Field,
    ): AudioRecord {
        val readBytes = (byteBufferField.get(audioInput) as java.nio.ByteBuffer).capacity()
        val submix = try {
            val record = AudioRecord.Builder()
                .setAudioSource(REMOTE_SUBMIX_AUDIO_SOURCE)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(micRecord.sampleRate)
                        .setChannelMask(
                            if (micRecord.channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
                        )
                        .build(),
                )
                .setBufferSizeInBytes(
                    maxOf(
                        AudioRecord.getMinBufferSize(micRecord.sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT),
                        readBytes,
                    ),
                )
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "capture via REMOTE_SUBMIX (stereo loopback) — playback capture stays the fallback")
                record
            } else {
                record.release()
                null
            }
        } catch (t: Throwable) {
            Log.i(TAG, "REMOTE_SUBMIX unavailable (${t.javaClass.simpleName}) — falling back to playback capture")
            null
        }
        return submix ?: buildCaptureRecord(projection, micRecord, audioInput, byteBufferField)
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
            buildCaptureRecord(projection, micRecord.sampleRate, micRecord.channelCount, readBytes, preferredMask)
        } catch (t: Throwable) {
            Log.w(TAG, "capture build with $preferredMask failed (${t.message}) — falling back to ${MaskKind.IN}")
            buildCaptureRecord(projection, micRecord.sampleRate, micRecord.channelCount, readBytes, MaskKind.IN)
        }
    }

    /**
     * The mask flavor is the one variable the tap's remix can differ on
     * (found live 2026-11-03/04: [MaskKind.IN] records got one channel; the
     * tapped mix is an *output* mix, so [MaskKind.OUT] addresses its channels
     * by their own positions; [MaskKind.INDEX] addresses them by index).
     */
    internal enum class MaskKind(val label: String) {
        IN("position-in"),
        OUT("position-out"),
        INDEX("index"),
    }

    private fun buildCaptureRecord(
        projection: MediaProjection,
        sampleRate: Int,
        channelCount: Int,
        readBytes: Int,
        mask: MaskKind = MaskKind.IN,
        encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
        usages: IntArray? = null,
    ): AudioRecord {
        val formatBuilder = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
        val format = when {
            channelCount != 2 -> formatBuilder.setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            mask == MaskKind.INDEX -> formatBuilder.setChannelIndexMask(STEREO_CHANNEL_INDEX_MASK).build()
            mask == MaskKind.OUT -> formatBuilder.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            else -> formatBuilder.setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        }
        // Buffer sizing uses the IN-stereo min regardless of mask flavor —
        // same channel count, so the requirement is identical per encoding.
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, encoding)
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            // audio.md usage filter: media, game, unknown — voice apps are
            // never capturable by platform design (their users' privacy).
            // The sweep also measures alternative sets (TikTok's cast ships
            // {MEDIA, 13, 14} — decoded from its APK — no GAME/UNKNOWN).
            .apply {
                for (usage in usages ?: DEFAULT_CAPTURE_USAGES) addMatchingUsage(usage)
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
     * The self-heal ([MonoTapRecovery] + sweep adoption): swap in a *fresh*
     * playback-capture record — the same field write the initial substitution
     * uses, executed on the recording thread between two reads, so the cast
     * never breaks. The old record is stopped only after the swap. Best-effort
     * by design: a failed rebuild keeps the current record streaming and logs.
     */
    private fun rebuildCaptureRecord(reason: String) {
        val context = swapContext ?: return
        val liveProjection = projection() ?: return
        val recordField = RECORD_FIELD ?: return
        try {
            val fresh = try {
                // Submix first (the adapter pattern), playback capture as fallback.
                try {
                    val submix = AudioRecord.Builder()
                        .setAudioSource(REMOTE_SUBMIX_AUDIO_SOURCE)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(context.sampleRate)
                                .setChannelMask(
                                    if (context.channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
                                )
                                .build(),
                        )
                        .setBufferSizeInBytes(context.readBytes * 2)
                        .build()
                    if (submix.state == AudioRecord.STATE_INITIALIZED) submix else {
                        submix.release()
                        buildCaptureRecord(liveProjection, context.sampleRate, context.channelCount, context.readBytes, preferredMask)
                    }
                } catch (t: Throwable) {
                    buildCaptureRecord(liveProjection, context.sampleRate, context.channelCount, context.readBytes, preferredMask)
                }
            } catch (t: Throwable) {
                try {
                    Log.w(TAG, "capture rebuild with $preferredMask failed (${t.message}) — retrying with ${MaskKind.IN}")
                    buildCaptureRecord(liveProjection, context.sampleRate, context.channelCount, context.readBytes, MaskKind.IN)
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
     * Reads standalone playback-capture records **outside the ADM**, one per
     * mask/rate flavor, **while the main path reports loud audio** (the first
     * attempts ran before any audio played and measured silence — useless).
     * Each flavor reads until it has two loud one-second aggregates or times
     * out (4 s). If the OUT mask delivers both channels where IN does not,
     * the main record is rebuilt with it — the tapped mix is an output mix,
     * and a remix that mishandles input-position masks can still map output
     * positions correctly. Runs on its own thread; numbers only, never PCM.
     */
    private fun sweepCaptureFlavors(sampleRate: Int) {
        Thread({
            val liveProjection = projection() ?: return@Thread
            val inReport = flavorReport(liveProjection, sampleRate, MaskKind.IN)
            val outReport = flavorReport(liveProjection, sampleRate, MaskKind.OUT)
            flavorReport(liveProjection, 44100, MaskKind.IN)?.let {
                Log.i(TAG, "capture flavor sweep (position-in 44.1k): ${it.summarize()}")
            }
            // Usage-set variants — TikTok's cast APK ships {MEDIA, 13, 14}
            // (decoded from its dex): the one real config difference left.
            flavorReport(liveProjection, sampleRate, MaskKind.IN, usages = MEDIA_ONLY_USAGES)?.let {
                Log.i(TAG, "capture flavor sweep (usages media-only): ${it.summarize()}")
            }
            flavorReport(liveProjection, sampleRate, MaskKind.IN, usages = TIKTOK_USAGES)?.let {
                Log.i(TAG, "capture flavor sweep (usages media+13+14): ${it.summarize()}")
            }
            flavorReport(liveProjection, sampleRate, MaskKind.IN, usages = MEDIA_GAME_USAGES)?.let {
                Log.i(TAG, "capture flavor sweep (usages media+game): ${it.summarize()}")
            }
            // The docs' example encoding — the float remix route may differ.
            flavorReport(liveProjection, sampleRate, MaskKind.IN, encoding = AudioFormat.ENCODING_PCM_FLOAT)?.let {
                Log.i(TAG, "capture flavor sweep (pcm float): ${it.summarize()}")
            }
            // THE lead: TikTok's cast on this ROM runs on AUDIO_SOURCE_REMOTE_SUBMIX
            // (dumpsys during its stereo cast) — the routed-loopback source,
            // not playback capture. Normally gated by the signature
            // CAPTURE_AUDIO_OUTPUT; this ROM may or may not check per app.
            remoteSubmixReport(sampleRate)?.let {
                Log.i(TAG, "capture flavor sweep (REMOTE_SUBMIX): ${it.summarize()}")
            }
            if (inReport === null || outReport === null) return@Thread
            val outHealthy = !outReport.oneChannelDead()
            val inDead = inReport.oneChannelDead()
            if (outHealthy && inDead && preferredMask != MaskKind.OUT) {
                preferredMask = MaskKind.OUT
                pendingRebuildReason = "sweep adopted the OUT mask (IN: ${inReport.summarize()} / OUT: ${outReport.summarize()})"
                Log.i(TAG, "capture flavor sweep: adopting the OUT mask — rebuild queued")
            } else {
                Log.i(TAG, "capture flavor sweep: keeping $preferredMask (IN ${inReport.summarize()} / OUT ${outReport.summarize()})")
            }
        }, "capture-flavor-sweep").start()
    }

    /**
     * The TikTok-mechanism probe (dumpsys-proven during its stereo cast): an
     * [AudioRecord] on `AUDIO_SOURCE_REMOTE_SUBMIX` — the routed-loopback
     * source that bypasses the broken playback-capture remix entirely.
     * Normally requires the signature-level CAPTURE_AUDIO_OUTPUT; whether
     * this ROM enforces that per app is exactly what this probe answers.
     */
    private fun remoteSubmixReport(sampleRate: Int): StereoContentProbe.Report? {
        return try {
            val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioSource(REMOTE_SUBMIX_AUDIO_SOURCE)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minBytes * 4)
                .build()
            record.startRecording()
            val probe = StereoContentProbe(framesPerReport = sampleRate)
            val buffer = ByteArray(minBytes)
            var report: StereoContentProbe.Report? = null
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) break
                val aggregate = probe.onSamples(buffer.copyOf(read)) ?: continue
                Log.i(TAG, "capture flavor (REMOTE_SUBMIX): ${aggregate.summarize()}")
                if (aggregate.amplitude >= MonoTapRecovery.MIN_AMPLITUDE) {
                    report = aggregate
                    break
                }
            }
            stopAndRelease(record)
            report
        } catch (t: Throwable) {
            Log.w(TAG, "capture flavor (REMOTE_SUBMIX) failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** One flavor's loud window, or null if it could not be measured (quiet/failed). */
    private fun flavorReport(
        projection: MediaProjection,
        sampleRate: Int,
        mask: MaskKind,
        encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
        usages: IntArray? = null,
    ): StereoContentProbe.Report? {
        val label = "${mask.label} ${sampleRate / 1000}k" +
            (if (usages !== null) " usages[${usages.joinToString(",")}]" else "") +
            (if (encoding != AudioFormat.ENCODING_PCM_16BIT) " float" else "")
        return try {
            val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, encoding)
            val record = buildCaptureRecord(projection, sampleRate, channelCount = 2, readBytes = minBytes * 4, mask, encoding, usages)
            record.startRecording()
            val probe = StereoContentProbe(framesPerReport = sampleRate)
            val byteBuffer = ByteArray(minBytes)
            val floatBuffer = FloatArray(minBytes / 4)
            var report: StereoContentProbe.Report? = null
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val aggregate: StereoContentProbe.Report?
                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    val read = record.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) break
                    aggregate = probe.onSamples(floatBuffer.copyOf(read))
                } else {
                    val read = record.read(byteBuffer, 0, byteBuffer.size)
                    if (read <= 0) break
                    aggregate = probe.onSamples(byteBuffer.copyOf(read))
                }
                if (aggregate === null) continue
                Log.i(TAG, "capture flavor ($label): ${aggregate.summarize()}")
                if (aggregate.amplitude >= MonoTapRecovery.MIN_AMPLITUDE) {
                    report = aggregate
                    break
                }
            }
            stopAndRelease(record)
            report
        } catch (t: Throwable) {
            Log.w(TAG, "capture flavor ($label) failed: ${t.message}")
            null
        }
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

        /** audio.md usage filter: media, game, unknown. */
        private val DEFAULT_CAPTURE_USAGES = intArrayOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
        )
        private val MEDIA_ONLY_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA)
        private val MEDIA_GAME_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)

        /** TikTok's cast set, decoded from its APK: {MEDIA, 13, 14}. */
        private val TIKTOK_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, 13, 14)

        /** MediaRecorder.AudioSource.REMOTE_SUBMIX — hidden from the SDK, its value forever 8. */
        private const val REMOTE_SUBMIX_AUDIO_SOURCE = 8

        /**
         * Lazy lookups, never class-init: a library update that renames the
         * internals degrades the cast to video-only ([GameAudioState.Failed])
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
