package com.zerofriction.localcast.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase10 persistence: the settings document round-trips, and anything this
 * build can't fully validate (corrupt JSON, wrong version, unknown values)
 * decodes to null — the store then degrades to defaults, never a cast on
 * guessed values (same convention as the desktop mixer's localStorage).
 */
class CastSettingsCodecTest {

    private val custom = CastSettings(
        profile = QualityProfile.SMOOTH,
        longEdgePx = 960,
        fps = 60,
        bitrateAuto = false,
        bitrateMinBps = 3_000_000,
        bitrateMaxBps = 6_000_000,
        gameAudio = false,
        mic = true,
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
        val future = CastSettingsCodec.encode(custom).replace(""""v":1""", """"v":2""")
        assertNull(CastSettingsCodec.decode(future))
    }

    @Test
    fun `an unknown preset label decodes to null`() {
        val encoded = CastSettingsCodec.encode(custom)
            .replace(""""profile":"smooth"""", """"profile":"ultra"""")
        assertNull(CastSettingsCodec.decode(encoded))
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
