package com.zerofriction.localcast.service

import com.zerofriction.localcast.adaptive.AdaptiveQualityController
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.thermal.ThermalStatus
import com.zerofriction.localcast.webrtc.SenderSample
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The running cast's quality coordination (mobile review 2026-11-09, group E split of CastService). */
class LiveQualityCoordinatorTest {

    private val ladder = QualityLevel.PRESETS
    private val cool = ladder[0]
    private val balanced = ladder[1]
    private val performance = ladder[2]

    private class Rig {
        val liveQuality = MutableStateFlow<QualityLevel?>(null)
        val adaptiveState = MutableStateFlow(AdaptiveUiState())
        val encoder = MutableStateFlow<String?>(null)
        var thermal = ThermalStatus.NONE
        var nowMs = 0L
        val applied = mutableListOf<QualityLevel>()
        val announced = mutableListOf<AdaptiveQualityController.QualityChange>()
        val coordinator = LiveQualityCoordinator(
            liveQuality = liveQuality,
            adaptiveState = adaptiveState,
            encoderImplementation = encoder,
            thermalStatus = { thermal },
            applyLevel = { applied.add(it) },
            onAdapted = { announced.add(it) },
            nowMs = { nowMs },
        )

        /** One healthy ~1 Hz stats sample per second for [seconds]. */
        fun runFor(seconds: Int) {
            repeat(seconds) {
                nowMs += 1_000
                coordinator.onSample(sample(frames = nowMs / 33))
            }
        }

        fun sample(frames: Long) = SenderSample(
            bytesSent = 0, framesEncoded = frames, framesDropped = 0, framesPerSecond = 30.0,
            frameWidth = 0, frameHeight = 0, rttMs = 5, encoderImplementation = "c2.hw.avc", fractionLost = 0.0,
        )
    }

    @Test
    fun `auto quality off - the readout shows the start level and the policy never acts`() {
        val rig = Rig()
        rig.coordinator.start(performance, autoQuality = false)
        rig.thermal = ThermalStatus.SEVERE
        rig.runFor(600)

        assertEquals(performance, rig.liveQuality.value)
        assertEquals(AdaptiveUiState(), rig.adaptiveState.value)
        assertTrue(rig.applied.isEmpty())
        assertEquals("c2.hw.avc", rig.encoder.value) // the encoder readout is policy-independent
    }

    @Test
    fun `a policy step down is applied, published and announced`() {
        val rig = Rig()
        rig.coordinator.start(performance, autoQuality = true)
        rig.thermal = ThermalStatus.SEVERE
        rig.runFor(200)

        assertEquals(listOf(balanced), rig.applied)
        assertEquals(balanced, rig.liveQuality.value)
        assertEquals(balanced, rig.adaptiveState.value.current)
        assertEquals(performance, rig.adaptiveState.value.ceiling)
        assertEquals(AdaptiveQualityController.Reason.THERMAL, rig.announced.single().reason)
    }

    @Test
    fun `a user live pick resets auto quality onto the pick as its new ceiling`() {
        val rig = Rig()
        rig.coordinator.start(performance, autoQuality = true)
        rig.thermal = ThermalStatus.SEVERE
        rig.runFor(200)

        rig.coordinator.applyUserLevel(cool)

        assertEquals(listOf(balanced, cool), rig.applied)
        assertEquals(cool, rig.liveQuality.value)
        assertEquals(AdaptiveUiState(current = cool, ceiling = cool), rig.adaptiveState.value)
        // Restore climbs back to the user's pick, not the cast-start settings.
        rig.coordinator.restore()
        assertEquals(cool, rig.liveQuality.value)
    }

    @Test
    fun `restore puts the cast back at the user's settings`() {
        val rig = Rig()
        rig.coordinator.start(performance, autoQuality = true)
        rig.thermal = ThermalStatus.SEVERE
        rig.runFor(200)
        rig.thermal = ThermalStatus.NONE

        rig.coordinator.restore()

        assertEquals(performance, rig.liveQuality.value)
        assertEquals(AdaptiveQualityController.Reason.USER, rig.announced.last().reason)
    }

    @Test
    fun `stop clears every readout`() {
        val rig = Rig()
        rig.coordinator.start(performance, autoQuality = true)
        rig.runFor(3)
        rig.coordinator.stop()

        assertNull(rig.liveQuality.value)
        assertNull(rig.encoder.value)
        assertEquals(AdaptiveUiState(), rig.adaptiveState.value)
        rig.thermal = ThermalStatus.SEVERE
        rig.runFor(600) // a late stats tick after teardown changes nothing
        assertTrue(rig.applied.isEmpty())
    }
}
