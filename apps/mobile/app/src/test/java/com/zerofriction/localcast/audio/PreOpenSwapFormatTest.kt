package com.zerofriction.localcast.audio

import android.media.AudioRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pre-open swap gate (session-ordering fix, issue #101): a record opened
 * before the ADM's microphone record replaces it ONLY at the exact format the
 * recorder thread and encoder expect — otherwise the swap would corrupt the
 * frame clock and the old build-at-start path must run instead.
 */
class PreOpenSwapFormatTest {
    private val state = AudioRecord.STATE_INITIALIZED
    private val recording = AudioRecord.RECORDSTATE_RECORDING

    @Test
    fun `pinned 48k stereo pre-open swaps into a 48k stereo ADM record`() {
        assertEquals(
            true,
            isCompatibleSwapFormat(48_000, 2, state, recording, micRate = 48_000, micChannels = 2),
        )
    }

    @Test
    fun `a rate mismatch falls back to building at start`() {
        assertEquals(
            false,
            isCompatibleSwapFormat(44_100, 2, state, recording, micRate = 48_000, micChannels = 2),
        )
    }

    @Test
    fun `a mono pre-open never swaps into a stereo ADM record`() {
        assertEquals(
            false,
            isCompatibleSwapFormat(48_000, 1, state, recording, micRate = 48_000, micChannels = 2),
        )
    }

    @Test
    fun `a pre-open that failed to initialize is discarded`() {
        assertEquals(
            false,
            isCompatibleSwapFormat(48_000, 2, AudioRecord.STATE_UNINITIALIZED, recording, micRate = 48_000, micChannels = 2),
        )
    }

    @Test
    fun `a pre-open that never started recording is discarded`() {
        assertEquals(
            false,
            isCompatibleSwapFormat(48_000, 2, state, AudioRecord.RECORDSTATE_STOPPED, micRate = 48_000, micChannels = 2),
        )
    }
}
