package com.zerofriction.localcast.ui.settings

import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.config.ProfileSelection
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.StrugglePolicy
import com.zerofriction.localcast.config.toValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings in memory: every change the user makes is applied and saved
 * at once (the store is always current, the next cast reads it), and a
 * refused change saves nothing. The profile rules themselves are
 * CastProfilesTest's.
 */
class SettingsViewModelTest {

    private val persisted = mutableListOf<CastSettings>()
    private val sharp60 = QualityProfile.SHARP.toValues().copy(fps = 60)

    private fun viewModel(initial: CastSettings = CastSettings.default()): SettingsViewModel =
        SettingsViewModel(initial) { persisted.add(it) }

    @Test
    fun `picking a built-in profile makes it the next cast's and saves it`() {
        val vm = viewModel()
        vm.selectBuiltIn(QualityProfile.COOL)
        val config = vm.settings.value.toConfig()
        assertEquals("cool", config.profile)
        assertEquals(960, config.longEdgePx)
        assertEquals(2_000_000, config.bitrateMinBps)
        assertEquals(3_000_000, config.bitrateMaxBps)
        assertEquals(vm.settings.value, persisted.single())
    }

    @Test
    fun `a new profile is selected, saved, and its id comes back`() {
        val vm = viewModel()
        val id = vm.addProfile("Stream 60", sharp60)
        assertNotNull(id)
        assertEquals(ProfileSelection.Custom(id!!), vm.settings.value.selection)
        assertEquals(60, vm.settings.value.toConfig().fps)
        assertEquals(vm.settings.value, persisted.single())
    }

    @Test
    fun `a refused profile changes and saves nothing`() {
        val vm = viewModel()
        assertNull(vm.addProfile("   ", sharp60))
        assertNull(vm.duplicateProfile(42))
        vm.renameProfile(42, "Ghost")
        vm.deleteProfile(42)
        vm.selectCustom(42)
        assertEquals(CastSettings.default(), vm.settings.value)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `picking the profile that is already selected saves nothing`() {
        val vm = viewModel()
        vm.selectBuiltIn(QualityProfile.BALANCED)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `edits, renames, copies and deletes are each saved as they happen`() {
        val vm = viewModel()
        val id = vm.addProfile("A", sharp60)!!
        vm.editProfile(id) { it.copy(struggle = StrugglePolicy.FIXED) }
        vm.renameProfile(id, "Evening")
        val copy = vm.duplicateProfile(id)!!
        vm.deleteProfile(id)
        assertEquals(5, persisted.size)
        assertEquals(vm.settings.value, persisted.last())
        val left = vm.settings.value.customProfiles.single()
        assertEquals(copy, left.id)
        assertEquals("the copy took the edit with it", StrugglePolicy.FIXED, left.values.struggle)
        assertEquals("the selection followed the copy", ProfileSelection.Custom(copy), vm.settings.value.selection)
    }

    @Test
    fun `the audio switches and the microphone choice are not a profile's and survive a profile change`() {
        val vm = viewModel()
        vm.setGameAudio(false)
        vm.setMic(true)
        vm.setMicDevice(MicDeviceSource.USB)
        vm.selectBuiltIn(QualityProfile.SHARP)
        vm.addProfile("A", sharp60)
        val config = vm.settings.value.toConfig()
        assertEquals(false, config.gameAudio)
        assertEquals(true, config.mic)
        assertEquals(MicDeviceSource.USB, config.micDevice)
        assertEquals(5, persisted.size)
    }
}
