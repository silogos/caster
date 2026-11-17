package com.zerofriction.localcast.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cable's profile (ADR-034, design v4): Capture until the user picks
 * another while the cable is in; Wi-Fi keeps its own pick; it survives
 * storage, and an older document reads Capture.
 */
class CableProfileTest {

    private val sharp60 = QualityProfile.SHARP.toValues().copy(fps = 60)

    @Test
    fun `the cable uses Capture by default while Wi-Fi keeps Balanced`() {
        val settings = CastSettings.default()
        assertEquals(ProfileSelection.BuiltIn(QualityProfile.CAPTURE), settings.cableSelection)
        val cable = settings.toCableConfig()
        assertEquals(1920 to 120, cable.longEdgePx to cable.fps)
        assertEquals(30_000_000, cable.bitrateMaxBps)
        assertEquals("capture", cable.profile)
        assertEquals("balanced", settings.toConfig().profile)
    }

    @Test
    fun `Capture is a valid profile a cast may run on`() {
        assertTrue(QualityProfile.CAPTURE.toValues().isValid)
    }

    @Test
    fun `picking for the cable leaves the Wi-Fi pick alone`() {
        val mine = CastSettings.default().withProfileAdded("Low Latency", sharp60)
        val id = mine.customProfiles.single().id
        val picked = mine.withCableSelection(ProfileSelection.Custom(id))
        assertEquals(ProfileSelection.Custom(id), picked.cableSelection)
        assertEquals(mine.selection, picked.selection)
        assertEquals(60, picked.toCableConfig().fps)
    }

    @Test
    fun `deleting the cable's profile hands the cable back to Capture`() {
        val mine = CastSettings.default().withProfileAdded("Low Latency", sharp60)
        val id = mine.customProfiles.single().id
        val gone = mine.withCableSelection(ProfileSelection.Custom(id)).withProfileDeleted(id)
        assertEquals(ProfileSelection.BuiltIn(QualityProfile.CAPTURE), gone.cableSelection)
    }

    @Test
    fun `the cable pick survives storage, and a document from before it reads Capture`() {
        val mine = CastSettings.default().withProfileAdded("Low Latency", sharp60)
        val id = mine.customProfiles.single().id
        val picked = mine.withCableSelection(ProfileSelection.Custom(id))
        assertEquals(picked, CastSettingsCodec.decode(CastSettingsCodec.encode(picked)))

        val before = CastSettingsCodec.encode(mine).replace(Regex(""","cable":"[^"]*""""), "")
        assertEquals(ProfileSelection.BuiltIn(QualityProfile.CAPTURE), CastSettingsCodec.decode(before)!!.cableSelection)
    }
}
