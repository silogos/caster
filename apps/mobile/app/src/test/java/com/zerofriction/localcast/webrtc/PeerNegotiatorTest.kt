package com.zerofriction.localcast.webrtc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offerer sequencing both pcs share (finding #23): offer → local
 * description → wire, answer → candidates, ICE restart, and what a replaced
 * pc's late libwebrtc callbacks may (not) do. A scripted [PeerPort] stands in
 * for libwebrtc; its callbacks fire when the test says, like the real
 * signaling thread's would.
 */
class PeerNegotiatorTest {

    private class FakePeer(val name: String) : PeerPort<String> {
        val calls = mutableListOf<String>()
        val added = mutableListOf<String>()
        var offerSuccess: ((String) -> Unit)? = null
        var offerFailure: ((String?) -> Unit)? = null
        var localSuccess: (() -> Unit)? = null
        var localFailure: ((String?) -> Unit)? = null
        var remoteSuccess: (() -> Unit)? = null
        var remoteFailure: ((String?) -> Unit)? = null
        var localSdp: String? = null
        var remoteSdp: String? = null

        override fun createOffer(onSuccess: (sdp: String) -> Unit, onFailure: (error: String?) -> Unit) {
            calls += "createOffer"; offerSuccess = onSuccess; offerFailure = onFailure
        }
        override fun setLocalOffer(sdp: String, onSuccess: () -> Unit, onFailure: (error: String?) -> Unit) {
            calls += "setLocal"; localSdp = sdp; localSuccess = onSuccess; localFailure = onFailure
        }
        override fun setRemoteAnswer(sdp: String, onSuccess: () -> Unit, onFailure: (error: String?) -> Unit) {
            calls += "setRemote"; remoteSdp = sdp; remoteSuccess = onSuccess; remoteFailure = onFailure
        }
        override fun addRemoteCandidate(candidate: String) { added += candidate }
        override fun restartIce() { calls += "restartIce" }
        override fun dispose() { calls += "dispose" }

        /** The libwebrtc side completes an offer round: created, then set locally. */
        fun completeOffer(sdp: String = "v=0 offer") {
            offerSuccess!!(sdp)
            localSuccess!!()
        }
    }

    private val sent = mutableListOf<String>()
    private val offerFailures = mutableListOf<String>()
    private val answerFailures = mutableListOf<String?>()
    private val events = mutableListOf<String>()

    /** The session thread: callbacks queue here until [drain] — like Handler.post. */
    private val sessionQueue = ArrayDeque<() -> Unit>()
    private fun drain() { while (sessionQueue.isNotEmpty()) sessionQueue.removeFirst()() }

    private val negotiator = PeerNegotiator(
        label = "media",
        post = { sessionQueue.addLast(it) },
        sendOffer = { sent += it },
        decodeCandidate = { element -> (element as? JsonObject)?.get("candidate")?.jsonPrimitive?.content },
        onOfferFailed = { offerFailures += it },
        offerTransform = { "$it +munged" },
        answerTransform = { "$it +relaxed" },
        onAnswerApplied = { events += "answer-applied" },
        onAnswerFailed = { answerFailures += it },
    )

    private fun candidate(id: String): JsonElement = JsonObject(mapOf("candidate" to JsonPrimitive(id)))

    @Test
    fun `the munged offer is set locally first and only then sent - on the session thread`() {
        val peer = FakePeer("pc1")
        negotiator.attach(peer)
        negotiator.offer()
        peer.offerSuccess!!("v=0 offer")

        assertEquals("v=0 offer +munged", peer.localSdp)
        assertTrue(sent.isEmpty()) // not before the pc holds it

        peer.localSuccess!!()
        assertTrue(sent.isEmpty()) // libwebrtc's thread never touches the wire directly
        drain()
        assertEquals(listOf("v=0 offer +munged"), sent)
    }

    @Test
    fun `offer failures are reported with their stage`() {
        val peer = FakePeer("pc1")
        negotiator.attach(peer)
        negotiator.offer()
        peer.offerFailure!!("no codecs")
        drain()
        negotiator.offer()
        peer.offerSuccess!!("v=0")
        peer.localFailure!!("bad sdp")
        drain()

        assertEquals(listOf("createOffer: no codecs", "setLocalDescription: bad sdp"), offerFailures)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `early candidates wait for the answer, then follow the answer hook in arrival order`() {
        val peer = FakePeer("pc1")
        negotiator.attach(peer)
        negotiator.offer()
        peer.completeOffer()
        negotiator.applyRemoteCandidate(candidate("c1"))
        negotiator.applyRemoteCandidate(candidate("c2"))
        negotiator.applyAnswer("v=0 answer")
        assertEquals("v=0 answer +relaxed", peer.remoteSdp)
        assertTrue(peer.added.isEmpty())

        peer.remoteSuccess!!()
        drain()
        assertEquals(listOf("answer-applied"), events) // sender parameters before the flush
        assertEquals(listOf("c1", "c2"), peer.added)

        negotiator.applyRemoteCandidate(candidate("c3"))
        assertEquals(listOf("c1", "c2", "c3"), peer.added)
    }

    @Test
    fun `end-of-candidates and malformed candidates add nothing`() {
        val peer = FakePeer("pc1")
        negotiator.attach(peer)
        negotiator.applyAnswer("a")
        peer.remoteSuccess!!()
        drain()

        negotiator.applyRemoteCandidate(JsonNull)
        negotiator.applyRemoteCandidate(JsonPrimitive("not an object"))
        assertTrue(peer.added.isEmpty())
    }

    @Test
    fun `a failed answer reports once and keeps candidates queued`() {
        val peer = FakePeer("pc1")
        negotiator.attach(peer)
        negotiator.applyRemoteCandidate(candidate("c1"))
        negotiator.applyAnswer("a")
        peer.remoteFailure!!("bad answer")
        drain()

        assertEquals(listOf<String?>("bad answer"), answerFailures)
        assertTrue(events.isEmpty())
        assertTrue(peer.added.isEmpty())
    }

    @Test
    fun `an ICE restart re-offers on the same pc and its candidates wait for the new answer`() {
        val peer = FakePeer("pc1")
        negotiator.attach(peer)
        negotiator.offer()
        peer.completeOffer()
        negotiator.applyAnswer("a1")
        peer.remoteSuccess!!()
        drain()
        peer.calls.clear()

        negotiator.restartIce()
        assertEquals(listOf("restartIce", "createOffer"), peer.calls)
        negotiator.applyRemoteCandidate(candidate("restart-c1"))
        assertTrue(peer.added.isEmpty())

        peer.completeOffer("v=0 restart")
        negotiator.applyAnswer("a2")
        peer.remoteSuccess!!()
        drain()
        assertEquals(listOf("restart-c1"), peer.added)
    }

    @Test
    fun `a replaced pc's late callbacks cannot send, fail, or open the new pc's gate`() {
        val old = FakePeer("old")
        negotiator.attach(old)
        negotiator.offer()
        negotiator.applyAnswer("old-answer")

        // Re-auth rebuilds the pc before the old callbacks land.
        val fresh = FakePeer("fresh")
        negotiator.attach(fresh)
        assertTrue("dispose" in old.calls)
        negotiator.applyRemoteCandidate(candidate("fresh-c1"))

        old.completeOffer()
        old.remoteSuccess!!()
        old.offerFailure!!("late")
        old.remoteFailure!!("late")
        drain()

        assertTrue(sent.isEmpty())
        assertTrue(events.isEmpty())
        assertTrue(offerFailures.isEmpty())
        assertTrue(answerFailures.isEmpty())
        assertTrue(fresh.added.isEmpty())
        assertTrue(old.added.isEmpty())
    }

    @Test
    fun `after detach nothing is negotiated`() {
        val peer = FakePeer("pc1")
        negotiator.attach(peer)
        negotiator.detach()
        negotiator.offer()
        negotiator.applyAnswer("a")
        negotiator.applyRemoteCandidate(candidate("c1"))

        assertEquals(listOf("dispose"), peer.calls)
    }
}
