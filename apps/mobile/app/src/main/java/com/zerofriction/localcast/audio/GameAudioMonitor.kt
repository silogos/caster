package com.zerofriction.localcast.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure state machine behind [GameAudioState]: turns the raw facts (frame
 * silence, user mute, connection state, capture failure) into the states the
 * UI renders. No Android types — the transitions are unit-testable on the JVM.
 *
 * Silence policy (docs/architecture/audio.md): opted-out apps produce silence,
 * not errors, so only a *sustained* run of zero samples *while the desktop is
 * connected and the user hasn't muted* may surface as [GameAudioState.Silent].
 * One audible frame clears it — the state recovers, it never escalates to an
 * error. Callers deliver frames from the ADM's recording thread; fields are
 * volatile because mute/streaming can be set from the session thread.
 */
class GameAudioMonitor(
    /** Sustained silence window before the UI may say "can't be captured". */
    private val silentAfterMs: Long = SILENT_AFTER_MS,
    /** Fired on every state transition (never for the initial [GameAudioState.Active]). */
    private val onState: (GameAudioState) -> Unit = {},
) {
    init {
        require(silentAfterMs > 0) { "silentAfterMs must be positive" }
    }

    private val _state = MutableStateFlow<GameAudioState>(GameAudioState.Active)
    val state: StateFlow<GameAudioState> = _state.asStateFlow()

    @Volatile private var failed = false
    @Volatile private var muted = false
    @Volatile private var streaming = false
    @Volatile private var silentSinceMs: Long? = null

    fun setUserMuted(muted: Boolean) {
        if (failed) return
        this.muted = muted
        // A mute window is intentional; a silence window starts fresh on unmute.
        silentSinceMs = null
        publish(if (muted) GameAudioState.Muted else GameAudioState.Active)
    }

    /** The `media` pc reached (or left) the connected state — silence only counts while connected. */
    fun setStreaming(streaming: Boolean) {
        this.streaming = streaming
        silentSinceMs = null
    }

    /** One ~10 ms capture frame; `atMs` is the caller's clock (test-injectable). */
    fun onFrame(atMs: Long, silent: Boolean) {
        if (failed || muted || !streaming) {
            silentSinceMs = null
            return
        }
        if (!silent) {
            if (_state.value == GameAudioState.Silent) publish(GameAudioState.Active)
            silentSinceMs = null
            return
        }
        val since = silentSinceMs
        if (since === null) {
            silentSinceMs = atMs
        } else if (atMs - since >= silentAfterMs && _state.value != GameAudioState.Silent) {
            publish(GameAudioState.Silent)
        }
    }

    /** Capture never started or died mid-cast — terminal for this cast's game audio. */
    fun onCaptureFailed() {
        if (failed) return
        failed = true
        publish(GameAudioState.Failed)
    }

    private fun publish(next: GameAudioState) {
        _state.value = next
        onState(next)
    }

    companion object {
        /** 5 s of continuous digital zero while connected reads as "app opted out". */
        const val SILENT_AFTER_MS = 5_000L
    }
}
