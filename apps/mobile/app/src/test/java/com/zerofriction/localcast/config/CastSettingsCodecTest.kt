package com.zerofriction.localcast.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase10 persistence: the settings document round-trips, and anything this
 * build can't fully validate (corrupt JSON, wrong version, unknown values)
 * decodes to null — the store then degrades to defaults, never a cast on
 * guessed values (same convention as the desktop mixer's localStorage).
 */
class CastSettingsCodecTest {

    private val custom = CastSettings(
        profile = QualityProfile.PERFORMANCE,
        longEdgePx = 960,
        fps = 60,
        bitrateAuto = false,
        bitrateMinBps = 3_000_000,
        bitrateMaxBps = 6_000_000,
        gameAudio = false,
        mic = true,
        autoQuality = true,
    )

    @Test
    fun `round-trips every field`() {
        val decoded = CastSettingsCodec.decode(CastSettingsCodec.encode(custom))
        assertEquals(custom, decoded)
    }

    @Test
    fun `round-trips the defaults`() {
        assertEquals(CastSettings.default(), CastSettingsCodec.decode(CastSettingsCodec.encode(CastSettings.default())))
    }

    @Test
    fun `unknown keys are tolerated forward-compatibly`() {
        val encoded = CastSettingsCodec.encode(custom)
            .replace(""""v":""", """"zfcFutureField":9,"v":""")
        assertEquals(custom, CastSettingsCodec.decode(encoded))
    }

    @Test
    fun `corrupt json decodes to null`() {
        assertNull(CastSettingsCodec.decode("{not json"))
        assertNull(CastSettingsCodec.decode(""))
    }

    @Test
    fun `a future version decodes to null`() {
        val future = CastSettingsCodec.encode(custom).replace(""""v":4""", """"v":5""")
        assertNull(CastSettingsCodec.decode(future))
    }

    @Test
    fun `an unknown preset label decodes to null`() {
        val encoded = CastSettingsCodec.encode(custom)
            .replace(""""profile":"performance"""", """"profile":"ultra"""")
        assertNull(CastSettingsCodec.decode(encoded))
    }

    // ---- v1 legacy documents (Phase10 shape, pre-thermal relabels) ----

    /** A v1 document: downgraded version field, pre-rename label, same values. */
    private fun v1Document(v3Document: String, legacyLabel: String): String = v3Document
        .replace(""""v":4""", """"v":1""")
        .replace(""""profile":"${custom.profile.label}"""", """"profile":"$legacyLabel"""")

    @Test
    fun `a v1 light document decodes as cool with the same values`() {
        val stored = v1Document(CastSettingsCodec.encode(custom.copy(profile = QualityProfile.COOL)), legacyLabel = "light")
        assertEquals(custom.copy(profile = QualityProfile.COOL), CastSettingsCodec.decode(stored))
    }

    @Test
    fun `a v1 smooth document decodes as performance with the same values`() {
        val stored = v1Document(CastSettingsCodec.encode(custom), legacyLabel = "smooth")
        assertEquals(custom, CastSettingsCodec.decode(stored))
    }

    @Test
    fun `a v1 document with an unknown label still decodes to null`() {
        val stored = v1Document(CastSettingsCodec.encode(custom), legacyLabel = "ultra")
        assertNull(CastSettingsCodec.decode(stored))
    }

    // ---- v2 legacy documents (Phase11 shape, pre-auto-quality) ----

    /** A v2 document: only the version field downgraded — no `autoQuality` key at all. */
    private fun v2Document(v3Document: String): String = v3Document
        .replace(""""v":4""", """"v":2""")
        .replace(""",""autoQuality":true""", "")

    @Test
    fun `a v2 document decodes with the auto-quality default on`() {
        val stored = v2Document(CastSettingsCodec.encode(custom.copy(autoQuality = true)))
        assertEquals(custom, CastSettingsCodec.decode(stored))
    }

    @Test
    fun `a v3 document with the switch off round-trips it`() {
        val off = custom.copy(autoQuality = false)
        assertEquals(off, CastSettingsCodec.decode(CastSettingsCodec.encode(off)))
    }

    @Test
    fun `the round-trip document carries the v4 version`() {
        assertTrue(CastSettingsCodec.encode(custom).contains(""""v":4"""))
    }

    // ---- v3 legacy documents (pre-Advanced) decode with today's-behavior defaults ----

    /**
     * A v3 document: the version downgraded and every Advanced key stripped
     * (leading-comma form — the Advanced fields all follow earlier fields, so
     * the object's closing brace is never touched).
     */
    private fun v3Document(v4Document: String): String =
        Regex(""","(encoderImpl|h264HighProfile|preferredCodec|degradationStrategy|encoderFpsLimit|bitrateMode)":[^,}]*""")
            .replace(v4Document.replace(""""v":4""", """"v":3"""), "")

    @Test
    fun `a v3 document decodes with the Advanced defaults`() {
        val stored = v3Document(CastSettingsCodec.encode(custom))
        assertEquals(custom, CastSettingsCodec.decode(stored))
    }

    // ---- Advanced (v4) round-trips and validation ----

    @Test
    fun `advanced overrides round-trip`() {
        val advanced = custom.copy(
            encoderImpl = EncoderImplementation.SOFTWARE,
            h264HighProfile = false,
            preferredCodec = PreferredVideoCodec.VP8,
            degradationStrategy = DegradationStrategy.MAINTAIN_FRAMERATE,
            encoderFpsLimit = 30,
            bitrateMode = EncoderBitrateMode.VBR,
        )
        assertEquals(advanced, CastSettingsCodec.decode(CastSettingsCodec.encode(advanced)))
    }

    @Test
    fun `unknown Advanced labels decode to null`() {
        val badEncoder = CastSettingsCodec.encode(custom).replace(""""encoderImpl":"hardware"""", """"encoderImpl":"qti"""")
        assertNull(CastSettingsCodec.decode(badEncoder))

        val badCodec = CastSettingsCodec.encode(custom).replace(""""preferredCodec":"h264"""", """"preferredCodec":"av1"""")
        assertNull(CastSettingsCodec.decode(badCodec))

        val badDegradation = CastSettingsCodec.encode(custom).replace(""""degradationStrategy":"balanced"""", """"degradationStrategy":"fluid"""")
        assertNull(CastSettingsCodec.decode(badDegradation))

        val badMode = CastSettingsCodec.encode(custom).replace(""""bitrateMode":"cbr"""", """"bitrateMode":"abrr"""")
        assertNull(CastSettingsCodec.decode(badMode))
    }

    @Test
    fun `an fps limit outside the choices decodes to null`() {
        val bad = CastSettingsCodec.encode(custom.copy(encoderFpsLimit = 30))
            .replace(""""encoderFpsLimit":30""", """"encoderFpsLimit":45""")
        assertNull(CastSettingsCodec.decode(bad))
    }

    @Test
    fun `a null fps limit round-trips as off`() {
        assertEquals(null, CastSettingsCodec.decode(CastSettingsCodec.encode(custom.copy(encoderFpsLimit = null)))?.encoderFpsLimit)
    }

    @Test
    fun `values outside the settings screen's choices decode to null`() {
        val badResolution = CastSettingsCodec.encode(custom).replace(""""longEdgePx":960""", """"longEdgePx":1440""")
        assertNull(CastSettingsCodec.decode(badResolution))

        val badFps = CastSettingsCodec.encode(custom).replace(""""fps":60""", """"fps":25""")
        assertNull(CastSettingsCodec.decode(badFps))
    }

    @Test
    fun `an inverted or oversized bitrate window decodes to null`() {
        val inverted = CastSettingsCodec.encode(custom)
            .replace(""""bitrateMinBps":3000000""", """"bitrateMinBps":7000000""")
        assertNull(CastSettingsCodec.decode(inverted))

        val oversized = CastSettingsCodec.encode(custom)
            .replace(""""bitrateMaxBps":6000000""", """"bitrateMaxBps":50000000""")
        assertNull(CastSettingsCodec.decode(oversized))
    }

    @Test
    fun `a missing field decodes to null`() {
        val encoded = CastSettingsCodec.encode(custom)
        assertNull(CastSettingsCodec.decode(encoded.substringBefore(""""gameAudio""")))
    }
}
