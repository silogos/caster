package com.zerofriction.localcast.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence: the settings document round-trips, anything this build can't
 * fully validate decodes to null (the store then degrades to defaults, never
 * a cast on guessed values), and every older document migrates into profiles
 * without losing a value (ADR-024). The older documents here are written out
 * the way those versions stored them, not derived from today's encoder.
 */
class CastSettingsCodecTest {

    private val smooth = ProfileValues(
        longEdgePx = 1920,
        fps = 60,
        bitrateMinBps = 6_000_000,
        bitrateMaxBps = 12_000_000,
        degradationStrategy = DegradationStrategy.MAINTAIN_FRAMERATE,
        autoQuality = false,
        encoderImpl = EncoderImplementation.SOFTWARE,
        preferredCodec = PreferredVideoCodec.VP8,
        h264HighProfile = false,
        encoderFpsLimit = 30,
        lowLatencyGameAudio = true,
    )

    private val withProfiles = CastSettings(
        selection = ProfileSelection.Custom(2),
        customProfiles = listOf(
            CustomProfile(1, "Couch", QualityProfile.SHARP.toValues()),
            CustomProfile(2, "Stream 60", smooth),
        ),
        gameAudio = false,
        mic = true,
        micDevice = MicDeviceSource.BLUETOOTH,
    )

    // ---- Today's document ----

    @Test
    fun `a document with the user's profiles round-trips every field`() {
        assertEquals(withProfiles, CastSettingsCodec.decode(CastSettingsCodec.encode(withProfiles)))
    }

    @Test
    fun `the defaults round-trip`() {
        assertEquals(CastSettings.default(), CastSettingsCodec.decode(CastSettingsCodec.encode(CastSettings.default())))
    }

    @Test
    fun `the document is version 9 and has no bitrate mode or leftover field`() {
        val document = CastSettingsCodec.encode(withProfiles)
        assertTrue(document.contains(""""v":9"""))
        assertFalse(document.contains("struggle"))
        assertFalse(document.contains("bitrateMode"))
        assertFalse(document.contains("bitrateAuto"))
    }

    @Test
    fun `unknown keys are tolerated forward-compatibly`() {
        val document = CastSettingsCodec.encode(withProfiles).replace(""""v":""", """"zfcFutureField":9,"v":""")
        assertEquals(withProfiles, CastSettingsCodec.decode(document))
    }

    @Test
    fun `corrupt json decodes to null`() {
        assertNull(CastSettingsCodec.decode("{not json"))
        assertNull(CastSettingsCodec.decode(""))
    }

    @Test
    fun `a version this build does not know decodes to null`() {
        assertNull(CastSettingsCodec.decode(CastSettingsCodec.encode(withProfiles).replace(""""v":9""", """"v":10""")))
        assertNull(CastSettingsCodec.decode(CastSettingsCodec.encode(withProfiles).replace(""""v":9""", """"v":0""")))
    }

    @Test
    fun `a selection that names no profile decodes to null`() {
        val document = CastSettingsCodec.encode(withProfiles)
        assertNull(CastSettingsCodec.decode(document.replace("custom:2", "custom:9")))
        assertNull(CastSettingsCodec.decode(document.replace("custom:2", "builtin:turbo")))
        assertNull(CastSettingsCodec.decode(document.replace("custom:2", "second")))
    }

    @Test
    fun `profiles that break the rules decode to null`() {
        val document = CastSettingsCodec.encode(withProfiles)
        // Two profiles with one id.
        assertNull(CastSettingsCodec.decode(document.replace(""""id":1""", """"id":2""")))
        // A name a profile may not have: empty, padded, too long.
        assertNull(CastSettingsCodec.decode(document.replace(""""name":"Couch"""", """"name":""""")))
        assertNull(CastSettingsCodec.decode(document.replace(""""name":"Couch"""", """"name":" Couch"""")))
        assertNull(CastSettingsCodec.decode(document.replace(""""name":"Couch"""", """"name":"${"x".repeat(21)}"""")))
        // More profiles than the app lets a user make.
        val many = (1..CustomProfileRules.MAX_PROFILES + 1).map { CustomProfile(it, "P$it", smooth) }
        assertNull(CastSettingsCodec.decode(CastSettingsCodec.encode(withProfiles.copy(customProfiles = many))))
    }

    @Test
    fun `a profile with a value this build does not offer decodes to null`() {
        val document = CastSettingsCodec.encode(withProfiles)
        assertNull(CastSettingsCodec.decode(document.replace(""""fps":60""", """"fps":24""")))
        assertNull(CastSettingsCodec.decode(document.replace(""""encoderFpsLimit":30""", """"encoderFpsLimit":45""")))
        assertNull(CastSettingsCodec.decode(document.replace(""""encoderImpl":"software"""", """"encoderImpl":"fpga"""")))
        assertNull(CastSettingsCodec.decode(document.replace(""""preferredCodec":"vp8"""", """"preferredCodec":"av2"""")))
        assertNull(CastSettingsCodec.decode(document.replace(""""bitrateMinBps":6000000""", """"bitrateMinBps":13000000""")))
    }

    @Test
    fun `an unknown mic device or a missing field decodes to null`() {
        val document = CastSettingsCodec.encode(withProfiles)
        assertNull(CastSettingsCodec.decode(document.replace(""""micDevice":"bluetooth"""", """"micDevice":"telepathy"""")))
        assertNull(CastSettingsCodec.decode(document.replace(""""mic":true,""", "")))
    }

    // ---- Older documents: one flat set of values, migrated into profiles ----

    /** A flat document the way version [v] stored it; [changes] override or (with null) drop a field. */
    private fun flat(v: Int, vararg changes: Pair<String, String?>): String {
        val fields = linkedMapOf(
            "v" to "$v",
            "profile" to "\"balanced\"",
            "longEdgePx" to "1280",
            "fps" to "30",
            "bitrateAuto" to "true",
            "bitrateMinBps" to "4000000",
            "bitrateMaxBps" to "6000000",
            "gameAudio" to "true",
            "mic" to "false",
        )
        if (v >= 3) fields["autoQuality"] = "true"
        if (v >= 4) {
            fields["encoderImpl"] = "\"hardware\""
            fields["h264HighProfile"] = "true"
            fields["preferredCodec"] = "\"h264\""
            fields["degradationStrategy"] = "\"balanced\""
            fields["encoderFpsLimit"] = "null"
            fields["bitrateMode"] = "\"cbr\""
        }
        if (v >= 5) fields["micDevice"] = "\"builtin\""
        if (v >= 6) fields["lowLatencyGameAudio"] = "false"
        for ((key, value) in changes) if (value == null) fields.remove(key) else fields[key] = value
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    @Test
    fun `stored values that equal a built-in profile select that profile`() {
        for (v in 1..6) {
            assertEquals("v$v", CastSettings.default(), CastSettingsCodec.decode(flat(v)))
        }
    }

    @Test
    fun `the setup that prompted profiles becomes Custom 1 with every value kept`() {
        // Sharp, then 60 fps, auto quality off: shown as "Custom", still carrying Sharp's window and strategy.
        val stored = flat(
            6,
            "profile" to "\"sharp\"", "longEdgePx" to "1920", "fps" to "60",
            "bitrateMinBps" to "8000000", "bitrateMaxBps" to "12000000",
            "autoQuality" to "false", "degradationStrategy" to "\"resolution\"",
        )
        val settings = CastSettingsCodec.decode(stored)!!
        val profile = settings.customProfiles.single()
        assertEquals("Custom 1", profile.name)
        assertEquals(ProfileSelection.Custom(profile.id), settings.selection)
        assertEquals(
            QualityProfile.SHARP.toValues().copy(fps = 60, autoQuality = false),
            profile.values,
        )
        assertEquals("the next cast is unchanged", 8_000_000, settings.toConfig().bitrateMinBps)
        assertEquals(CastConfig.DEGRADATION_RESOLUTION, settings.toConfig().degradationPreference)
    }

    @Test
    fun `a preset with one changed encoder choice becomes Custom 1, not the preset`() {
        val settings = CastSettingsCodec.decode(flat(6, "encoderImpl" to "\"software\""))!!
        assertEquals(EncoderImplementation.SOFTWARE, settings.customProfiles.single().values.encoderImpl)
        assertEquals(EncoderImplementation.SOFTWARE, settings.toConfig().encoderImpl)
    }

    @Test
    fun `the dropped bitrate mode and auto-bitrate flag do not make a preset custom`() {
        val cool = flat(
            6,
            "profile" to "\"cool\"", "longEdgePx" to "960", "bitrateMinBps" to "2000000",
            "bitrateMaxBps" to "3000000", "h264HighProfile" to "false", "bitrateMode" to "\"vbr\"",
            "bitrateAuto" to "false",
        )
        val settings = CastSettingsCodec.decode(cool)!!
        assertEquals(ProfileSelection.BuiltIn(QualityProfile.COOL), settings.selection)
        assertTrue(settings.customProfiles.isEmpty())
    }

    @Test
    fun `a v1 document's old preset names are translated`() {
        val light = flat(
            1, "profile" to "\"light\"", "longEdgePx" to "960",
            "bitrateMinBps" to "2000000", "bitrateMaxBps" to "3000000",
        )
        // v1 had no H.264 profile choice: Cool's Baseline is not what it ran, so it is kept as the user's own.
        val settings = CastSettingsCodec.decode(light)!!
        assertEquals(QualityProfile.COOL.toValues().copy(h264HighProfile = true), settings.active)
        assertNull(CastSettingsCodec.decode(flat(1, "profile" to "\"turbo\"")))
        assertNull("the old names are v1's only", CastSettingsCodec.decode(flat(2, "profile" to "\"light\"")))
    }

    @Test
    fun `fields a version did not have yet take the default shipped with it`() {
        val v2 = CastSettingsCodec.decode(flat(2))!!
        assertEquals(true, v2.active.autoQuality)
        val v3 = CastSettingsCodec.decode(flat(3, "autoQuality" to "false"))!!
        assertEquals(false, v3.active.autoQuality)
        assertEquals(EncoderImplementation.HARDWARE, v3.active.encoderImpl)
        val v4 = CastSettingsCodec.decode(flat(4))!!
        assertEquals(MicDeviceSource.BUILTIN, v4.micDevice)
        val v5 = CastSettingsCodec.decode(flat(5, "micDevice" to "\"bluetooth\""))!!
        assertEquals(MicDeviceSource.BLUETOOTH, v5.micDevice)
        assertEquals(false, v5.active.lowLatencyGameAudio)
    }

    @Test
    fun `the audio switches and the low-latency choice survive the migration`() {
        val settings = CastSettingsCodec.decode(
            flat(6, "gameAudio" to "false", "mic" to "true", "lowLatencyGameAudio" to "true"),
        )!!
        assertEquals(false, settings.gameAudio)
        assertEquals(true, settings.mic)
        assertEquals("it lives in the profile now", true, settings.customProfiles.single().values.lowLatencyGameAudio)
        assertEquals(true, settings.toConfig().lowLatencyGameAudio)
    }

    @Test
    fun `an older document with a value this build does not offer decodes to null`() {
        assertNull(CastSettingsCodec.decode(flat(6, "longEdgePx" to "1000")))
        assertNull(CastSettingsCodec.decode(flat(6, "fps" to "24")))
        assertNull(CastSettingsCodec.decode(flat(6, "bitrateMinBps" to "7000000")))
        assertNull(CastSettingsCodec.decode(flat(6, "bitrateMaxBps" to "25000000")))
        assertNull(CastSettingsCodec.decode(flat(6, "encoderFpsLimit" to "45")))
        assertNull(CastSettingsCodec.decode(flat(6, "degradationStrategy" to "\"vibes\"")))
        assertNull(CastSettingsCodec.decode(flat(6, "micDevice" to "\"telepathy\"")))
        assertNull(CastSettingsCodec.decode(flat(6, "fps" to null)))
    }

    // ---- v7 and v8: the earlier profile formats ----

    private val v7 = """{"v":7,"selected":"custom:1","profiles":[{"id":1,"name":"Custom 1","values":{"longEdgePx":1920,"fps":60,"bitrateMinBps":8000000,"bitrateMaxBps":12000000,"degradationStrategy":"framerate","autoQuality":false,"encoderImpl":"hardware","preferredCodec":"h264","h264HighProfile":true,"encoderFpsLimit":null,"lowLatencyGameAudio":true}}],"gameAudio":true,"mic":false,"micDevice":"builtin"}"""

    /** The tablet's own document after the one-day build with a single "struggle" choice. */
    private val v8 = """{"v":8,"selected":"custom:1","profiles":[{"id":1,"name":"Custom 1","values":{"longEdgePx":1920,"fps":60,"bitrateMinBps":8000000,"bitrateMaxBps":12000000,"struggle":"smooth","degradationStrategy":null,"encoderImpl":"hardware","preferredCodec":"h264","h264HighProfile":true,"encoderFpsLimit":null,"lowLatencyGameAudio":true}}],"gameAudio":true,"mic":false,"micDevice":"builtin"}"""

    @Test
    fun `a v7 document reads as it was stored`() {
        val values = CastSettingsCodec.decode(v7)!!.customProfiles.single().values
        assertEquals(DegradationStrategy.MAINTAIN_FRAMERATE, values.degradationStrategy)
        assertEquals("the switch the user set is kept", false, values.autoQuality)
        assertEquals(8_000_000 to 12_000_000, values.bitrateMinBps to values.bitrateMaxBps)
    }

    @Test
    fun `a v8 document's single choice becomes the two settings it stood for`() {
        fun valuesOf(choice: String) = CastSettingsCodec.decode(v8.replace("smooth", choice))!!.customProfiles.single().values
        assertEquals(DegradationStrategy.MAINTAIN_FRAMERATE to true, valuesOf("smooth").let { it.degradationStrategy to it.autoQuality })
        assertEquals(DegradationStrategy.MAINTAIN_RESOLUTION to true, valuesOf("sharp").let { it.degradationStrategy to it.autoQuality })
        assertEquals(DegradationStrategy.BALANCED to true, valuesOf("balanced").let { it.degradationStrategy to it.autoQuality })
        assertEquals("don't adjust was the switch turned off", DegradationStrategy.BALANCED to false, valuesOf("fixed").let { it.degradationStrategy to it.autoQuality })
        val kept = CastSettingsCodec.decode(v8)!!
        assertEquals("Custom 1", kept.customProfiles.single().name)
        assertEquals(true, kept.active.lowLatencyGameAudio)
        assertEquals(8_000_000, kept.toConfig().bitrateMinBps)
    }

    @Test
    fun `a v8 document with an unknown choice, or a profile with neither form, decodes to null`() {
        assertNull(CastSettingsCodec.decode(v8.replace("smooth", "vibes")))
        assertNull(CastSettingsCodec.decode(v8.replace(""""struggle":"smooth",""", "")))
    }

    @Test
    fun `a migrated document is written back as today's and reads the same`() {
        for (old in listOf(v7, v8)) {
            val migrated = CastSettingsCodec.decode(old)!!
            assertEquals(migrated, CastSettingsCodec.decode(CastSettingsCodec.encode(migrated)))
        }
        val migrated = CastSettingsCodec.decode(flat(6, "fps" to "60"))!!
        assertEquals(migrated, CastSettingsCodec.decode(CastSettingsCodec.encode(migrated)))
    }
}
