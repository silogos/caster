package com.zerofriction.localcast.audio

import com.zerofriction.localcast.audio.MonoTapRecovery.Companion.HARD_RESET_MARKER
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-heal's trigger semantics (audio.md): a dead channel counts only
 * while the window is loud, both-channels-alive resets, quiet pauses, the
 * whole thing is bounded, rebuilds are spaced out by a cooldown (the fold
 * recurs at every playback track re-registration — issue #101), and the hard
 * reset lands on every third rebuild.
 */
class MonoTapRecoveryTest {
    private var now = 1_000_000L
    private val clock: () -> Long = { now }

    private fun recovery(
        consecutiveWindows: Int = 5,
        maxRebuilds: Int = 12,
        rebuildIntervalMs: Long = MonoTapRecovery.REBUILD_INTERVAL_MS,
    ) = MonoTapRecovery(maxRebuilds, consecutiveWindows, rebuildIntervalMs, clock)

    private fun report(left: Long, right: Long) =
        StereoContentProbe.Report(meanAbsLeft = left, meanAbsRight = right, meanAbsDiff = left - right)

    private fun advanceSeconds(seconds: Long) { now += seconds * 1_000 }

    @Test
    fun `five consecutive dead-channel windows trigger a rebuild`() {
        val recovery = recovery(consecutiveWindows = 5)
        repeat(4) { assertNull(recovery.onReport(report(5000, 0))) }
        assertNotNull(recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `both channels alive resets the streak`() {
        val recovery = recovery(consecutiveWindows = 3)
        assertNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(5000, 4800))) // stereo/mono-duplicated — healthy
        assertNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(5000, 0)))
        assertNotNull("streak restarted after the healthy window", recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `quiet windows pause the streak without resetting it`() {
        val recovery = recovery(consecutiveWindows = 3)
        assertNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(0, 0))) // silence — no information
        assertNull(recovery.onReport(report(5000, 0)))
        assertNotNull(recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `hard-panned but alive content is not dead`() {
        val recovery = recovery(consecutiveWindows = 2)
        // 3% leakage of the loud channel — panned, not dead (ratio 1/50).
        assertNull(recovery.onReport(report(5000, 150)))
        assertNull(recovery.onReport(report(5000, 150)))
        assertNull(recovery.onReport(report(5000, 150)))
    }

    @Test
    fun `rebuilds are bounded`() {
        val recovery = recovery(consecutiveWindows = 1, maxRebuilds = 2, rebuildIntervalMs = 0)
        assertNotNull(recovery.onReport(report(5000, 0)))
        assertNotNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `a quiet cast never triggers`() {
        val recovery = recovery(consecutiveWindows = 1)
        repeat(10) { assertNull(recovery.onReport(report(100, 0))) }
    }

    @Test
    fun `a rebuild inside the cooldown is held back but the streak stands`() {
        val recovery = recovery(consecutiveWindows = 2, rebuildIntervalMs = 8_000)
        advanceSeconds(100)
        assertNull(recovery.onReport(report(5000, 0))) // streak 1
        assertNotNull(recovery.onReport(report(5000, 0))) // streak 2 — first rebuild
        advanceSeconds(1) // next two dead windows land inside the 8 s cooldown
        assertNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(5000, 0)))
        advanceSeconds(2) // cooldown passed — the standing streak fires immediately
        advanceSeconds(8)
        assertNotNull(recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `a healthy window inside the cooldown resets the streak`() {
        val recovery = recovery(consecutiveWindows = 2, rebuildIntervalMs = 8_000)
        advanceSeconds(100)
        assertNull(recovery.onReport(report(5000, 0)))
        assertNotNull(recovery.onReport(report(5000, 0))) // first rebuild
        advanceSeconds(1)
        assertNull(recovery.onReport(report(5000, 0))) // cooldown hold, streak = 1
        assertNull(recovery.onReport(report(5000, 4800))) // healthy — streak resets
        advanceSeconds(2)
        assertNull(recovery.onReport(report(5000, 0))) // streak restarted from scratch
        advanceSeconds(8)
        assertNotNull("full streak needed after the healthy window", recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `every third rebuild is the hard reset`() {
        val recovery = recovery(consecutiveWindows = 1, maxRebuilds = 12, rebuildIntervalMs = 0)
        val first = recovery.onReport(report(5000, 0))!!
        val second = recovery.onReport(report(5000, 0))!!
        val third = recovery.onReport(report(5000, 0))!!
        assertFalse(first.contains(HARD_RESET_MARKER))
        assertFalse(second.contains(HARD_RESET_MARKER))
        assertTrue("third rebuild must carry the marker", third.contains(HARD_RESET_MARKER))
    }

    @Test
    fun `the budget outlasts a session of track-change re-folds`() {
        // A YouTube-style session: a re-fold every ~40 s, each clearing after
        // one rebuild (5 dead windows then stereo again).
        val recovery = recovery(consecutiveWindows = 5, maxRebuilds = 12)
        var rebuilds = 0
        repeat(12) { flip ->
            advanceSeconds(40) // stereo holds between the app's track changes
            repeat(4) { assertNull(recovery.onReport(report(5000, 0))) }
            assertNotNull("rebuild ${flip + 1} must fire", recovery.onReport(report(5000, 0)))
            rebuilds++
            advanceSeconds(1)
            repeat(3) { assertNull(recovery.onReport(report(5000, 4000))) } // stereo again
        }
        assertTrue(rebuilds >= 10)
    }
}
