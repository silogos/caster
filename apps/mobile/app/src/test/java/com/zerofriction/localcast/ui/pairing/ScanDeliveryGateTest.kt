package com.zerofriction.localcast.ui.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDeliveryGateTest {
    private var now = 1_000L
    private val gate = ScanDeliveryGate(clock = { now })

    @Test
    fun `the same code is not re-delivered at frame rate`() {
        assertTrue(gate.shouldDeliver("A"))
        repeat(30) { now += 33; assertFalse(gate.shouldDeliver("A")) }
    }

    @Test
    fun `after a wrong code the right one gets through at once`() {
        assertTrue(gate.shouldDeliver("not-ours"))
        now += 33
        assertTrue("the right QR must not be blocked by the wrong one", gate.shouldDeliver("ours"))
    }

    @Test
    fun `the same code can be retried after a pause`() {
        assertTrue(gate.shouldDeliver("ours"))
        now += ScanDeliveryGate.SAME_CODE_RETRY_MS
        assertTrue("a failed attempt can be retried without leaving the screen", gate.shouldDeliver("ours"))
    }
}
