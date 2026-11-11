package com.zerofriction.localcast.adaptive

import com.zerofriction.localcast.config.CastSettingChoices
import com.zerofriction.localcast.config.StrugglePolicy
import com.zerofriction.localcast.thermal.ThermalStatus

/**
 * What the app does when the cast struggles (ADR-025): a pure, JVM-testable
 * state machine — no Android types, no WebRTC types. The cast service feeds
 * it ~1 Hz sender stats ([StreamSample]) plus the thermal ladder, and applies
 * the [QualityChange]s it announces (`MediaCastSession.changeQuality`).
 *
 * The split: **libwebrtc moves the bitrate**, by itself and within seconds,
 * inside the profile's range. **This moves the resolution and the frame
 * rate**, and libwebrtc no longer changes either. Left to itself libwebrtc
 * took a game cast from 1920 px to 640×400 in three minutes with an empty
 * send queue, and stayed there (2026-11-11).
 *
 * The rule, agreed with the product owner. Every number is a starting guess
 * to be measured in a real game cast:
 *  - **Steps:** one at a time, at most [MAX_STEPS_DOWN] below the profile,
 *    in the order the profile chose ([StrugglePolicy], [struggleSteps]).
 *  - **Down** one step when one symptom holds for [STRUGGLE_HOLD_MS]: the
 *    encoder cannot keep up, frames are dropped, video packets wait to be
 *    sent, or the bitrate sits at the profile's least (libwebrtc has nothing
 *    left to give through the bitrate).
 *  - **Up** one step after [HEALTHY_HOLD_MS] without any of them and with
 *    the bitrate clear of the least.
 *  - **[DWELL_MS]** between two steps, either way.
 *  - **Temperature alone does nothing** until [THERMAL_EMERGENCY_STATUS]:
 *    then one step down, and no step up while it lasts. The old rule stepped
 *    on a temperature reading while the cast was fine, went to the lowest
 *    quality and never came back.
 *  - Not a symptom: a frame rate below the target. A 30 fps game under a
 *    60 fps profile is a quiet encoder, not a struggling one.
 */
class AdaptiveQualityController(
    /** The profile's own values — the cast never goes above them. */
    private val ceiling: QualityLevel,
    private val policy: StrugglePolicy,
    /** Every announced change — the service applies it and tells the user. */
    private val onLevelChanged: (QualityChange) -> Unit = {},
) {

    /** Why a step happened, in the words the user is told. */
    enum class Reason {
        /** The encoder spends all the time it has. */
        ENCODER,

        /** Frames are dropped before they are sent. */
        DROPPED_FRAMES,

        /** Video packets wait to be sent. */
        SEND_QUEUE,

        /** The bitrate sits at the profile's least. */
        BITRATE_FLOOR,

        /** The device reports a thermal emergency. */
        THERMAL,

        /** The cast stayed clean: a step back up. */
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

    /** One ~1 Hz sender-stats tick (cumulative counters, as libwebrtc reports them). */
    data class StreamSample(
        val framesEncoded: Long,
        val framesDropped: Long,
        /** Cumulative seconds the encoder spent encoding (`totalEncodeTime`); null while not reported. */
        val totalEncodeTimeSeconds: Double? = null,
        /** Cumulative seconds packets waited in the pacer (`totalPacketSendDelay`) and the packets it counts. */
        val totalPacketSendDelaySeconds: Double? = null,
        val packetsSent: Long? = null,
        /** libwebrtc's current target bitrate; null while not reported. */
        val targetBitrateBps: Long? = null,
    )

    /** The profile's values, then each step below them. */
    private val steps: List<QualityLevel> = struggleSteps(ceiling, policy)
    private var stepIndex = 0

    val current: QualityLevel get() = steps[stepIndex]

    /** The last change, for the UI's "what happened" line — null until one fires. */
    var lastChange: QualityChange? = null
        private set

    /** Backdated so the *first* step only waits for its hold, not for a dwell that never started. */
    private var lastChangeAtMs = -DWELL_MS
    private var struggleSinceMs: Long? = null
    private var struggleReason: Reason? = null
    private var healthySinceMs: Long? = null
    private var steppedForEmergency = false
    private var lastFramesEncoded = 0L
    private var lastFramesDropped = 0L
    private var lastEncodeTimeSeconds: Double? = null
    private var lastSendDelaySeconds: Double? = null
    private var lastPacketsSent: Long? = null
    private var lastTickMs: Long? = null

    /** One stats tick at [nowMs]; the caller's cadence is the rule's clock. */
    @Synchronized
    fun onTick(nowMs: Long, sample: StreamSample, thermalStatus: ThermalStatus) {
        val symptom = symptomOf(nowMs, sample)
        lastTickMs = nowMs
        val emergency = thermalStatus >= THERMAL_EMERGENCY_STATUS

        // A hold counts one unbroken stretch; which symptom started it is the reason given.
        if (symptom != null) {
            if (struggleSinceMs == null) {
                struggleSinceMs = nowMs
                struggleReason = symptom
            }
        } else {
            struggleSinceMs = null
            struggleReason = null
        }
        val clean = symptom == null && !emergency && hasHeadroom(sample.targetBitrateBps)
        healthySinceMs = if (clean) healthySinceMs ?: nowMs else null

        // One step per emergency, not one every dwell while it lasts.
        if (!emergency) steppedForEmergency = false
        if (nowMs - lastChangeAtMs < DWELL_MS) return
        val struggledForMs = struggleSinceMs?.let { nowMs - it }
        val healthyForMs = healthySinceMs?.let { nowMs - it }
        when {
            emergency && !steppedForEmergency -> {
                steppedForEmergency = true
                step(nowMs, Direction.DOWN, Reason.THERMAL)
            }

            struggledForMs != null && struggledForMs >= STRUGGLE_HOLD_MS ->
                step(nowMs, Direction.DOWN, struggleReason ?: Reason.ENCODER)

            healthyForMs != null && healthyForMs >= HEALTHY_HOLD_MS -> step(nowMs, Direction.UP, Reason.RECOVERED)
        }
    }

    /** The user takes the cast back to the profile's values and the rule starts over. */
    @Synchronized
    fun restore(nowMs: Long) {
        if (stepIndex == 0) return
        apply(nowMs, 0, Direction.UP, Reason.USER)
    }

    /** The symptom this tick shows, or null. Counters are cumulative; a reset (pc rebuild) reads as nothing. */
    private fun symptomOf(nowMs: Long, sample: StreamSample): Reason? {
        val deltaEncoded = (sample.framesEncoded - lastFramesEncoded).coerceAtLeast(0)
        val deltaDropped = (sample.framesDropped - lastFramesDropped).coerceAtLeast(0)
        lastFramesEncoded = sample.framesEncoded
        lastFramesDropped = sample.framesDropped
        val droppedFraction = if (deltaEncoded + deltaDropped > 0) {
            deltaDropped.toDouble() / (deltaEncoded + deltaDropped)
        } else {
            0.0
        }

        val wallSeconds = lastTickMs?.let { (nowMs - it) / 1000.0 }?.takeIf { it > 0 }
        val encodeSeconds = delta(sample.totalEncodeTimeSeconds, lastEncodeTimeSeconds)
        lastEncodeTimeSeconds = sample.totalEncodeTimeSeconds
        val utilization = if (wallSeconds != null && encodeSeconds != null) encodeSeconds / wallSeconds else null

        val waitedSeconds = delta(sample.totalPacketSendDelaySeconds, lastSendDelaySeconds)
        val packets = sample.packetsSent?.let { now -> lastPacketsSent?.let { now - it } }?.takeIf { it > 0 }
        lastSendDelaySeconds = sample.totalPacketSendDelaySeconds
        lastPacketsSent = sample.packetsSent
        val sendWaitMs = if (waitedSeconds != null && packets != null) waitedSeconds / packets * 1000.0 else null

        return when {
            utilization != null && utilization >= ENCODER_UTILIZATION_BAD -> Reason.ENCODER
            droppedFraction >= DROPPED_FRACTION_BAD -> Reason.DROPPED_FRAMES
            sendWaitMs != null && sendWaitMs >= SEND_WAIT_BAD_MS -> Reason.SEND_QUEUE
            atTheFloor(sample.targetBitrateBps) -> Reason.BITRATE_FLOOR
            else -> null
        }
    }

    /** A cumulative counter's step since the last tick; null while unknown or after a reset. */
    private fun delta(now: Double?, before: Double?): Double? =
        if (now != null && before != null && now >= before) now - before else null

    private fun atTheFloor(targetBps: Long?): Boolean =
        targetBps != null && targetBps <= ceiling.bitrateMinBps * FLOOR_MARGIN

    /** Room to go up: the target is clear of the least by a part of the range. Unknown counts as room. */
    private fun hasHeadroom(targetBps: Long?): Boolean {
        if (targetBps == null) return true
        val range = ceiling.bitrateMaxBps - ceiling.bitrateMinBps
        return targetBps >= ceiling.bitrateMinBps + range * HEADROOM_FRACTION
    }

    private fun step(nowMs: Long, direction: Direction, reason: Reason) {
        val target = if (direction == Direction.DOWN) stepIndex + 1 else stepIndex - 1
        if (target !in steps.indices) return // the last step down, or already at the profile's values
        apply(nowMs, target, direction, reason)
    }

    private fun apply(nowMs: Long, index: Int, direction: Direction, reason: Reason) {
        val change = QualityChange(steps[stepIndex], steps[index], direction, reason)
        stepIndex = index
        lastChange = change
        lastChangeAtMs = nowMs
        // Every next step needs its own full hold at the new values.
        struggleSinceMs = null
        struggleReason = null
        healthySinceMs = null
        onLevelChanged(change)
    }

    companion object {
        /** A symptom has to hold this long before a step down. */
        const val STRUGGLE_HOLD_MS = 10_000L

        /** A clean cast has to hold this long before a step up. */
        const val HEALTHY_HOLD_MS = 30_000L

        /** The least time between two steps, either way. */
        const val DWELL_MS = 20_000L

        /** How far below the profile the cast may go. */
        const val MAX_STEPS_DOWN = 2

        /** Temperature alone acts only here and above. */
        val THERMAL_EMERGENCY_STATUS = ThermalStatus.CRITICAL

        /**
         * Fraction of wall time the encoder may spend before it counts as
         * unable to keep up. The recorded evidence it separates: a healthy
         * rig ran 0.42–0.68, an overloaded one 1.2–1.9.
         */
        const val ENCODER_UTILIZATION_BAD = 0.95

        /** Per-tick dropped-frame fraction that counts as struggling (healthy: ≈0.3%). */
        const val DROPPED_FRACTION_BAD = 0.10

        /** A pacer wait per packet that counts as a backed-up sender (healthy: under 10 ms). */
        const val SEND_WAIT_BAD_MS = 60.0

        /** "At the least": the target within this factor of the profile's least. */
        const val FLOOR_MARGIN = 1.05

        /** "Clear of the least": the target this part of the range above it. */
        const val HEADROOM_FRACTION = 0.25
    }
}

/**
 * The profile's values followed by each step below them, in the order
 * [policy] gives things up; at most [AdaptiveQualityController.MAX_STEPS_DOWN]
 * steps, fewer when there is nothing lower to go to. The bitrate range is
 * never part of a step: it is libwebrtc's to move in.
 */
fun struggleSteps(top: QualityLevel, policy: StrugglePolicy): List<QualityLevel> {
    val order = when (policy) {
        StrugglePolicy.SMOOTH -> listOf(Give.DETAIL, Give.DETAIL)
        StrugglePolicy.SHARP -> listOf(Give.FRAMES, Give.DETAIL)
        StrugglePolicy.BALANCED -> listOf(Give.DETAIL, Give.FRAMES)
        StrugglePolicy.FIXED -> emptyList()
    }
    val steps = mutableListOf(top)
    for (give in order) {
        val from = steps.last()
        // What the profile would give up is used up: the other thing goes instead.
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
