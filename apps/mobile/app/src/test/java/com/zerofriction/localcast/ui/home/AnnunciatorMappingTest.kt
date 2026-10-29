package com.zerofriction.localcast.ui.home

import com.zerofriction.localcast.service.CastState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app-bar annunciator's cast-state mapping (designs/
 * mobile-app.html): one machine word per state — Idle-with-a-pairing
 * reads STANDBY, ON AIR only while streaming, FAULT on failure.
 */
class AnnunciatorMappingTest {

    @Test
    fun `idle reads standby`() {
        assertEquals(ConsoleAnnunciator.STANDBY, annunciatorFor(CastState.Idle))
    }

    @Test
    fun `starting reads starting`() {
        assertEquals(ConsoleAnnunciator.STARTING, annunciatorFor(CastState.Starting("Gaming PC")))
    }

    @Test
    fun `casting reads on air`() {
        assertEquals(ConsoleAnnunciator.ON_AIR, annunciatorFor(CastState.Casting("Gaming PC")))
    }

    @Test
    fun `failure reads fault`() {
        assertEquals(ConsoleAnnunciator.FAULT, annunciatorFor(CastState.Failed("Couldn't start the cast.")))
    }
}
