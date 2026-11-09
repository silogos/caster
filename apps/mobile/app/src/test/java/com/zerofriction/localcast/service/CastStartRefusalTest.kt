package com.zerofriction.localcast.service

import com.zerofriction.localcast.signaling.SignalingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastStartRefusalTest {
    private val authorized = SignalingClient.State.AUTHORIZED

    @Test
    fun `a live pairing with nothing running starts`() {
        assertNull(startRefusal(pending = false, castState = CastState.Idle, signaling = authorized))
        assertNull(startRefusal(false, CastState.Failed("x"), SignalingClient.State.RECONNECTING))
    }

    @Test
    fun `a second start while one is pending or running is refused`() {
        assertEquals(StartRefusal.BUSY, startRefusal(true, CastState.Idle, authorized))
        assertEquals(StartRefusal.BUSY, startRefusal(false, CastState.Starting("Mac"), authorized))
        assertEquals("a running cast must not be silently replaced", StartRefusal.BUSY, startRefusal(false, CastState.Casting("Mac"), authorized))
    }

    @Test
    fun `a dead pairing is refused`() {
        for (dead in SignalingClient.State.values().filter { it != authorized && it != SignalingClient.State.RECONNECTING }) {
            assertEquals("$dead", StartRefusal.DEAD_PAIRING, startRefusal(false, CastState.Idle, dead))
        }
    }
}
