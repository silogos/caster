package com.zerofriction.localcast.audio

/**
 * Decides when a playback-capture tap whose data folds to one channel should
 * be rebuilt. Fed one [StereoContentProbe.Report] per second by the capture
 * source; when the quiet channel stays dead for [CONSECUTIVE_WINDOWS] loud
 * seconds, the next report asks for a capture-record rebuild — the swap that
 * re-patches the submix input and restores the clean stereo write.
 *
 * Found live 2026-11-04 (issue #101): with the pre-opened capture record the
 * tap holds true stereo for minutes at a time, but the vendor's playback-side
 * routing into the policy sink re-folds whenever the playing app re-registers
 * an audio track (every track change in a media player). One recovery budget
 * per cast therefore cannot be tiny: the fold recurs over a whole session,
 * and exhausting the budget mid-cast left a fold unrecovered. The budget is
 * now [MAX_REBUILDS] rebuilds with a [REBUILD_INTERVAL_MS] cooldown between
 * them — enough to outlast a long session, still bounded so genuinely
 * hard-panned content cannot churn the app through rebuilds forever. The
 * bound keys on the dead side: hard-panned content keeps the SAME side dead
 * no matter how often the record is rebuilt, while the vendor re-fold
 * SHUFFLES the dead side across rebuilds (its live signature) — shuffles
 * heal uncounted, so track-heavy content recovers for a whole session.
 *
 * Window semantics:
 * - **loud + one channel dead** — counts toward the rebuild;
 * - **loud + both channels alive** (stereo *or* mono-duplicated L=R) — resets
 *   the streak; nothing to fix;
 * - **quiet** ([StereoContentProbe.Report.amplitude] below [MIN_AMPLITUDE])
 *   — carries no channel information; pauses the streak without resetting.
 *
 * A rebuild landing inside the cooldown leaves the streak standing — the next
 * dead window after the cooldown fires immediately, instead of waiting for
 * another [CONSECUTIVE_WINDOWS] windows.
 */
internal class MonoTapRecovery(
    private val maxRebuilds: Int = MAX_REBUILDS,
    private val consecutiveWindows: Int = CONSECUTIVE_WINDOWS,
    private val rebuildIntervalMs: Long = REBUILD_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var deadStreak = 0
    private var totalRebuilds = 0

    /** Rebuilds where the dead side stayed the same — the content-panning budget. */
    private var sameSideRebuilds = 0
    private var lastRebuildSide: DeadSide? = null
    private var lastRebuildAt = 0L
    private var hasRebuilt = false

    /** Which tap channel the dead window found. */
    private enum class DeadSide { LEFT, RIGHT }

    /** A rebuild reason when the capture record should be swapped, else null. */
    fun onReport(report: StereoContentProbe.Report): String? {
        if (report.amplitude < MIN_AMPLITUDE) return null
        if (!report.oneChannelDead()) {
            deadStreak = 0
            return null
        }
        deadStreak++
        val side = if (report.meanAbsLeft < report.meanAbsRight) DeadSide.LEFT else DeadSide.RIGHT
        // The dead side SHUFFLING across rebuilds is the vendor re-fold's
        // signature (the tap's channel state flips, live: L-dead → R-dead →
        // L-dead) — it heals with a rebuild and must not consume the budget.
        // The same side staying dead no matter how often the record is
        // rebuilt is hard-panned CONTENT — that is what the budget bounds.
        val shuffled = lastRebuildSide !== null && side != lastRebuildSide
        if (deadStreak < consecutiveWindows || (!shuffled && sameSideRebuilds >= maxRebuilds)) return null
        val now = clock()
        if (hasRebuilt && now - lastRebuildAt < rebuildIntervalMs) return null
        totalRebuilds++
        if (!shuffled) sameSideRebuilds++
        lastRebuildSide = side
        hasRebuilt = true
        lastRebuildAt = now
        deadStreak = 0
        // Every [HARD_RESET_EVERY]th rebuild goes nuclear ([HARD_RESET_MARKER]):
        // the tap's HAL thread may be stuck in a broken config only a full
        // teardown recreates (issue #101) — see PlaybackCaptureAudioSource.
        val hard = if (totalRebuilds % HARD_RESET_EVERY == 0) " $HARD_RESET_MARKER" else ""
        val counted = if (shuffled) " (side shuffled — uncounted)" else "/$maxRebuilds"
        return "one channel dead for $consecutiveWindows s " +
            "(mean|L|=${report.meanAbsLeft}, mean|R|=${report.meanAbsRight}) — rebuild $totalRebuilds$counted$hard"
    }

    companion object {
        /** Below this mean amplitude a window says nothing about channels. */
        const val MIN_AMPLITUDE = 500L

        /** The quiet channel under 1/50 (2%) of the loud one is dead, not panned. */
        const val DEAD_CHANNEL_RATIO = 50L

        const val CONSECUTIVE_WINDOWS = 5

        /** Rebuilds per cast — sized to outlast track-change re-folds (issue #101). */
        const val MAX_REBUILDS = 12

        /** Minimum gap between rebuilds, so content flips cannot churn the app. */
        const val REBUILD_INTERVAL_MS = 8_000L

        /** Every Nth rebuild releases all records and idles the input thread out. */
        const val HARD_RESET_EVERY = 3

        /** Marks a rebuild that must idle out the input thread (full teardown). */
        const val HARD_RESET_MARKER = "[hard-reset]"
    }
}
