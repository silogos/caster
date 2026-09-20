package com.zerofriction.localcast.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase10: preset → config semantics — a settings document that matches a
 * preset is labeled with it; any tweak beyond a preset is labeled "custom"
 * (the display-only label the desktop's status line shows, webrtc.md).
 */
class QualityProfileTest {

    @Test
    fun `default settings map to the balanced preset label`() {
        assertEquals("balanced", CastSettings.default().toConfig().profile)
    }

    @Test
    fun `every preset maps to its own label`() {
        for (preset in QualityProfile.entries) {
            val config = CastSettings(
                profile = preset,
                longEdgePx = preset.longEdgePx,
                fps = preset.fps,
                bitrateAuto = true,
                bitrateMinBps = preset.bitrateMinBps,
                bitrateMaxBps = preset.bitrateMaxBps,
                gameAudio = true,
                mic = false,
            ).toConfig()
            assertEquals(preset.label, config.profile)
        }
    }

    @Test
    fun `a tweaked value makes the label custom`() {
        assertEquals(
            "custom",
            profileLabelFor(longEdgePx = 1280, fps = 30, bitrateMinBps = 4_000_000, bitrateMaxBps = 5_000_000),
        )
        assertEquals(
            "custom",
            profileLabelFor(longEdgePx = 1920, fps = 30, bitrateMinBps = 4_000_000, bitrateMaxBps = 6_000_000),
        )
        assertEquals(
            "custom",
            profileLabelFor(longEdgePx = 1280, fps = 60, bitrateMinBps = 4_000_000, bitrateMaxBps = 6_000_000),
        )
    }

    @Test
    fun `a manual window that happens to equal a preset window is that preset, not custom`() {
        assertEquals(
            "sharp",
            profileLabelFor(longEdgePx = 1920, fps = 30, bitrateMinBps = 8_000_000, bitrateMaxBps = 12_000_000),
        )
    }

    @Test
    fun `toConfig carries the audio defaults through`() {
        val config = CastSettings.default().copy(gameAudio = false, mic = true).toConfig()
        assertEquals(false, config.gameAudio)
        assertEquals(true, config.mic)
        assertEquals(CastConfig.DEGRADATION_BALANCED, config.degradationPreference)
    }
}
