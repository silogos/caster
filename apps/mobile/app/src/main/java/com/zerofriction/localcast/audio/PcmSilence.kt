package com.zerofriction.localcast.audio

/**
 * One question about a captured PCM frame: is it digital zero? Opted-out
 * playback capture yields silence (docs/architecture/audio.md), and this is
 * the only signal available to distinguish it from audible content.
 */
internal object PcmSilence {
    fun isSilent(samples: ByteArray): Boolean {
        for (b in samples) {
            if (b.toInt() != 0) return false
        }
        return true
    }
}
