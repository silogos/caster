package com.zerofriction.localcast.adaptive

import com.zerofriction.localcast.thermal.ThermalStatus

/**
 * The auto-quality policy (Phase12, thermal.md "How thermal data is used"):
 * a pure, JVM-testable state machine — no Android types, no WebRTC types.
 * The cast service feeds it ~1 Hz sender stats ([StreamSample]) plus the
 * thermal ladder, and applies the [QualityChange]s it announces (capture
 * format + sender bitrate window — `MediaCastSession.changeQuality`).
 *
 * Policy, exactly as thermal.md specifies it — conservative, slow, announced,
 * reversible:
 *  - **Step down** one ladder rung only after a *sustained* bad stretch:
 *    thermal `MODERATE`+ ([THERMAL_HOLD_MS] — "minutes, not seconds") or a
 *    struggling stream ([NET_HOLD_MS]; inputs are dropped frames — the
 *    encoder-stress signal —, RTT and packet loss). Any step also respects
 *    [DWELL_MS] since the previous one, so bad conditions cause a slow
 *    staircase, never a collapse.
 *  - **Step up** only after a long sustained healthy stretch
 *    ([HEALTHY_HOLD_MS] — deliberately much longer than the way down, the
 *    asymmetry that makes oscillation impossible), and never above the
 *    user's own settings (the ceiling is always their explicit choice,
 *    thermal.md principle 2). Thermal step-downs do **not** recover
 *    automatically — the device got hot under these exact settings, so only
 *    the user decides to go back ([restore]); stream-health step-downs do.
 *  - Notably *not* an input: measured fps below the target. Screen content
 *    frames follow the game's own pacing (thermal.md heat table) — a 30 fps
 *    game under a 60 fps profile is a quiet encoder, not a struggling one;
 *    acting on it would punish the wrong thing. Dropped frames is the
 *    encoder-stress signal instead.
 */
class AdaptiveQualityController(
    /** The user's own settings — auto quality never goes above this rung. */
    private val ceiling: QualityLevel,
    /** The preset ladder auto quality walks down (config/QualityProfile.kt). */
    private val ladder: List<QualityLevel> = QualityLevel.PRESETS,
    /** Every announced change — the service applies it and tells the user. */
    private val onLevelChanged: (QualityChange) -> Unit = {},
) {

    enum class Reason { THERMAL, STREAM_HEALTH, USER }

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
        /** Remote receiver's reported loss fraction (0..1); null while unknown. */
        val fractionLost: Double?,
        /** Nominated candidate pair RTT, ms; null while unknown. */
        val rttMs: Long?,
    )

    var current: QualityLevel = ceiling
        private set

    /** The last change, for the UI's "what happened" line — null until one fires. */
    var lastChange: QualityChange? = null
        private set

    /**
     * Why the last step *down* happened — sticky: a thermal trigger marks the
     * whole descent thermal, and thermal descents only recover manually.
     */
    private var lastDownReason: Reason? = null

    /** Backdated so the *first* step only waits for its hold, not for a dwell that never started. */
    private var lastChangeAtMs = -DWELL_MS
    private var thermalBadSinceMs: Long? = null
    private var netBadSinceMs: Long? = null
    private var healthySinceMs: Long? = null
    private var lastFramesEncoded = 0L
    private var lastFramesDropped = 0L

    /**
     * One stats tick at [nowMs]. [thermalStatus] is the latest ladder reading;
     * the holds are evaluated per tick, so the cadence of the caller is the
     * policy's clock.
     */
    @Synchronized
    fun onTick(nowMs: Long, sample: StreamSample, thermalStatus: ThermalStatus) {
        // Cumulative counters in, per-tick deltas out (a counter reset — pc
        // rebuild — must not fake a drop burst: negative deltas read as zero).
        val deltaEncoded = (sample.framesEncoded - lastFramesEncoded).coerceAtLeast(0)
        val deltaDropped = (sample.framesDropped - lastFramesDropped).coerceAtLeast(0)
        lastFramesEncoded = sample.framesEncoded
        lastFramesDropped = sample.framesDropped

        val droppedFraction = if (deltaEncoded + deltaDropped > 0) {
            deltaDropped.toDouble() / (deltaEncoded + deltaDropped)
        } else {
            0.0
        }
        val netBad = droppedFraction >= DROPPED_FRACTION_BAD ||
            (sample.fractionLost != null && sample.fractionLost >= PACKET_LOSS_FRACTION_BAD) ||
            (sample.rttMs != null && sample.rttMs >= RTT_BAD_MS)
        val thermalBad = thermalStatus >= THERMAL_TRIGGER_STATUS

        thermalBadSinceMs = markSince(thermalBadSinceMs, thermalBad, nowMs)
        netBadSinceMs = markSince(netBadSinceMs, netBad, nowMs)
        healthySinceMs = markSince(healthySinceMs, !thermalBad && !netBad, nowMs)

        val dwellElapsed = nowMs - lastChangeAtMs >= DWELL_MS
        val thermalBadForMs = thermalBadSinceMs?.let { nowMs - it }
        val netBadForMs = netBadSinceMs?.let { nowMs - it }
        val healthyForMs = healthySinceMs?.let { nowMs - it }
        when {
            thermalBadForMs != null && thermalBadForMs >= THERMAL_HOLD_MS && dwellElapsed ->
                stepDown(nowMs, Reason.THERMAL)

            netBadForMs != null && netBadForMs >= NET_HOLD_MS && dwellElapsed ->
                stepDown(nowMs, Reason.STREAM_HEALTH)

            healthyForMs != null && healthyForMs >= HEALTHY_HOLD_MS && dwellElapsed &&
                lastDownReason == Reason.STREAM_HEALTH ->
                stepUp(nowMs, Reason.STREAM_HEALTH)
        }
    }

    /**
     * The user takes the cast back to their own settings — always available
     * while below the ceiling, whatever got the cast down here.
     */
    @Synchronized
    fun restore(nowMs: Long) {
        if (current == ceiling) return
        applyChange(nowMs, ceiling, Direction.UP, Reason.USER)
        lastDownReason = null
    }

    /**
     * One rung down **the ladder order** (the preset enum order *is* the rung
     * order — cool → balanced → performance → sharp); null at the floor. The
     * ladder order, not a per-dimension comparison, is what makes descents and
     * ascents symmetric: rungs like performance (60 fps) and sharp (1080p30)
     * are not comparable dimension-wise, but they are ordered rungs.
     */
    private fun stepDownTarget(): QualityLevel? {
        val idx = ladder.indexOf(current)
        return when {
            idx > 0 -> ladder[idx - 1]
            idx == 0 -> null
            // current is the user's custom ceiling (not a rung): descend to the
            // heaviest preset that is genuinely lighter on every dimension.
            else -> ladder.filter { it.isNoHeavierThan(current) && it != current }.maxByOrNull { ladder.indexOf(it) }
        }
    }

    /**
     * The next *comparable* preset above the current one that fits within the
     * ceiling — one controlled step — or the ceiling itself when no preset
     * rung sits between (e.g. performance → sharp is a ceiling-bound step).
     */
    private fun stepUpTarget(): QualityLevel? {
        val nextPresetAbove = ladder
            .filter { current.isNoHeavierThan(it) && it != current && it.isNoHeavierThan(ceiling) }
            .minByOrNull { ladder.indexOf(it) }
        return nextPresetAbove ?: if (current != ceiling) ceiling else null
    }

    private fun stepDown(nowMs: Long, reason: Reason) {
        val target = stepDownTarget() ?: return // floor reached — nothing lower to ask for
        if (reason == Reason.THERMAL || lastDownReason == null) lastDownReason = reason
        applyChange(nowMs, target, Direction.DOWN, reason)
    }

    private fun stepUp(nowMs: Long, reason: Reason) {
        val target = stepUpTarget() ?: return
        // The descent's reason stops governing once the cast is back where
        // the user put it — further recovery rungs (a multi-rung descent)
        // stay allowed while the stream stays clean.
        if (target == ceiling) lastDownReason = null
        applyChange(nowMs, target, Direction.UP, reason)
    }

    private fun applyChange(nowMs: Long, to: QualityLevel, direction: Direction, reason: Reason) {
        val change = QualityChange(current, to, direction, reason)
        current = to
        lastChange = change
        lastChangeAtMs = nowMs
        // Hysteresis restarts at the new rung: every next step needs its own
        // full hold at the new settings — the anti-oscillation core.
        thermalBadSinceMs = null
        netBadSinceMs = null
        healthySinceMs = null
        onLevelChanged(change)
    }

    private fun markSince(since: Long?, condition: Boolean, nowMs: Long): Long? =
        if (condition) since ?: nowMs else null

    companion object {
        /** thermal.md Phase12: step down only on MODERATE and worse, never on LIGHT. */
        val THERMAL_TRIGGER_STATUS = ThermalStatus.MODERATE

        /** thermal.md: "minutes, not seconds" of sustained heat before acting. */
        const val THERMAL_HOLD_MS = 120_000L

        /** Sustained stream trouble (drops/loss/RTT) before a step down. */
        const val NET_HOLD_MS = 30_000L

        /** Recovery needs a long clean stretch — the asymmetry that beats oscillation. */
        const val HEALTHY_HOLD_MS = 180_000L

        /** Minimum spacing between any two steps, either direction. */
        const val DWELL_MS = 90_000L

        /** Per-tick dropped-frame fraction considered encoder stress (Phase5's ≈0.3% healthy baseline). */
        const val DROPPED_FRACTION_BAD = 0.05

        /** Remote receiver loss fraction considered a struggling link. */
        const val PACKET_LOSS_FRACTION_BAD = 0.05

        /** RTT (ms) considered a struggling link — Phase5 measured 5–10 ms on a healthy LAN. */
        const val RTT_BAD_MS = 100L
    }
}
