package com.zerofriction.localcast.ui.settings

import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.QualityProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase10 settings behaviors: preset picks apply whole values (and restore
 * Auto bitrate), tweaks go custom, manual bitrates clamp and derive their
 * floor, and every change persists immediately (the settings screen's
 * contract: the store is always current, the next cast reads it).
 */
class SettingsViewModelTest {

    private val persisted = mutableListOf<CastSettings>()

    private fun viewModel(initial: CastSettings = CastSettings.default()): SettingsViewModel =
        SettingsViewModel(initial) { persisted.add(it) }

    @Test
    fun `picking a preset applies all of its values and restores auto bitrate`() {
        val vm = viewModel(CastSettings.default().copy(bitrateAuto = false))
        vm.selectProfile(QualityProfile.SHARP)
        val settings = vm.settings.value
        assertEquals(QualityProfile.SHARP, settings.profile)
        assertEquals(QualityProfile.SHARP.longEdgePx, settings.longEdgePx)
        assertEquals(QualityProfile.SHARP.fps, settings.fps)
        assertEquals(true, settings.bitrateAuto)
        assertEquals(QualityProfile.SHARP.bitrateMinBps, settings.bitrateMinBps)
        assertEquals(QualityProfile.SHARP.bitrateMaxBps, settings.bitrateMaxBps)
        assertEquals("sharp", settings.toConfig().profile)
    }

    @Test
    fun `tweaking a value past the preset makes the config custom`() {
        val vm = viewModel()
        vm.selectFps(60)
        assertEquals("custom", vm.settings.value.toConfig().profile)
        // The picked preset survives for the Auto bitrate restore.
        assertEquals(QualityProfile.BALANCED, vm.settings.value.profile)
    }

    @Test
    fun `manual bitrate clamps to the slider bounds and derives its floor`() {
        val vm = viewModel()
        vm.setManualBitrateMax(25_000_000)
        assertEquals(20_000_000, vm.settings.value.bitrateMaxBps)
        assertEquals(10_000_000, vm.settings.value.bitrateMinBps)
        assertEquals(false, vm.settings.value.bitrateAuto)

        vm.setManualBitrateMax(1)
        assertEquals(2_000_000, vm.settings.value.bitrateMaxBps)
        assertEquals(1_000_000, vm.settings.value.bitrateMinBps)
    }

    @Test
    fun `switching back to auto restores the chosen preset's window`() {
        val vm = viewModel()
        vm.setManualBitrateMax(9_000_000)
        vm.selectProfile(QualityProfile.COOL)
        vm.setManualBitrateMax(9_000_000)
        vm.setBitrateAuto(true)
        val settings = vm.settings.value
        assertEquals(true, settings.bitrateAuto)
        assertEquals(QualityProfile.COOL.bitrateMinBps, settings.bitrateMinBps)
        assertEquals(QualityProfile.COOL.bitrateMaxBps, settings.bitrateMaxBps)
    }

    @Test
    fun `going manual keeps the current ceiling and derives the floor from it`() {
        val vm = viewModel() // balanced: 4–6 Mbps
        vm.setBitrateAuto(false)
        assertEquals(6_000_000, vm.settings.value.bitrateMaxBps)
        assertEquals(3_000_000, vm.settings.value.bitrateMinBps)
    }

    @Test
    fun `picking the cool thermal profile applies the low-heat send targets`() {
        val vm = viewModel()
        vm.selectProfile(QualityProfile.COOL)
        val settings = vm.settings.value
        assertEquals(QualityProfile.COOL.longEdgePx, settings.longEdgePx)
        assertEquals(QualityProfile.COOL.fps, settings.fps)
        assertEquals("cool", settings.toConfig().profile)
    }

    @Test
    fun `audio toggles change the next cast's defaults`() {
        val vm = viewModel()
        vm.setGameAudio(false)
        vm.setMic(true)
        assertEquals(false, vm.settings.value.gameAudio)
        assertEquals(true, vm.settings.value.mic)
    }

    @Test
    fun `the auto-quality switch toggles and persists like any setting`() {
        val vm = viewModel()
        assertEquals(true, vm.settings.value.autoQuality)
        vm.setAutoQuality(false)
        assertEquals(false, vm.settings.value.autoQuality)
        assertEquals(false, persisted.last().autoQuality)
        // And a preset pick must not resurrect it (whole-value semantics apply to profile values).
        vm.selectProfile(QualityProfile.PERFORMANCE)
        assertEquals(false, vm.settings.value.autoQuality)
    }

    @Test
    fun `every change persists immediately`() {
        val vm = viewModel()
        vm.selectLongEdge(1920)
        vm.selectFps(60)
        vm.setGameAudio(false)
        assertEquals(3, persisted.size)
        assertEquals(vm.settings.value, persisted.last())
    }
}
