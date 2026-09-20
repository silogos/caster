package com.zerofriction.localcast.adaptive

import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.thermal.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-quality policy's acceptance matrix (roadmap Phase12): controlled
 * step-downs/ups without oscillation. Every scenario runs the pure machine
 * with a synthetic ~1 Hz tick cadence — the policy's clock is the caller's —
 * and cumulative counters, exactly as libwebrtc reports them.
 */
class AdaptiveQualityControllerTest {

    companion object {
        private val ladder = QualityLevel.PRESETS
        private val cool = ladder[0]
        private val balanced = ladder[1]
        private val performance = ladder[2]
        private val sharp = ladder[3]
    }

    /**
     * Tick harness: per-tick *deltas* in, cumulative counters out — the shape
     * `getStats` actually produces. `absolute` overrides the counters (a pc
     * rebuild resets them to zero mid-stream).
     */
    private class Harness(val ceiling: QualityLevel) {
        val changes = mutableListOf<AdaptiveQualityController.QualityChange>()
        val controller = AdaptiveQualityController(
            ceiling = ceiling,
            ladder = ladder,
            onLevelChanged = { changes.add(it) },
        )
        private var encoded = 0L
        private var dropped = 0L

        fun tick(
            nowMs: Long,
            encodedDelta: Long = 30,
            droppedDelta: Long = 0,
            fractionLost: Double? = null,
            rttMs: Long? = 5L,
            thermal: ThermalStatus = ThermalStatus.NONE,
            absolute: Pair<Long, Long>? = null,
        ) {
            if (absolute != null) {
                encoded = absolute.first
                dropped = absolute.second
            } else {
                encoded += encodedDelta
                dropped += droppedDelta
            }
            controller.onTick(
                nowMs,
                AdaptiveQualityController.StreamSample(
                    framesEncoded = encoded,
                    framesDropped = dropped,
                    fractionLost = fractionLost,
                    rttMs = rttMs,
                ),
                thermal,
            )
        }
    }

    // ---- the good default: no bad stretch, no change ever ----

    @Test
    fun `healthy ticks never change the level`() {
        val h = Harness(balanced)
        for (second in 0..600) h.tick(second * 1_000L)
        assertEquals(balanced, h.controller.current)
        assertTrue(h.changes.isEmpty())
    }

    // ---- thermal: sustained MODERATE+ steps down, minutes not seconds ----

    @Test
    fun `sustained MODERATE steps down after the thermal hold`() {
        val h = Harness(balanced)
        for (second in 0..119) h.tick(second * 1_000L, thermal = ThermalStatus.MODERATE)
        assertTrue(h.changes.isEmpty())

        h.tick(120_000L, thermal = ThermalStatus.MODERATE)
        assertEquals(listOf(AdaptiveQualityController.Direction.DOWN), h.changes.map { it.direction })
        assertEquals(cool, h.controller.current)
        assertEquals(AdaptiveQualityController.Reason.THERMAL, h.changes.single().reason)
    }

    @Test
    fun `LIGHT never triggers a thermal step down`() {
        val h = Harness(performance)
        for (second in 0..600) h.tick(second * 1_000L, thermal = ThermalStatus.LIGHT)
        assertEquals(performance, h.controller.current)
        assertTrue(h.changes.isEmpty())
    }

    @Test
    fun `sustained heat walks the staircase, one rung per hold`() {
        val h = Harness(performance)
        for (second in 0..300) h.tick(second * 1_000L, thermal = ThermalStatus.SEVERE)
        // 120 s hold → balanced; hysteresis restarts → cool at 240 s; the floor stops it.
        assertEquals(listOf(balanced, cool), h.changes.map { it.to })
        assertEquals(cool, h.controller.current)
    }

    @Test
    fun `heat ending does not recover automatically`() {
        val h = Harness(balanced)
        for (second in 0..600) {
            val thermal = if (second < 130) ThermalStatus.MODERATE else ThermalStatus.NONE
            h.tick(second * 1_000L, thermal = thermal)
        }
        // Stepped down once, and even 8 minutes of clean air after never goes back up:
        // the device got hot under these settings — thermal.md, recovery is manual only.
        assertEquals(listOf(cool), h.changes.map { it.to })
        assertEquals(cool, h.controller.current)
    }

    @Test
    fun `a thermal descent is marked thermal even if drops arrive later`() {
        val h = Harness(balanced)
        for (second in 0..120) h.tick(second * 1_000L, thermal = ThermalStatus.MODERATE)
        assertEquals(cool, h.controller.current)
        // Recovery needs 3 clean minutes — which then would be a stream-health
        // recovery, but the descent was marked thermal: it must stay down.
        for (second in 121..600) h.tick(second * 1_000L)
        assertEquals(1, h.changes.size)
        assertEquals(cool, h.controller.current)
    }

    // ---- stream health: drops / loss / RTT ----

    @Test
    fun `sustained dropped frames step down after the net hold`() {
        val h = Harness(balanced)
        for (second in 0..29) h.tick(second * 1_000L, droppedDelta = 30)
        assertTrue(h.changes.isEmpty())

        h.tick(30_000L, droppedDelta = 30)
        assertEquals(cool, h.controller.current)
        assertEquals(AdaptiveQualityController.Reason.STREAM_HEALTH, h.changes.single().reason)
    }

    @Test
    fun `sustained packet loss steps down`() {
        val h = Harness(balanced)
        for (second in 0..30) h.tick(second * 1_000L, fractionLost = 0.2)
        assertEquals(cool, h.controller.current)
        assertEquals(AdaptiveQualityController.Reason.STREAM_HEALTH, h.changes.single().reason)
    }

    @Test
    fun `sustained high RTT steps down`() {
        val h = Harness(balanced)
        for (second in 0..30) h.tick(second * 1_000L, rttMs = 200L)
        assertEquals(cool, h.controller.current)
    }

    @Test
    fun `single bad ticks do not step down`() {
        val h = Harness(balanced)
        h.tick(5_000L, droppedDelta = 100)
        h.tick(6_000L)
        for (second in 7..600) h.tick(second * 1_000L)
        assertTrue(h.changes.isEmpty())
    }

    @Test
    fun `healthy stretch recovers a stream-health descent`() {
        val h = Harness(balanced)
        for (second in 0..30) h.tick(second * 1_000L, droppedDelta = 30)
        assertEquals(cool, h.controller.current)
        // Clean from the change onward; recovery fires after the healthy hold.
        for (second in 31..211) h.tick(second * 1_000L)
        assertEquals(
            listOf(AdaptiveQualityController.Direction.DOWN, AdaptiveQualityController.Direction.UP),
            h.changes.map { it.direction },
        )
        assertEquals(balanced, h.controller.current)
    }

    @Test
    fun `recovery is a controlled staircase, never a jump`() {
        val h = Harness(performance)
        // Two drops walk down the ladder order: performance → balanced → cool.
        for (second in 0..30) h.tick(second * 1_000L, droppedDelta = 30)
        for (second in 31..119) h.tick(second * 1_000L, droppedDelta = 30) // dwell blocks until 120 s
        for (second in 121..600) h.tick(second * 1_000L, droppedDelta = 30) // floor: no more steps
        assertEquals(listOf(balanced, cool), h.changes.map { it.to })

        // Recovery climbs one comparable rung at a time: cool → balanced → performance.
        for (second in 601..781) h.tick(second * 1_000L) // up at 781: cool → balanced
        for (second in 782..962) h.tick(second * 1_000L) // up at 962: balanced → performance (the ceiling)
        assertEquals(listOf(balanced, cool, balanced, performance), h.changes.map { it.to })
        assertEquals(performance, h.controller.current)
    }

    @Test
    fun `a ceiling-bound step covers rungs no preset sits between`() {
        // sharp (1080p30) and performance (720p60) are ordered rungs but not
        // comparable dimension-wise: recovery from performance goes to the
        // ceiling itself, in one announced step.
        val h = Harness(sharp)
        for (second in 0..30) h.tick(second * 1_000L, droppedDelta = 30) // down: sharp → performance
        for (second in 31..211) h.tick(second * 1_000L) // up: performance → sharp
        assertEquals(listOf(performance, sharp), h.changes.map { it.to })
        assertEquals(sharp, h.controller.current)
    }

    @Test
    fun `the ceiling is respected — never above the user's settings`() {
        val h = Harness(balanced)
        for (second in 0..30) h.tick(second * 1_000L, droppedDelta = 30)
        for (second in 31..400) h.tick(second * 1_000L)
        assertEquals(balanced, h.controller.current)
        assertTrue(h.changes.all { it.to.isNoHeavierThan(balanced) })
    }

    @Test
    fun `the ladder floor stops the descent`() {
        val h = Harness(performance)
        for (second in 0..600) h.tick(second * 1_000L, droppedDelta = 30, thermal = ThermalStatus.MODERATE)
        assertEquals(cool, h.controller.current)
    }

    // ---- oscillation guards ----

    @Test
    fun `a fresh bad stretch right after a recovery waits out the dwell`() {
        val h = Harness(balanced)
        for (second in 0..30) h.tick(second * 1_000L, droppedDelta = 30) // down at 30 s
        for (second in 31..211) h.tick(second * 1_000L) // recovery at 211 s
        assertEquals(2, h.changes.size)
        // Trouble again immediately: the dwell (90 s since the recovery) blocks the next step.
        for (second in 212..300) h.tick(second * 1_000L, droppedDelta = 30)
        assertEquals(2, h.changes.size)
        h.tick(301_000L, droppedDelta = 30)
        assertEquals(3, h.changes.size)
        assertEquals(cool, h.controller.current)
    }

    // ---- user restore ----

    @Test
    fun `restore returns to the ceiling and restarts the hysteresis`() {
        val h = Harness(balanced)
        for (second in 0..30) h.tick(second * 1_000L, droppedDelta = 30)
        assertEquals(cool, h.controller.current)
        h.controller.restore(31_000L)
        assertEquals(balanced, h.controller.current)
        assertEquals(AdaptiveQualityController.Reason.USER, h.controller.lastChange?.reason)
        // Hysteresis restarted: the heat stretch needs its full hold again.
        for (second in 32..151) h.tick(second * 1_000L, thermal = ThermalStatus.MODERATE)
        assertEquals(2, h.changes.size) // the step-down and the restore
        h.tick(152_000L, thermal = ThermalStatus.MODERATE)
        assertEquals(3, h.changes.size)
        assertEquals(cool, h.controller.current)
    }

    @Test
    fun `restore at the ceiling is a no-op`() {
        val h = Harness(balanced)
        h.controller.restore(0L)
        assertNull(h.controller.lastChange)
    }

    // ---- counter resets (pc rebuild) must not fake a drop burst ----

    @Test
    fun `a counter reset does not fake a bad stretch`() {
        val h = Harness(balanced)
        for (second in 0..30) h.tick(second * 1_000L, droppedDelta = 30)
        assertEquals(cool, h.controller.current)
        for (second in 31..60) h.tick(second * 1_000L)
        // A pc rebuild resets the cumulative counters — the deltas clamp to zero.
        h.tick(61_000L, absolute = 0L to 0L)
        for (second in 62..211) h.tick(second * 1_000L)
        assertEquals(
            listOf(AdaptiveQualityController.Direction.DOWN, AdaptiveQualityController.Direction.UP),
            h.changes.map { it.direction },
        )
    }

    // ---- ladder sanity: the rungs the policy walks are the shipped presets ----

    @Test
    fun `the preset ladder mirrors the shipped profiles`() {
        assertEquals(QualityProfile.entries.size, ladder.size)
        assertEquals(QualityProfile.entries.map { it.longEdgePx }, ladder.map { it.longEdgePx })
    }
}
