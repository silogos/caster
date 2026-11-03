package com.zerofriction.localcast.audio

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The self-heal's trigger semantics (audio.md): a dead channel counts only
 * while the window is loud, both-channels-alive resets, quiet pauses, and
 * the whole thing is bounded — behavior, not implementation.
 */
class MonoTapRecoveryTest {

    private fun report(left: Long, right: Long) =
        StereoContentProbe.Report(meanAbsLeft = left, meanAbsRight = right, meanAbsDiff = left - right)

    @Test
    fun `five consecutive dead-channel windows trigger a rebuild`() {
        val recovery = MonoTapRecovery(consecutiveWindows = 5, maxRebuilds = 3)
        repeat(4) { assertNull(recovery.onReport(report(5000, 0))) }
        assertNotNull(recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `both channels alive resets the streak`() {
        val recovery = MonoTapRecovery(consecutiveWindows = 3, maxRebuilds = 3)
        assertNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(5000, 4800))) // stereo/mono-duplicated — healthy
        assertNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(5000, 0)))
        assertNotNull("streak restarted after the healthy window", recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `quiet windows pause the streak without resetting it`() {
        val recovery = MonoTapRecovery(consecutiveWindows = 3, maxRebuilds = 3)
        assertNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(0, 0))) // silence — no information
        assertNull(recovery.onReport(report(5000, 0)))
        assertNotNull(recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `hard-panned but alive content is not dead`() {
        val recovery = MonoTapRecovery(consecutiveWindows = 2, maxRebuilds = 3)
        // 3% leakage of the loud channel — panned, not dead (ratio 1/50).
        assertNull(recovery.onReport(report(5000, 150)))
        assertNull(recovery.onReport(report(5000, 150)))
        assertNull(recovery.onReport(report(5000, 150)))
    }

    @Test
    fun `rebuilds are bounded`() {
        val recovery = MonoTapRecovery(consecutiveWindows = 1, maxRebuilds = 2)
        assertNotNull(recovery.onReport(report(5000, 0)))
        assertNotNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(5000, 0)))
        assertNull(recovery.onReport(report(5000, 0)))
    }

    @Test
    fun `a quiet cast never triggers`() {
        val recovery = MonoTapRecovery(consecutiveWindows = 1, maxRebuilds = 3)
        repeat(10) { assertNull(recovery.onReport(report(100, 0))) }
    }
}
