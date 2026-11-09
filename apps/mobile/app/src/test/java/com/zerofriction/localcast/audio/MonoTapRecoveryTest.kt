package com.zerofriction.localcast.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-heal's trigger semantics (audio.md): a dead channel counts only
 * while the window is loud, both-channels-alive resets, quiet pauses, the
 * whole thing is bounded, rebuilds are spaced out by a cooldown (the fold
 * recurs at every playback track re-registration — issue #101), and the L=R
 * flavor is bounded by outcome.
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
    fun `shuffling dead sides never exhaust the budget`() {
        // The vendor re-fold flips the dead side across rebuilds — L-dead and
        // R-dead alternating must keep healing far past the content budget.
        val recovery = recovery(consecutiveWindows = 1, maxRebuilds = 2, rebuildIntervalMs = 0)
        repeat(6) { flip ->
            val dead = if (flip % 2 == 0) report(5000, 0) else report(0, 5000)
            assertNotNull("shuffle rebuild $flip must fire", recovery.onReport(dead))
        }
    }

    @Test
    fun `content panning keeps the same side dead and burns the budget`() {
        val recovery = recovery(consecutiveWindows = 1, maxRebuilds = 2, rebuildIntervalMs = 0)
        assertNotNull(recovery.onReport(report(5000, 0)))
        assertNotNull(recovery.onReport(report(5000, 0)))
        // Still left-dead after two rebuilds = content, not the vendor — stop.
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

    private fun dualMono(level: Long = 5000) =
        StereoContentProbe.Report(meanAbsLeft = level, meanAbsRight = level, meanAbsDiff = 0)

    private fun stereo() = StereoContentProbe.Report(meanAbsLeft = 5000, meanAbsRight = 4600, meanAbsDiff = 1200)

    @Test
    fun `a loud L=R fold triggers a rebuild after four windows`() {
        val recovery = recovery()
        repeat(3) { assertNull(recovery.onReport(dualMono())) }
        val reason = recovery.onReport(dualMono())
        assertNotNull("4 s of L=R must rebuild", reason)
        assertTrue(reason!!.contains("L=R"))
    }

    @Test
    fun `a centred passage of a stereo test clip does not rebuild as L=R`() {
        // Live 2026-11-09: left ~3 s, right ~3 s, centre ~3 s, repeating.
        // (The hard-panned sides may trip the dead-channel path — pre-existing,
        // budget-bounded; this test pins only the L=R path.)
        val recovery = recovery()
        val reasons = mutableListOf<String>()
        fun feed(r: StereoContentProbe.Report) { recovery.onReport(r)?.let(reasons::add) }
        repeat(5) {
            repeat(3) { feed(report(3000, 0)) }
            feed(report(0, 0))
            repeat(3) { feed(report(0, 3000)) }
            feed(report(0, 0))
            repeat(3) { feed(dualMono()) }
            feed(stereo())
            advanceSeconds(12)
        }
        assertTrue("centre is content, not a fold: $reasons", reasons.none { it.contains("L=R") })
    }

    @Test
    fun `narrow but real stereo is not dual-mono`() {
        val recovery = recovery()
        // L−R at ~1% of the amplitude: a narrow mix, not a fold.
        val narrow = StereoContentProbe.Report(meanAbsLeft = 5000, meanAbsRight = 4990, meanAbsDiff = 50)
        repeat(20) { assertNull(recovery.onReport(narrow)) }
    }

    @Test
    fun `quiet L=R says nothing`() {
        val recovery = recovery()
        repeat(10) { assertNull(recovery.onReport(dualMono(level = 100))) }
    }

    @Test
    fun `genuinely mono content stops rebuilding after the unhealed bound`() {
        val recovery = recovery()
        var rebuilds = 0
        repeat(120) {
            if (recovery.onReport(dualMono()) != null) rebuilds++
            advanceSeconds(1)
        }
        assertTrue("a mono podcast costs at most two rebuilds, got $rebuilds", rebuilds == MonoTapRecovery.MAX_UNHEALED_DUAL_MONO)
    }

    @Test
    fun `true stereo re-arms the dual-mono bound so every re-fold heals`() {
        val recovery = recovery()
        var rebuilds = 0
        repeat(10) {
            advanceSeconds(30) // stereo holds between re-folds
            repeat(3) { assertNull(recovery.onReport(stereo())) }
            repeat(3) { assertNull(recovery.onReport(dualMono())) }
            if (recovery.onReport(dualMono()) != null) rebuilds++
        }
        assertTrue("each of 10 re-folds must rebuild, got $rebuilds", rebuilds == 10)
    }

    @Test
    fun `a dual-mono rebuild respects the shared cooldown`() {
        val recovery = recovery()
        assertNull(recovery.onReport(report(5000, 0)))
        repeat(3) { recovery.onReport(report(5000, 0)) }
        assertNotNull(recovery.onReport(report(5000, 0))) // dead-channel rebuild
        repeat(3) { assertNull(recovery.onReport(dualMono())) }
        assertNull("inside the cooldown", recovery.onReport(dualMono()))
        advanceSeconds(9)
        assertNotNull("streak stood through the cooldown", recovery.onReport(dualMono()))
    }

    @Test
    fun `quiet splits an L=R streak`() {
        // Live 2026-11-09: MMqqqMM fired as one 4 s fold.
        val recovery = recovery()
        repeat(2) { assertNull(recovery.onReport(dualMono())) }
        repeat(3) { assertNull(recovery.onReport(dualMono(level = 100))) }
        repeat(2) { assertNull("two 2 s centred passages are not a 4 s fold", recovery.onReport(dualMono())) }
    }
}
