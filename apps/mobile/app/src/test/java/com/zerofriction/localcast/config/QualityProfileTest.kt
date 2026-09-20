package com.zerofriction.localcast.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase10/11: preset → config semantics — a settings document that matches a
 * preset is labeled with it; any tweak beyond a preset is labeled "custom"
 * (the display-only label the desktop's status line shows, webrtc.md).
 * Phase11 adds the thermal acceptance: the three thermal profiles
 * (thermal.md) must map to measurably distinct send targets.
 */
class QualityProfileTest {

    @Test
    fun `default settings map to the balanced preset label`() {
        assertEquals("balanced", CastSettings.default().toConfig().profile)
    }

    @Test
    fun `the thermal presets carry their thermal vocabulary`() {
        assertEquals("cool", QualityProfile.COOL.label)
        assertEquals("balanced", QualityProfile.BALANCED.label)
        assertEquals("performance", QualityProfile.PERFORMANCE.label)
        assertEquals("sharp", QualityProfile.SHARP.label)
    }

    /**
     * The Phase11 acceptance behind "profiles switch measurably distinct
     * encoder configs": no two of the thermal profiles (cool · balanced ·
     * performance, thermal.md) may share a whole encoder target — switching
     * between any two must change what the sender actually sends.
     */
    @Test
    fun `every thermal profile maps to a distinct encoder target`() {
        val thermal = listOf(QualityProfile.COOL, QualityProfile.BALANCED, QualityProfile.PERFORMANCE)
        val targets = thermal.map { Triple(it.longEdgePx, it.fps, it.bitrateMinBps to it.bitrateMaxBps) }
        assertEquals(thermal.size, targets.toSet().size)
        // And the distinct pairs, spelled out (no hiding behind the set):
        // cool is the smallest target, performance raises fps and the window.
        assertEquals(960, QualityProfile.COOL.longEdgePx)
        assertEquals(30, QualityProfile.COOL.fps)
        assertEquals(1280, QualityProfile.BALANCED.longEdgePx)
        assertEquals(30, QualityProfile.BALANCED.fps)
        assertEquals(1280, QualityProfile.PERFORMANCE.longEdgePx)
        assertEquals(60, QualityProfile.PERFORMANCE.fps)
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
