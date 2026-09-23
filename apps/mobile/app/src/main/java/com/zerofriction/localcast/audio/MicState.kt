package com.zerofriction.localcast.audio

/**
 * The microphone half of the cast's user-facing state (docs/architecture/
 * audio.md, Phase8) — rendered 1:1 by the home and scan screens while a cast
 * runs. Deliberately separate from [GameAudioState]: the two tracks are
 * separate products end to end, and one must never gate the other.
 *
 * There is no "quiet" state here (unlike game audio's Silent): a quiet
 * microphone is normal and never a fact worth surfacing. [Silenced] is a
 * different fact — the platform itself muted the capture under the
 * concurrent-capture policy while another app owns the input (ADR-004).
 */
sealed interface MicState {
    /**
     * Not part of this cast: the user hasn't turned it on for this cast
     * (audio.md: mic is off by default and enabled by an explicit toggle).
     */
    data object Off : MicState

    /** Capturing; the mic is on its way to (or already) the desktop. */
    data object Active : MicState

    /**
     * The capture started but the platform silenced it — another app's
     * recording has priority under the concurrent-capture rules, so the
     * desktop would only ever receive digital silence. Recovers by itself
     * if that app stops recording (ADR-004).
     */
    data object Silenced : MicState

    /** RECORD_AUDIO is denied — mic can't join this cast. */
    data object NeedsPermission : MicState

    /** Capture couldn't start or died mid-cast — the cast itself keeps running. */
    data object Failed : MicState
}
