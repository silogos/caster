package com.zerofriction.localcast.adaptive

import com.zerofriction.localcast.config.CastSettingChoices
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.thermal.ThermalStatus

/**
 * Automatic quality (ADR-025): a pure, JVM-testable state machine — no
 * Android types, no WebRTC types. The cast service feeds it the thermal
 * ladder once a second and applies the [QualityChange]s it announces
 * (`MediaCastSession.changeQuality`).
 *
 * It acts on one thing: **a thermal emergency** ([THERMAL_EMERGENCY_STATUS]
 * and above). Then the picture goes down one step, in the order the
 * profile's pressure strategy gives things up ([thermalSteps]); if the
 * emergency is still there after [EMERGENCY_HOLD_MS], one more, and no
 * further. Once the device is out of the emergency for [COOL_HOLD_MS] it
 * comes back, a step at a time.
 *
 * What it deliberately does not act on, and why:
 *  - **The network.** That is libwebrtc's ("Under network pressure"): it
 *    sees the link and reacts in seconds.
 *  - **Heat below the emergency.** The old rule stepped on MODERATE held for
 *    minutes; a gaming tablet sits at SEVERE for a whole session while the
 *    cast runs at 60 fps, so it went to the lowest quality and stayed.
 *  - **Encoder load and dropped frames.** Neither signal holds on the
 *    measured device (TB321FU, 14,000 s of casts, 2026-11-10/11): the
 *    dropped-frame counter never left 0, also with the encoder stalled, and
 *    the "utilization" figure read 0.95–1.57 at a healthy 60 fps and
 *    0.55–0.77 while the picture crawled at 20–45 fps.
 */
class AdaptiveQualityController(
    /** The profile's own values — the cast never goes above them. */
    private val ceiling: QualityLevel,
    /** The profile's pressure strategy: what is given up first. */
    private val strategy: DegradationStrategy,
    /** Every announced change — the service applies it and tells the user. */
    private val onLevelChanged: (QualityChange) -> Unit = {},
) {

    enum class Reason {
        /** The device reports a thermal emergency. */
        THERMAL,

        /** The emergency is over: a step back up. */
        RECOVERED,

        /** The user's own "Restore now". */
        USER,
    }

    enum class Direction { DOWN, UP }

    /** One announced transition — logged, shown to the user, applied by the service. */
    data class QualityChange(
        val from: QualityLevel,
        val to: QualityLevel,
        val direction: Direction,
        val reason: Reason,
    )

    /** The profile's values, then each step below them. */
    private val steps: List<QualityLevel> = thermalSteps(ceiling, strategy)
    private var stepIndex = 0

    val current: QualityLevel get() = steps[stepIndex]

    /** The last change, for the UI's "what happened" line — null until one fires. */
    var lastChange: QualityChange? = null
        private set

    private var emergencySinceMs: Long? = null
    private var coolSinceMs: Long? = null

    /** One tick at [nowMs] with the latest thermal reading; the caller's cadence is the rule's clock. */
    @Synchronized
    fun onTick(nowMs: Long, thermalStatus: ThermalStatus) {
        if (thermalStatus >= THERMAL_EMERGENCY_STATUS) {
            coolSinceMs = null
            val since = emergencySinceMs
            when {
                // The emergency began: one step, at once.
                since == null -> {
                    emergencySinceMs = nowMs
                    step(nowMs, Direction.DOWN, Reason.THERMAL)
                }
                // Still there a hold later: the next step, and the hold starts over.
                nowMs - since >= EMERGENCY_HOLD_MS -> {
                    emergencySinceMs = nowMs
                    step(nowMs, Direction.DOWN, Reason.THERMAL)
                }
            }
        } else {
            emergencySinceMs = null
            val since = coolSinceMs ?: nowMs.also { coolSinceMs = it }
            if (stepIndex > 0 && nowMs - since >= COOL_HOLD_MS) {
                coolSinceMs = nowMs
                step(nowMs, Direction.UP, Reason.RECOVERED)
            }
        }
    }

    /** The user takes the cast back to the profile's values. */
    @Synchronized
    fun restore(nowMs: Long) {
        if (stepIndex == 0) return
        apply(0, Direction.UP, Reason.USER)
        // A restore during an emergency holds for a full hold before the rule may step again.
        if (emergencySinceMs != null) emergencySinceMs = nowMs
        coolSinceMs = null
    }

    private fun step(nowMs: Long, direction: Direction, reason: Reason) {
        val target = if (direction == Direction.DOWN) stepIndex + 1 else stepIndex - 1
        if (target !in steps.indices) return // the last step down, or already at the profile's values
        apply(target, direction, reason)
    }

    private fun apply(index: Int, direction: Direction, reason: Reason) {
        val change = QualityChange(steps[stepIndex], steps[index], direction, reason)
        stepIndex = index
        lastChange = change
        onLevelChanged(change)
    }

    companion object {
        /** The platform's "has done all it can to reduce power" and worse. */
        val THERMAL_EMERGENCY_STATUS = ThermalStatus.CRITICAL

        /** An emergency that outlasts this costs the second step. */
        const val EMERGENCY_HOLD_MS = 60_000L

        /** Out of the emergency this long brings one step back. */
        const val COOL_HOLD_MS = 30_000L

        /** How far below the profile the cast may go. */
        const val MAX_STEPS_DOWN = 2
    }
}

/**
 * The profile's values followed by each step below them, in the order its
 * pressure [strategy] gives things up; at most
 * [AdaptiveQualityController.MAX_STEPS_DOWN] steps, fewer when there is
 * nothing lower to go to. The bitrate range is never part of a step.
 */
fun thermalSteps(top: QualityLevel, strategy: DegradationStrategy): List<QualityLevel> {
    val order = when (strategy) {
        DegradationStrategy.MAINTAIN_FRAMERATE -> listOf(Give.DETAIL, Give.DETAIL)
        DegradationStrategy.MAINTAIN_RESOLUTION -> listOf(Give.FRAMES, Give.DETAIL)
        DegradationStrategy.BALANCED -> listOf(Give.DETAIL, Give.FRAMES)
    }
    val steps = mutableListOf(top)
    for (give in order) {
        val from = steps.last()
        // What the strategy would give up is used up: the other thing goes instead.
        val next = (if (give == Give.DETAIL) lessDetail(from) ?: fewerFrames(from) else fewerFrames(from) ?: lessDetail(from))
            ?: break
        steps += next
    }
    return steps
}

private enum class Give { DETAIL, FRAMES }

private fun lessDetail(level: QualityLevel): QualityLevel? =
    CastSettingChoices.LONG_EDGE_CHOICES.filter { it < level.longEdgePx }.maxOrNull()?.let { level.copy(longEdgePx = it) }

private fun fewerFrames(level: QualityLevel): QualityLevel? =
    CastSettingChoices.FPS_CHOICES.filter { it < level.fps }.maxOrNull()?.let { level.copy(fps = it) }
