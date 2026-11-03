package com.zerofriction.localcast.audio

/**
 * Decides when a mono-born playback-capture tap (found live 2026-11-03 on the
 * test ROM: the tap comes up with one channel dead — L-only or R-only — while
 * AudioFlinger's tracks are stereo) should be rebuilt. Fed one
 * [StereoContentProbe.Report] per second by the capture source; when the
 * quiet channel stays dead for [CONSECUTIVE_WINDOWS] loud seconds, the next
 * report asks for a capture-record rebuild — the swap that fixes the tap's
 * channel state, bounded to [MAX_REBUILDS] per cast so genuine hard-panned
 * content cannot loop the app through rebuilds forever.
 *
 * Window semantics:
 * - **loud + one channel dead** — counts toward the rebuild;
 * - **loud + both channels alive** (stereo *or* mono-duplicated L=R) — resets
 *   the streak; nothing to fix;
 * - **quiet** ([StereoContentProbe.Report.amplitude] below [MIN_AMPLITUDE])
 *   — carries no channel information; pauses the streak without resetting.
 */
internal class MonoTapRecovery(
    private val maxRebuilds: Int = MAX_REBUILDS,
    private val consecutiveWindows: Int = CONSECUTIVE_WINDOWS,
) {
    private var deadStreak = 0
    private var rebuilds = 0

    /** A rebuild reason when the capture record should be swapped, else null. */
    fun onReport(report: StereoContentProbe.Report): String? {
        if (report.amplitude < MIN_AMPLITUDE) return null
        if (!report.oneChannelDead()) {
            deadStreak = 0
            return null
        }
        deadStreak++
        if (deadStreak < consecutiveWindows || rebuilds >= maxRebuilds) return null
        rebuilds++
        deadStreak = 0
        // Rebuilds past the first go nuclear ([HARD_RESET_MARKER]): the tap's
        // HAL thread may be stuck in a broken config only a full teardown
        // recreates (issue #101) — see PlaybackCaptureAudioSource.
        val hard = if (rebuilds > 1) " $HARD_RESET_MARKER" else ""
        return "one channel dead for $consecutiveWindows s " +
            "(mean|L|=${report.meanAbsLeft}, mean|R|=${report.meanAbsRight}) — rebuild ${rebuildsOf(maxRebuilds)}$hard"
    }

    private fun rebuildsOf(max: Int): String = "$rebuilds/$max"

    companion object {
        /** Below this mean amplitude a window says nothing about channels. */
        const val MIN_AMPLITUDE = 500L

        /** The quiet channel under 1/50 (2%) of the loud one is dead, not panned. */
        const val DEAD_CHANNEL_RATIO = 50L

        const val CONSECUTIVE_WINDOWS = 5
        const val MAX_REBUILDS = 3

        /** Marks a rebuild that must idle out the input thread (full teardown). */
        const val HARD_RESET_MARKER = "[hard-reset]"
    }
}
