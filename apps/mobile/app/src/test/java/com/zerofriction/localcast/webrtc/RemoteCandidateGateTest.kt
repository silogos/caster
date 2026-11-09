package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trickle-ICE ordering (webrtc.md): no candidate before its offer's answer, none from a superseded offer. */
class RemoteCandidateGateTest {

    private val gate = RemoteCandidateGate<String>()
    private val added = mutableListOf<String>()
    private val add: (String) -> Unit = { added.add(it) }

    @Test
    fun `candidates before the answer wait, then flush in arrival order`() {
        gate.reset() // offer sent
        gate.offer("c1", add)
        gate.offer("c2", add)
        assertTrue(added.isEmpty())

        assertTrue(gate.open(gate.generation, add))
        assertEquals(listOf("c1", "c2"), added)
    }

    @Test
    fun `candidates after the answer go straight in`() {
        gate.reset()
        gate.open(gate.generation, add)
        gate.offer("c3", add)
        assertEquals(listOf("c3"), added)
    }

    @Test
    fun `a new offer drops the old offer's queued candidates and closes the gate`() {
        gate.reset()
        gate.offer("old", add)
        gate.reset() // ICE restart before the answer landed
        gate.offer("new", add)
        gate.open(gate.generation, add)
        assertEquals(listOf("new"), added)

        gate.reset() // another restart: an open gate closes again
        gate.offer("after-restart", add)
        assertEquals(listOf("new"), added)
    }

    @Test
    fun `a late answer success from a superseded offer cannot open the gate`() {
        gate.reset()
        val firstOffer = gate.generation // answer arrived; setRemoteDescription in flight
        gate.reset() // the ICE ladder restarts before the callback lands
        gate.offer("restart-candidate", add)

        assertFalse(gate.open(firstOffer, add))
        assertTrue(added.isEmpty()) // still waiting for the restart's own answer

        assertTrue(gate.open(gate.generation, add))
        assertEquals(listOf("restart-candidate"), added)
    }

    @Test
    fun `the queue flushes once - a repeated open adds nothing twice`() {
        gate.reset()
        gate.offer("c1", add)
        gate.open(gate.generation, add)
        gate.open(gate.generation, add)
        assertEquals(listOf("c1"), added)
    }
}
