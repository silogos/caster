package com.zerofriction.localcast.webrtc

import com.zerofriction.localcast.config.CastConfig
import com.zerofriction.localcast.config.QualityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SenderBitrateFloorTest {

    private val sharpFloor = QualityProfile.SHARP.bitrateMinBps

    @Test
    fun `keep frame rate lets the bitrate fall below the window`() {
        assertEquals(KEEP_FRAME_RATE_FLOOR_BPS, effectiveBitrateFloorBps(sharpFloor, CastConfig.DEGRADATION_FRAMERATE))
    }

    @Test
    fun `keep frame rate starts the cast at the window's floor`() {
        assertEquals(sharpFloor, startBitrateBps(sharpFloor, CastConfig.DEGRADATION_FRAMERATE))
    }

    @Test
    fun `the other strategies keep the window's floor, and it is their start`() {
        for (strategy in listOf(CastConfig.DEGRADATION_RESOLUTION, CastConfig.DEGRADATION_BALANCED)) {
            assertEquals(strategy, sharpFloor, effectiveBitrateFloorBps(sharpFloor, strategy))
            assertNull(strategy, startBitrateBps(sharpFloor, strategy))
        }
    }

    @Test
    fun `a window already below the floor is left alone`() {
        val low = KEEP_FRAME_RATE_FLOOR_BPS / 2
        assertEquals(low, effectiveBitrateFloorBps(low, CastConfig.DEGRADATION_FRAMERATE))
        assertNull(startBitrateBps(low, CastConfig.DEGRADATION_FRAMERATE))
    }
}
