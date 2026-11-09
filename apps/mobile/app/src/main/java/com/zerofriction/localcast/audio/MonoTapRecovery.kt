package com.zerofriction.localcast.audio

/**
 * Decides when a playback-capture tap whose data folds to one channel should
 * be rebuilt. Fed one [StereoContentProbe.Report] per second by the capture
 * source; when the quiet channel stays dead for [CONSECUTIVE_WINDOWS] loud
 * seconds, the next report asks for a capture-record rebuild — the swap that
 * re-patches the submix input and restores the clean stereo write.
 *
 * Found live 2026-11-04 (issue #101): with the pre-opened capture record the
 * tap holds true stereo for minutes at a time, but the playback-capture
 * write re-folds (audio.md §capture-session ordering) whenever the playing app re-registers
 * an audio track (every track change in a media player). One recovery budget
 * per cast therefore cannot be tiny: the fold recurs over a whole session,
 * and exhausting the budget mid-cast left a fold unrecovered. The budget is
 * now [MAX_REBUILDS] rebuilds with a [REBUILD_INTERVAL_MS] cooldown between
 * them — enough to outlast a long session, still bounded so genuinely
 * hard-panned content cannot churn the app through rebuilds forever. The
 * bound keys on the dead side: hard-panned content keeps the SAME side dead
 * no matter how often the record is rebuilt, while a re-fold
 * SHUFFLES the dead side across rebuilds (its live signature) — shuffles
 * heal uncounted, so track-heavy content recovers for a whole session.
 *
 * Window semantics:
 * - **loud + one channel dead** — counts toward the dead-channel rebuild;
 * - **loud + L=R** (side under 1/[DUAL_MONO_RATIO] of the amplitude) — counts
 *   toward the dual-mono rebuild (below);
 * - **loud + true stereo** — resets both streaks; nothing to fix;
 * - **quiet** ([StereoContentProbe.Report.amplitude] below [MIN_AMPLITUDE])
 *   — carries no channel information; pauses the dead-channel streak, resets
 *   the L=R streak (the L=R streak must be 4 *consecutive* loud seconds).
 *
 * **Dual-mono fold (found live 2026-11-09, desktop side meter at −118 dB):**
 * the L=R flavor of the re-fold reached the wire bit-identical for tens of
 * seconds with no rebuild, because L=R was treated as healthy. It cannot be
 * told apart from genuinely mono content in one window — the platform mixer
 * duplicates a mono source into L=R bit-exactly too — so the rebuild is
 * bounded by *outcome*: a dual-mono rebuild after which the tap still reads
 * L=R is "unhealed", and after [MAX_UNHEALED_DUAL_MONO] of those in a row the
 * content is taken as mono and left alone. Any true-stereo window proves the
 * content is stereo and re-arms the bound, so a session of re-folds heals
 * every time while a mono podcast costs at most [MAX_UNHEALED_DUAL_MONO]
 * rebuilds.
 *
 * A rebuild landing inside the cooldown leaves the streak standing — the next
 * folded window after the cooldown fires immediately, instead of waiting for
 * another full streak.
 */
internal class MonoTapRecovery(
    private val maxRebuilds: Int = MAX_REBUILDS,
    private val consecutiveWindows: Int = CONSECUTIVE_WINDOWS,
    private val rebuildIntervalMs: Long = REBUILD_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dualMonoWindows: Int = DUAL_MONO_WINDOWS,
    private val maxUnhealedDualMono: Int = MAX_UNHEALED_DUAL_MONO,
) {
    private var deadStreak = 0
    private var dualMonoStreak = 0

    /** Dual-mono rebuilds since the last true-stereo window — the mono-content bound. */
    private var unhealedDualMono = 0
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
        if (report.amplitude < MIN_AMPLITUDE) {
            // A dead channel survives a quiet gap (it is still dead after);
            // an L=R streak does not: two centred passages split by silence
            // are content, not one 4 s fold (found live 2026-11-09).
            dualMonoStreak = 0
            return null
        }
        if (!report.oneChannelDead()) {
            deadStreak = 0
            if (report.isDualMono()) return onDualMonoWindow(report)
            dualMonoStreak = 0
            unhealedDualMono = 0 // true stereo: the content is stereo, folds are folds
            return null
        }
        dualMonoStreak = 0
        deadStreak++
        val side = if (report.meanAbsLeft < report.meanAbsRight) DeadSide.LEFT else DeadSide.RIGHT
        // The dead side SHUFFLING across rebuilds is a re-fold's
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
        val counted = if (shuffled) " (side shuffled — uncounted)" else "/$maxRebuilds"
        return "one channel dead for $consecutiveWindows s " +
            "(mean|L|=${report.meanAbsLeft}, mean|R|=${report.meanAbsRight}) — rebuild $totalRebuilds$counted"
    }

    private fun onDualMonoWindow(report: StereoContentProbe.Report): String? {
        dualMonoStreak++
        if (dualMonoStreak < dualMonoWindows || unhealedDualMono >= maxUnhealedDualMono) return null
        val now = clock()
        if (hasRebuilt && now - lastRebuildAt < rebuildIntervalMs) return null
        totalRebuilds++
        unhealedDualMono++
        hasRebuilt = true
        lastRebuildAt = now
        dualMonoStreak = 0
        return "L=R for $dualMonoWindows s (mean|L|=${report.meanAbsLeft}, mean|L-R|=${report.meanAbsDiff}) " +
            "— rebuild $totalRebuilds (dual-mono $unhealedDualMono/$maxUnhealedDualMono unhealed)"
    }

    companion object {
        /** Below this mean amplitude a window says nothing about channels. */
        const val MIN_AMPLITUDE = 500L

        /** The quiet channel under 1/50 (2%) of the loud one is dead, not panned. */
        const val DEAD_CHANNEL_RATIO = 50L

        const val CONSECUTIVE_WINDOWS = 5

        /**
         * L−R under 1/1000 of the amplitude (≈ −60 dB) is L=R: real stereo
         * mixes, even narrow ones, sit far above it; the live fold read
         * bit-identical (−118 dB on the desktop meter).
         */
        const val DUAL_MONO_RATIO = 1_000L

        /**
         * Loud L=R seconds before a dual-mono rebuild. Longer than a centred
         * passage of real stereo content (a L/R/centre test clip's ~3 s centre
         * fired at 2, found live 2026-11-09); still far shorter than the
         * unhealed fold's tens of seconds.
         */
        const val DUAL_MONO_WINDOWS = 4

        /** Dual-mono rebuilds that left L=R in place before the content is taken as mono. */
        const val MAX_UNHEALED_DUAL_MONO = 2

        /** Rebuilds per cast — sized to outlast track-change re-folds (issue #101). */
        const val MAX_REBUILDS = 12

        /** Minimum gap between rebuilds, so content flips cannot churn the app. */
        const val REBUILD_INTERVAL_MS = 8_000L
    }
}
