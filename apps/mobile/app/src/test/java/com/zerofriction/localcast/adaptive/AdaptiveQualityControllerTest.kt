package com.zerofriction.localcast.adaptive

import com.zerofriction.localcast.adaptive.AdaptiveQualityController.Direction
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.QualityChange
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.Reason
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.StreamSample
import com.zerofriction.localcast.config.StrugglePolicy
import com.zerofriction.localcast.thermal.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app does when the cast struggles (ADR-025): the picture goes down
 * one step at a time, in the profile's order, never more than two steps, and
 * comes back by itself. Driven by a one-second clock with the counters
 * libwebrtc reports.
 */
class AdaptiveQualityControllerTest {

    private val profile = QualityLevel(1920, 60, 4_000_000, 12_000_000)

    /** The controller on a 1 Hz clock, fed whatever the cast looks like right now. */
    private class Cast(top: QualityLevel, policy: StrugglePolicy = StrugglePolicy.SMOOTH) {
        val changes = mutableListOf<QualityChange>()
        val controller = AdaptiveQualityController(top, policy) { changes += it }
        var nowMs = 0L
        private var encoded = 0L
        private var dropped = 0L
        private var encodeSeconds = 0.0
        private var waitSeconds = 0.0
        private var packets = 0L

        /** [seconds] of a cast: frames per second, the share dropped, encoder load, pacer wait, target bitrate. */
        fun run(
            seconds: Int,
            fps: Int = 60,
            droppedShare: Double = 0.0,
            encoderLoad: Double = 0.5,
            sendWaitMs: Double = 2.0,
            targetBps: Long? = 11_000_000,
            thermal: ThermalStatus = ThermalStatus.NONE,
        ) {
            repeat(seconds) {
                nowMs += 1_000
                val lost = (fps * droppedShare).toLong()
                encoded += fps - lost
                dropped += lost
                encodeSeconds += encoderLoad
                packets += 500
                waitSeconds += 500 * sendWaitMs / 1000.0
                controller.onTick(
                    nowMs,
                    StreamSample(encoded, dropped, encodeSeconds, waitSeconds, packets, targetBps),
                    thermal,
                )
            }
        }

        val sizes get() = changes.map { it.to.longEdgePx to it.to.fps }
    }

    // ---- The steps a profile's choice gives ----

    @Test
    fun `keep it smooth gives up detail twice and never the frame rate`() {
        assertEquals(listOf(1920 to 60, 1280 to 60, 960 to 60), struggleSteps(profile, StrugglePolicy.SMOOTH).map { it.longEdgePx to it.fps })
    }

    @Test
    fun `keep it sharp gives up the frame rate first, then one step of detail`() {
        assertEquals(listOf(1920 to 60, 1920 to 30, 1280 to 30), struggleSteps(profile, StrugglePolicy.SHARP).map { it.longEdgePx to it.fps })
    }

    @Test
    fun `balanced gives up a step of detail, then the frame rate`() {
        assertEquals(listOf(1920 to 60, 1280 to 60, 1280 to 30), struggleSteps(profile, StrugglePolicy.BALANCED).map { it.longEdgePx to it.fps })
    }

    @Test
    fun `don't adjust has no step`() {
        assertEquals(listOf(profile), struggleSteps(profile, StrugglePolicy.FIXED))
    }

    @Test
    fun `when what the profile would give up is used up, the other thing goes instead`() {
        // Already the smallest picture: smooth has only the frame rate left, once.
        assertEquals(listOf(960 to 60, 960 to 30), struggleSteps(profile.copy(longEdgePx = 960), StrugglePolicy.SMOOTH).map { it.longEdgePx to it.fps })
        // Already 30 fps: sharp gives up detail twice.
        assertEquals(listOf(1920 to 30, 1280 to 30, 960 to 30), struggleSteps(profile.copy(fps = 30), StrugglePolicy.SHARP).map { it.longEdgePx to it.fps })
        // Nothing lower at all.
        assertEquals(1, struggleSteps(profile.copy(longEdgePx = 960, fps = 30), StrugglePolicy.BALANCED).size)
    }

    @Test
    fun `a step never touches the bitrate range`() {
        assertTrue(struggleSteps(profile, StrugglePolicy.BALANCED).all { it.bitrateMinBps == 4_000_000 && it.bitrateMaxBps == 12_000_000 })
    }

    // ---- Down ----

    @Test
    fun `a clean cast is left alone`() {
        val cast = Cast(profile)
        cast.run(600)
        assertTrue(cast.changes.isEmpty())
    }

    @Test
    fun `an encoder that cannot keep up for ten seconds costs one step, and says why`() {
        val cast = Cast(profile)
        cast.run(30)
        cast.run(9, encoderLoad = 1.4)
        assertTrue("nine seconds is not yet a reason", cast.changes.isEmpty())
        cast.run(3, encoderLoad = 1.4)
        assertEquals(QualityChange(profile, profile.copy(longEdgePx = 1280), Direction.DOWN, Reason.ENCODER), cast.changes.single())
    }

    @Test
    fun `a struggle that lets go before ten seconds costs nothing`() {
        val cast = Cast(profile)
        repeat(20) {
            cast.run(8, encoderLoad = 1.4)
            cast.run(2)
        }
        assertTrue(cast.changes.isEmpty())
    }

    @Test
    fun `each symptom is a reason of its own`() {
        fun reasonOf(run: Cast.() -> Unit): Reason = Cast(profile).also { it.run(5); it.run() }.changes.single().reason
        assertEquals(Reason.DROPPED_FRAMES, reasonOf { run(15, droppedShare = 0.2) })
        assertEquals(Reason.SEND_QUEUE, reasonOf { run(15, sendWaitMs = 200.0) })
        assertEquals(Reason.BITRATE_FLOOR, reasonOf { run(15, targetBps = 4_000_000) })
        assertEquals(Reason.ENCODER, reasonOf { run(15, encoderLoad = 1.0) })
    }

    @Test
    fun `a game running at 30 fps under a 60 fps profile is not a struggle`() {
        val cast = Cast(profile)
        cast.run(300, fps = 30, encoderLoad = 0.6)
        assertTrue(cast.changes.isEmpty())
    }

    @Test
    fun `a struggle that never ends stops two steps down, twenty seconds apart`() {
        val cast = Cast(profile)
        cast.run(600, encoderLoad = 1.5)
        assertEquals(listOf(1280 to 60, 960 to 60), cast.sizes)
        // The first after its ten-second hold, the second no sooner than twenty seconds later.
        assertEquals(2, cast.changes.size)
    }

    @Test
    fun `the second step waits out the pause after the first`() {
        val cast = Cast(profile)
        cast.run(5) // the first second has nothing to compare with
        cast.run(11, encoderLoad = 1.5)
        assertEquals(1, cast.changes.size)
        cast.run(19, encoderLoad = 1.5)
        assertEquals("still inside the pause", 1, cast.changes.size)
        cast.run(2, encoderLoad = 1.5)
        assertEquals(2, cast.changes.size)
    }

    @Test
    fun `don't adjust never steps, whatever the cast looks like`() {
        val cast = Cast(profile, StrugglePolicy.FIXED)
        cast.run(600, encoderLoad = 2.0, droppedShare = 0.5, sendWaitMs = 500.0, targetBps = 4_000_000, thermal = ThermalStatus.CRITICAL)
        assertTrue(cast.changes.isEmpty())
    }

    // ---- Up ----

    @Test
    fun `thirty clean seconds bring one step back, and thirty more the next`() {
        val cast = Cast(profile)
        cast.run(60, encoderLoad = 1.5)
        cast.run(29)
        assertEquals("not yet", 2, cast.changes.size)
        cast.run(2)
        assertEquals(QualityChange(profile.copy(longEdgePx = 960), profile.copy(longEdgePx = 1280), Direction.UP, Reason.RECOVERED), cast.changes.last())
        cast.run(31)
        assertEquals(profile, cast.changes.last().to)
        cast.run(300)
        assertEquals("back at the profile's values: nothing more to do", 4, cast.changes.size)
    }

    @Test
    fun `a bitrate still near the least keeps the cast where it is`() {
        val cast = Cast(profile)
        cast.run(15, encoderLoad = 1.5)
        // No symptom, but libwebrtc's target has not climbed clear of the least.
        cast.run(300, targetBps = 5_000_000)
        assertEquals(1, cast.changes.size)
        cast.run(31, targetBps = 9_000_000)
        assertEquals(Direction.UP, cast.changes.last().direction)
    }

    @Test
    fun `a struggle that keeps coming back does not make the picture flap`() {
        val cast = Cast(profile)
        // Ten minutes of fifteen bad seconds, fifteen good.
        repeat(20) {
            cast.run(15, encoderLoad = 1.5)
            cast.run(15)
        }
        val ups = cast.changes.count { it.direction == Direction.UP }
        assertEquals("fifteen clean seconds are never enough to go up", 0, ups)
        assertEquals(2, cast.changes.size)
    }

    // ---- Temperature ----

    @Test
    fun `heat alone does nothing below critical`() {
        val cast = Cast(profile)
        cast.run(900, thermal = ThermalStatus.SEVERE)
        assertTrue(cast.changes.isEmpty())
    }

    @Test
    fun `a thermal emergency costs one step, and nothing comes back while it lasts`() {
        val cast = Cast(profile)
        cast.run(5)
        cast.run(300, thermal = ThermalStatus.CRITICAL)
        assertEquals(QualityChange(profile, profile.copy(longEdgePx = 1280), Direction.DOWN, Reason.THERMAL), cast.changes.single())
        cast.run(31, thermal = ThermalStatus.SEVERE)
        assertEquals(Direction.UP, cast.changes.last().direction)
    }

    // ---- The user ----

    @Test
    fun `restore now goes straight back to the profile's values and the rule starts over`() {
        val cast = Cast(profile)
        cast.run(60, encoderLoad = 1.5)
        cast.controller.restore(cast.nowMs)
        assertEquals(QualityChange(profile.copy(longEdgePx = 960), profile, Direction.UP, Reason.USER), cast.changes.last())
        cast.run(11, encoderLoad = 1.5)
        assertEquals("the pause after the restore holds", 3, cast.changes.size)
        cast.run(10, encoderLoad = 1.5)
        assertEquals(1280, cast.changes.last().to.longEdgePx)
    }

    @Test
    fun `restore at the profile's values does nothing`() {
        val cast = Cast(profile)
        cast.controller.restore(0)
        assertTrue(cast.changes.isEmpty())
    }

    // ---- Counters ----

    @Test
    fun `counters that start over after a reconnect are not a burst of trouble`() {
        val cast = Cast(profile)
        cast.run(30)
        // A rebuilt peer connection reports its counters from zero again.
        val fresh = Cast(profile)
        repeat(30) {
            cast.nowMs += 1_000
            fresh.run(1)
            cast.controller.onTick(cast.nowMs, StreamSample(60L * (it + 1), 0, 0.5 * (it + 1), 0.001 * (it + 1), 500L * (it + 1), 11_000_000), ThermalStatus.NONE)
        }
        assertTrue(cast.changes.isEmpty())
    }

    @Test
    fun `a cast whose stats carry no encode time, queue or target still runs`() {
        val controller = AdaptiveQualityController(profile, StrugglePolicy.SMOOTH)
        repeat(120) { controller.onTick(it * 1_000L, StreamSample(60L * it, 0), ThermalStatus.NONE) }
        assertEquals(profile, controller.current)
    }
}
