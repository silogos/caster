package io.github.silogos.caster.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

/** The re-auth decision for the media pc (MediaCastSession, ADR-036). */
class ReauthActionTest {

    @Test
    fun `a connected pc with nothing pending is kept - no keyframe freeze`() {
        assertEquals(ReauthAction.KEEP, reauthAction(hasPc = true, pcConnected = true, offerUnanswered = false))
    }

    // Found live 2026-11-18: Wi-Fi off, the receiver's pc failed and the restart
    // offer died with the socket; ours reconnected, so it was kept, and the
    // receiver waited for an offer that never came.
    @Test
    fun `a connected pc whose offer went unanswered re-offers`() {
        assertEquals(ReauthAction.REOFFER, reauthAction(hasPc = true, pcConnected = true, offerUnanswered = true))
    }

    @Test
    fun `no pc or a pc that is not connected is rebuilt`() {
        assertEquals(ReauthAction.REBUILD, reauthAction(hasPc = false, pcConnected = false, offerUnanswered = false))
        assertEquals(ReauthAction.REBUILD, reauthAction(hasPc = true, pcConnected = false, offerUnanswered = true))
    }
}
