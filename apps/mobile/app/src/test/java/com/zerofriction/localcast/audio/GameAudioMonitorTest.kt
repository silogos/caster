package com.zerofriction.localcast.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silence/mute state machine behind the game-audio UI (audio.md):
 * silence only counts while connected and not muted, it must be sustained,
 * it recovers on the first audible frame, and capture failure is terminal.
 */
class GameAudioMonitorTest {

    private fun monitor(silentAfterMs: Long = GameAudioMonitor.SILENT_AFTER_MS): Pair<GameAudioMonitor, MutableList<GameAudioState>> {
        val seen = mutableListOf<GameAudioState>()
        return GameAudioMonitor(silentAfterMs = silentAfterMs, onState = { seen.add(it) }) to seen
    }

    @Test
    fun `starts active and publishes nothing until a transition happens`() {
        val (m, seen) = monitor()
        assertEquals(GameAudioState.Active, m.state.value)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `sustained silence while streaming becomes silent`() {
        val (m, seen) = monitor(silentAfterMs = 5_000)
        m.setStreaming(true)
        m.onFrame(atMs = 1_000, silent = true)
        // Still inside the window at 5_999 ms.
        m.onFrame(atMs = 5_999, silent = true)
        assertEquals(GameAudioState.Active, m.state.value)
        m.onFrame(atMs = 6_001, silent = true)
        assertEquals(GameAudioState.Silent, m.state.value)
        assertEquals(listOf(GameAudioState.Silent), seen)
    }

    @Test
    fun `silence before the desktop is connected never becomes silent`() {
        val (m, _) = monitor(silentAfterMs = 100)
        m.onFrame(atMs = 0, silent = true)
        m.onFrame(atMs =10_000, silent = true)
        assertEquals(GameAudioState.Active, m.state.value)
        // Connecting now must start a fresh window, not inherit the old silence.
        m.setStreaming(true)
        m.onFrame(atMs =10_050, silent = false)
        m.onFrame(atMs =20_000, silent = true)
        assertEquals(GameAudioState.Active, m.state.value)
    }

    @Test
    fun `one audible frame recovers from silent`() {
        val (m, seen) = monitor(silentAfterMs = 100)
        m.setStreaming(true)
        m.onFrame(atMs = 0, silent = true)
        m.onFrame(atMs =200, silent = true)
        assertEquals(GameAudioState.Silent, m.state.value)
        m.onFrame(atMs =210, silent = false)
        assertEquals(GameAudioState.Active, m.state.value)
        assertEquals(listOf(GameAudioState.Silent, GameAudioState.Active), seen)
    }

    @Test
    fun `user mute wins over silence detection and unmute starts a fresh window`() {
        val (m, seen) = monitor(silentAfterMs = 100)
        m.setStreaming(true)
        m.setUserMuted(true)
        assertEquals(GameAudioState.Muted, m.state.value)
        // Muted frames are zeroed by the ADM — they must not read as "can't be captured".
        m.onFrame(atMs = 0, silent = true)
        m.onFrame(atMs = 1_000, silent = true)
        assertEquals(GameAudioState.Muted, m.state.value)
        m.setUserMuted(false)
        assertEquals(GameAudioState.Active, m.state.value)
        // The mute-era silence doesn't carry over: the window restarts here.
        m.onFrame(atMs =2_000, silent = true)
        assertEquals(GameAudioState.Active, m.state.value)
        m.onFrame(atMs = 2_100, silent = true)
        assertEquals(GameAudioState.Silent, m.state.value)
        assertEquals(listOf(GameAudioState.Muted, GameAudioState.Active, GameAudioState.Silent), seen)
    }

    @Test
    fun `capture failure is terminal and ignores later mute`() {
        val (m, seen) = monitor()
        m.setStreaming(true)
        m.onCaptureFailed()
        m.onCaptureFailed() // idempotent
        m.setUserMuted(true)
        m.onFrame(atMs = 0, silent =true)
        assertEquals(GameAudioState.Failed, m.state.value)
        assertEquals(listOf(GameAudioState.Failed), seen)
    }

    @Test
    fun `rejects a non-positive silence window`() {
        try {
            GameAudioMonitor(silentAfterMs = 0)
            throw AssertionError("expected require failure")
        } catch (expected: IllegalArgumentException) {
            // named constant sanity, nothing else to assert
        }
    }

    // ---- PcmSilence: the raw signal silence detection is built on ----

    @Test
    fun `all-zero pcm frame is silent`() {
        assertTrue(PcmSilence.isSilent(ByteArray(0)))
        assertTrue(PcmSilence.isSilent(byteArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun `any nonzero byte makes a frame audible`() {
        assertEquals(false, PcmSilence.isSilent(byteArrayOf(0,1, 0)))
        assertEquals(false, PcmSilence.isSilent(byteArrayOf(0, 0, -1)))
    }
}
