package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mic pc's reconnect ladder (reliability.md A8, formalized after the
 * live finding: a connection reset kept screen + game audio but parked the
 * mic as Failed). Behavior pinned: the ladder's shape, the repeating last
 * rung, and the give-up budget that parks the session.
 */
class MicReconnectPolicyTest {

    @Test
    fun `the ladder walks 1-2-5-10-30 seconds`() {
        assertEquals(1_000L, MicReconnectPolicy.backoffMsFor(0))
        assertEquals(2_000L, MicReconnectPolicy.backoffMsFor(1))
        assertEquals(5_000L, MicReconnectPolicy.backoffMsFor(2))
        assertEquals(10_000L, MicReconnectPolicy.backoffMsFor(3))
        assertEquals(30_000L, MicReconnectPolicy.backoffMsFor(4))
    }

    @Test
    fun `the budget is spent after MAX_ATTEMPTS - the session parks, never loops`() {
        assertNull(MicReconnectPolicy.backoffMsFor(MicReconnectPolicy.MAX_ATTEMPTS))
        assertNull(MicReconnectPolicy.backoffMsFor(MicReconnectPolicy.MAX_ATTEMPTS + 1))
    }

    @Test
    fun `a malformed attempt index is a spent budget, not a crash`() {
        assertNull(MicReconnectPolicy.backoffMsFor(-1))
    }
}
