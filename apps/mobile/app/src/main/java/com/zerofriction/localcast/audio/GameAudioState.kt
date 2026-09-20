package com.zerofriction.localcast.audio

/**
 * The game-audio half of the cast's user-facing state (docs/architecture/
 * audio.md) — rendered 1:1 by the home screen while a cast runs. Mic audio
 * (Phase8) is deliberately not represented here; the two tracks are separate
 * products end to end.
 */
sealed interface GameAudioState {
    /** Not part of this cast: game audio off in config, or RECORD_AUDIO denied. */
    data object Off : GameAudioState

    /** Capturing; audible samples are flowing to the desktop. */
    data object Active : GameAudioState

    /** The user muted game audio — the silence is intentional. */
    data object Muted : GameAudioState

    /**
     * Sustained digital silence while connected and not muted (audio.md): the
     * app being played has opted out of capture. Surfaced honestly as a fact,
     * never as a technical error.
     */
    data object Silent : GameAudioState

    /** Capture couldn't start or died mid-cast — game audio is unavailable. */
    data object Failed : GameAudioState
}
