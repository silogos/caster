package com.zerofriction.localcast.debug

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Debug-only tone source for verifying game-audio capture end to end
 * (features/game-audio.md): apps targeting API29+ are NOT capturable unless
 * they opt in — which rules out YouTube and most modern apps — so the test
 * signal comes from this app itself (`allowAudioPlaybackCapture="true"` in
 * the manifest). A loud continuous DTMF tone on STREAM_MUSIC (usage MEDIA,
 * inside the capture usage filter) played while a cast runs must be audible
 * on the desktop and must flip the home screen's game-audio state.
 */
object DebugTestTone {
    private var toneGenerator: ToneGenerator? = null

    fun start() {
        if (toneGenerator !== null) return
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME).also {
            it.startTone(ToneGenerator.TONE_DTMF_1, DURATION_INDEFINITE)
        }
    }

    fun stop() {
        toneGenerator?.release()
        toneGenerator = null
    }

    private const val VOLUME = 80
    private const val DURATION_INDEFINITE = -1
}
