package com.zerofriction.localcast.adaptive

import com.zerofriction.localcast.adaptive.AdaptiveQualityController.Direction
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.QualityChange
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.Reason
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.thermal.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic quality (ADR-025): the picture is lowered only in a thermal
 * emergency, one step and then at most one more, in the order the profile's
 * pressure strategy gives things up, and it comes back once the device is
 * out of the emergency. Driven by a one-second clock.
 */
class AdaptiveQualityControllerTest {

    private val profile = QualityLevel(1920, 60, 4_000_000, 12_000_000)

    private class Cast(top: QualityLevel, strategy: DegradationStrategy = DegradationStrategy.MAINTAIN_FRAMERATE) {
        val changes = mutableListOf<QualityChange>()
        val controller = AdaptiveQualityController(top, strategy) { changes += it }
        var nowMs = 0L

        fun run(seconds: Int, thermal: ThermalStatus = ThermalStatus.NONE) {
            repeat(seconds) {
                nowMs += 1_000
                controller.onTick(nowMs, thermal)
            }
        }

        val sizes get() = changes.map { it.to.longEdgePx to it.to.fps }
    }

    private fun steps(level: QualityLevel, strategy: DegradationStrategy) =
        thermalSteps(level, strategy).map { it.longEdgePx to it.fps }

    // ---- The steps a pressure strategy gives ----

    @Test
    fun `keep frame rate gives up detail twice and never the frame rate`() {
        assertEquals(listOf(1920 to 60, 1280 to 60, 960 to 60), steps(profile, DegradationStrategy.MAINTAIN_FRAMERATE))
    }

    @Test
    fun `keep resolution gives up the frame rate first, then one step of detail`() {
        assertEquals(listOf(1920 to 60, 1920 to 30, 1280 to 30), steps(profile, DegradationStrategy.MAINTAIN_RESOLUTION))
    }

    @Test
    fun `balanced gives up a step of detail, then the frame rate`() {
        assertEquals(listOf(1920 to 60, 1280 to 60, 1280 to 30), steps(profile, DegradationStrategy.BALANCED))
    }

    @Test
    fun `when what the strategy would give up is used up, the other thing goes instead`() {
        assertEquals(listOf(960 to 60, 960 to 30), steps(profile.copy(longEdgePx = 960), DegradationStrategy.MAINTAIN_FRAMERATE))
        assertEquals(listOf(1920 to 30, 1280 to 30, 960 to 30), steps(profile.copy(fps = 30), DegradationStrategy.MAINTAIN_RESOLUTION))
        assertEquals(1, steps(profile.copy(longEdgePx = 960, fps = 30), DegradationStrategy.BALANCED).size)
    }

    @Test
    fun `a step never touches the bitrate range`() {
        assertTrue(thermalSteps(profile, DegradationStrategy.BALANCED).all { it.bitrateMinBps == 4_000_000 && it.bitrateMaxBps == 12_000_000 })
    }

    // ---- When it acts ----

    @Test
    fun `game heat below the emergency never lowers the picture`() {
        val cast = Cast(profile)
        // A whole session at SEVERE, the way this tablet runs a game.
        cast.run(3_600, ThermalStatus.SEVERE)
        assertTrue(cast.changes.isEmpty())
    }

    @Test
    fun `a thermal emergency costs one step at once`() {
        val cast = Cast(profile)
        cast.run(120, ThermalStatus.SEVERE)
        cast.run(1, ThermalStatus.CRITICAL)
        assertEquals(QualityChange(profile, profile.copy(longEdgePx = 1280), Direction.DOWN, Reason.THERMAL), cast.changes.single())
    }

    @Test
    fun `an emergency that lasts a minute costs a second step, and no more however long it lasts`() {
        val cast = Cast(profile)
        cast.run(59, ThermalStatus.CRITICAL)
        assertEquals(listOf(1280 to 60), cast.sizes)
        cast.run(2, ThermalStatus.CRITICAL)
        assertEquals(listOf(1280 to 60, 960 to 60), cast.sizes)
        cast.run(1_800, ThermalStatus.EMERGENCY)
        assertEquals(2, cast.changes.size)
    }

    @Test
    fun `the order is the profile's pressure strategy`() {
        val cast = Cast(profile, DegradationStrategy.MAINTAIN_RESOLUTION)
        cast.run(70, ThermalStatus.CRITICAL)
        assertEquals(listOf(1920 to 30, 1280 to 30), cast.sizes)
    }

    // ---- Coming back ----

    @Test
    fun `thirty seconds out of the emergency bring one step back, and thirty more the next`() {
        val cast = Cast(profile)
        cast.run(70, ThermalStatus.CRITICAL)
        cast.run(29, ThermalStatus.SEVERE)
        assertEquals("not yet", 2, cast.changes.size)
        cast.run(2, ThermalStatus.SEVERE)
        assertEquals(QualityChange(profile.copy(longEdgePx = 960), profile.copy(longEdgePx = 1280), Direction.UP, Reason.RECOVERED), cast.changes.last())
        cast.run(31, ThermalStatus.SEVERE)
        assertEquals(profile, cast.changes.last().to)
        cast.run(600, ThermalStatus.SEVERE)
        assertEquals("back at the profile's values: nothing more to do", 4, cast.changes.size)
    }

    @Test
    fun `nothing comes back while the emergency lasts`() {
        val cast = Cast(profile)
        cast.run(600, ThermalStatus.CRITICAL)
        assertTrue(cast.changes.none { it.direction == Direction.UP })
    }

    @Test
    fun `an emergency that flickers does not take the picture down a step each time`() {
        val cast = Cast(profile)
        // Ten seconds critical, ten seconds not, for five minutes.
        repeat(15) {
            cast.run(10, ThermalStatus.CRITICAL)
            cast.run(10, ThermalStatus.SEVERE)
        }
        // One step at the first emergency; ten cool seconds are never enough to
        // come back, so the next emergencies have nothing new to take... but one more.
        assertTrue("never below two steps", cast.changes.count { it.direction == Direction.DOWN } <= 2)
        assertEquals(0, cast.changes.count { it.direction == Direction.UP })
    }

    // ---- The user ----

    @Test
    fun `restore now goes straight back to the profile's values`() {
        val cast = Cast(profile)
        cast.run(70, ThermalStatus.CRITICAL)
        cast.controller.restore(cast.nowMs)
        assertEquals(QualityChange(profile.copy(longEdgePx = 960), profile, Direction.UP, Reason.USER), cast.changes.last())
    }

    @Test
    fun `a restore during an emergency holds for a minute before the rule steps again`() {
        val cast = Cast(profile)
        cast.run(10, ThermalStatus.CRITICAL)
        cast.controller.restore(cast.nowMs)
        cast.run(59, ThermalStatus.CRITICAL)
        assertEquals("the user's restore stands", profile, cast.controller.current)
        cast.run(2, ThermalStatus.CRITICAL)
        assertEquals(1280, cast.controller.current.longEdgePx)
    }

    @Test
    fun `restore at the profile's values does nothing`() {
        val cast = Cast(profile)
        cast.controller.restore(0)
        assertTrue(cast.changes.isEmpty())
    }
}
