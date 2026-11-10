package com.zerofriction.localcast.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's own profiles (ADR-024): what adding, copying, renaming, editing
 * and deleting one does to the settings, and what the next cast runs on.
 */
class CastProfilesTest {

    private val start = CastSettings.default()
    private val sharp60 = QualityProfile.SHARP.toValues().copy(fps = 60)

    @Test
    fun `a new profile is selected and the next cast runs on its values`() {
        val settings = start.withProfileAdded("Stream 60", sharp60)
        val profile = settings.customProfiles.single()
        assertEquals("Stream 60", profile.name)
        assertEquals(ProfileSelection.Custom(profile.id), settings.selection)
        val config = settings.toConfig()
        assertEquals(1920, config.longEdgePx)
        assertEquals(60, config.fps)
        assertEquals(CastConfig.DEGRADATION_RESOLUTION, config.degradationPreference)
        assertEquals("the desktop shows the profile's name", "Stream 60", config.profile)
    }

    @Test
    fun `a built-in profile runs on its own values and keeps its label`() {
        val config = start.withProfileAdded("Stream 60", sharp60)
            .withSelection(ProfileSelection.BuiltIn(QualityProfile.PERFORMANCE))
            .toConfig()
        assertEquals("performance", config.profile)
        assertEquals(1280, config.longEdgePx)
        assertEquals(60, config.fps)
        assertEquals(CastConfig.DEGRADATION_FRAMERATE, config.degradationPreference)
        assertEquals(true, config.autoQuality)
        assertEquals(false, config.lowLatencyGameAudio)
    }

    @Test
    fun `a name is trimmed, and an empty or too long one adds nothing`() {
        assertEquals("Couch", start.withProfileAdded("  Couch ", sharp60).customProfiles.single().name)
        assertSame(start, start.withProfileAdded("   ", sharp60))
        assertSame(start, start.withProfileAdded("x".repeat(CustomProfileRules.MAX_NAME_LENGTH + 1), sharp60))
        assertEquals(1, start.withProfileAdded("x".repeat(CustomProfileRules.MAX_NAME_LENGTH), sharp60).customProfiles.size)
    }

    @Test
    fun `no more profiles than the limit`() {
        var settings = start
        repeat(CustomProfileRules.MAX_PROFILES) { settings = settings.withProfileAdded("P$it", sharp60) }
        assertEquals(CustomProfileRules.MAX_PROFILES, settings.customProfiles.size)
        assertFalse(settings.canAddProfile)
        assertSame(settings, settings.withProfileAdded("One more", sharp60))
        assertSame(settings, settings.withProfileDuplicated(settings.customProfiles.first().id))
    }

    @Test
    fun `editing a profile changes the next cast only when it is the selected one`() {
        val two = start.withProfileAdded("A", sharp60).withProfileAdded("B", sharp60)
        val (a, b) = two.customProfiles
        val edited = two.withProfileEdited(a.id) { it.copy(degradationStrategy = DegradationStrategy.MAINTAIN_FRAMERATE) }
        assertEquals(DegradationStrategy.MAINTAIN_FRAMERATE, edited.customProfile(a.id)!!.values.degradationStrategy)
        assertEquals("B is selected and untouched", sharp60, edited.active)
        val editedSelected = two.withProfileEdited(b.id) { it.copy(fps = 30) }
        assertEquals(30, editedSelected.toConfig().fps)
    }

    @Test
    fun `an edit to a value the app does not offer is refused`() {
        val settings = start.withProfileAdded("A", sharp60)
        val id = settings.customProfiles.single().id
        assertSame(settings, settings.withProfileEdited(id) { it.copy(fps = 24) })
        assertSame(settings, settings.withProfileEdited(id) { it.copy(encoderFpsLimit = 45) })
        assertSame("no such profile", settings, settings.withProfileEdited(id + 1) { it.copy(fps = 30) })
    }

    @Test
    fun `a bitrate ceiling stays inside the slider and brings its floor with it`() {
        assertEquals(6_000_000 to 12_000_000, sharp60.withBitrateCeiling(12_000_000).window())
        assertEquals(10_000_000 to 20_000_000, sharp60.withBitrateCeiling(99_000_000).window())
        assertEquals(1_000_000 to 2_000_000, sharp60.withBitrateCeiling(1).window())
        assertTrue(sharp60.withBitrateCeiling(7_000_000).isValid)
    }

    @Test
    fun `a copy gets the next free name, its own id, and is selected`() {
        val one = start.withProfileAdded("Custom 1", sharp60)
        val original = one.customProfiles.single()
        val two = one.withProfileDuplicated(original.id)
        val copy = two.customProfiles.last()
        assertEquals("Custom 2", copy.name)
        assertEquals(original.values, copy.values)
        assertTrue(copy.id != original.id)
        assertEquals(ProfileSelection.Custom(copy.id), two.selection)
        // Editing the copy leaves the original alone.
        assertEquals(sharp60, two.withProfileEdited(copy.id) { it.copy(fps = 30) }.customProfile(original.id)!!.values)
    }

    @Test
    fun `a rename keeps the profile and its values`() {
        val settings = start.withProfileAdded("A", sharp60)
        val id = settings.customProfiles.single().id
        val renamed = settings.withProfileRenamed(id, " Evening ")
        assertEquals(CustomProfile(id, "Evening", sharp60), renamed.customProfiles.single())
        assertSame(renamed, renamed.withProfileRenamed(id, ""))
    }

    @Test
    fun `deleting the selected profile falls back to Balanced`() {
        val settings = start.withProfileAdded("A", sharp60)
        val deleted = settings.withProfileDeleted(settings.customProfiles.single().id)
        assertTrue(deleted.customProfiles.isEmpty())
        assertEquals(ProfileSelection.BuiltIn(QualityProfile.BALANCED), deleted.selection)
        assertEquals("balanced", deleted.toConfig().profile)
    }

    @Test
    fun `deleting another profile keeps the selection`() {
        val two = start.withProfileAdded("A", sharp60).withProfileAdded("B", sharp60)
        val (a, b) = two.customProfiles
        val deleted = two.withProfileDeleted(a.id)
        assertEquals(listOf(b), deleted.customProfiles)
        assertEquals(ProfileSelection.Custom(b.id), deleted.selection)
    }

    @Test
    fun `a deleted profile's id is not handed to the next one while others remain`() {
        val two = start.withProfileAdded("A", sharp60).withProfileAdded("B", sharp60)
        val (a, b) = two.customProfiles
        val next = two.withProfileDeleted(a.id).withProfileAdded("C", sharp60).customProfiles.last()
        assertTrue(next.id != a.id && next.id != b.id)
    }

    @Test
    fun `selecting a profile that does not exist changes nothing`() {
        assertSame(start, start.withSelection(ProfileSelection.Custom(7)))
        assertNull(start.customProfile(7))
    }

    @Test
    fun `the suggested name skips names already taken`() {
        val taken = listOf(CustomProfile(1, "Custom 1", sharp60), CustomProfile(2, "Custom 3", sharp60))
        assertEquals("Custom 2", CustomProfileRules.nextName(taken))
        assertEquals("Custom 1", CustomProfileRules.nextName(emptyList()))
    }

    private fun ProfileValues.window() = bitrateMinBps to bitrateMaxBps
}
