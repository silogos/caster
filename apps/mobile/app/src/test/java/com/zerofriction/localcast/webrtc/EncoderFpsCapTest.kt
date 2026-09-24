package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sender-side fps cap rule (Phase16): the live target (profile or adaptive
 * step) is enforced through the encoder because screencast capture cannot
 * throttle frames; the user's own Advanced limit can only tighten it.
 */
class EncoderFpsCapTest {

    @Test
    fun `the live fps is the cap when the user set no limit`() {
        assertEquals(30, effectiveEncoderFpsCap(liveFps = 30, userFpsLimit = null))
    }

    @Test
    fun `a tighter user limit wins`() {
        assertEquals(24, effectiveEncoderFpsCap(liveFps = 30, userFpsLimit = 24))
    }

    @Test
    fun `a looser user limit cannot raise the rate past the profile's intent`() {
        assertEquals(30, effectiveEncoderFpsCap(liveFps = 30, userFpsLimit = 60))
    }

    @Test
    fun `an adaptive step's lower target composes with the user's limit`() {
        // Stepped down to 30 while the user asked for 24 all along: 24 holds.
        assertEquals(24, effectiveEncoderFpsCap(liveFps = 30, userFpsLimit = 24))
        // Stepped below the user's own limit: the step wins.
        assertEquals(20, effectiveEncoderFpsCap(liveFps = 20, userFpsLimit = 24))
    }
}
