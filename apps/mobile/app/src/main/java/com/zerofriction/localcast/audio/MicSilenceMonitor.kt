package com.zerofriction.localcast.audio

/**
 * Pure state machine for the concurrent-capture arbitration (ADR-004,
 * docs/architecture/audio.md): the platform may silence our mic capture when
 * another app's recording has priority — the capture keeps "running" but the
 * desktop would only ever receive digital silence, a silent failure unless
 * surfaced. Fed by the session's `AudioManager.AudioRecordingCallback`.
 *
 * Public APIs cannot say *which* recording is ours (the uid-level accessors
 * are system APIs), so "ours" is matched by audio source — the source we
 * configured the mic record with. A false positive needs another capture on
 * that exact source to be the one silenced; every snapshot is still logged
 * with source/silenced metadata so the Phase 15 matrix can verify from
 * logcat. No audio content ever passes through here (AGENTS.md logging).
 *
 * No Android types — the transitions are unit-testable on the JVM; logging
 * is injected (the Android `Log` is not available in JVM tests).
 */
class MicSilenceMonitor(
    /** The audio source our mic record was configured with (ADR-004). */
    private val ourSource: Int,
    /** Metadata-only diagnostics line per config change (logcat in prod). */
    private val log: (String) -> Unit = {},
    /** Fired only on transitions (never for the initial [MicState.Active]). */
    private val onSignal: (Signal) -> Unit = {},
) {
    sealed interface Signal {
        data object OwnSourceSilenced : Signal
        data object OwnSourceCleared : Signal
    }

    /** The facts the session extracts from one `AudioRecordingConfiguration`. */
    data class Recording(val source: Int, val silenced: Boolean)

    var state: MicState = MicState.Active
        private set

    fun onRecordings(recordings: List<Recording>) {
        // The playback-capture record (factory A) is not part of the mic
        // arbitration — only microphone-like sources can be silenced against
        // our capture, so it never enters the decision.
        val micRecordings = recordings.filter { it.source != REMOTE_SUBMIX }
        val silencedSources = micRecordings.filter { it.silenced }.map { it.source }
        log(
            "active mic recordings=${micRecordings.size} " +
                "sources=${micRecordings.map { it.source }} " +
                "silenced=${silencedSources}",
        )
        val next = if (ourSource in silencedSources) MicState.Silenced else MicState.Active
        if (state != next) {
            state = next
            onSignal(
                if (next == MicState.Silenced) {
                    Signal.OwnSourceSilenced
                } else {
                    Signal.OwnSourceCleared
                },
            )
        }
    }

    companion object {
        /**
         * Same value as android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX
         * (inlined so the pure class stays free of Android types).
         */
        const val REMOTE_SUBMIX = 8
    }
}
