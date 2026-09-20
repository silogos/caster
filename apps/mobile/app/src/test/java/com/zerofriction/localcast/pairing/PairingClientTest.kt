package com.zerofriction.localcast.pairing

import com.zerofriction.localcast.signaling.Envelope
import com.zerofriction.localcast.signaling.EnvelopeCodec
import com.zerofriction.localcast.signaling.EnvelopeParseResult
import com.zerofriction.localcast.signaling.Handshake
import com.zerofriction.localcast.signaling.Payloads
import com.zerofriction.localcast.signaling.SignalingTransport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Behavior tests for the pairing state machine with a scripted fake transport:
 * the machine must produce the exact wire frames the desktop loopback tests
 * (apps/desktop …/signalingServer.test.ts) accept.
 */
class PairingClientTest {

    @org.junit.Test
    fun `completes the handshake and reaches Connected`() {
        val fake = FakeTransport()
        val client = PairingClient({ fake }) { TEST_UA }

        client.startFromQrText(VALID_PAYLOAD)

        // hello
        fake.listener?.onTransportOpen()
        val hello = parseFrame(fake.sentFrames.removeAt(0))
        assertEquals(Envelope.TYPE_HELLO, hello.type)
        assertEquals(1, hello.seq)
        assertEquals(VECTOR_SID, hello.sid)
        assertEquals(TEST_UA, Payloads.helloUa(hello))
        assertEquals(1, Payloads.helloProtoMin(hello))
        assertEquals(PairingClient.State.Authenticating, client.state.value)

        // challenge → auth with the exact HMAC of the scanned secret
        fake.listener?.onTransportText(challengeFrame(VECTOR_NONCE))
        val auth = parseFrame(fake.sentFrames.removeAt(0))
        assertEquals(Envelope.TYPE_AUTH, auth.type)
        assertEquals(2, auth.seq)
        assertEquals(VECTOR_MAC, auth.payload["mac"]?.jsonPrimitive?.content)
        assertEquals(VECTOR_SID, auth.sid)

        // auth-ok → Connected with the desktop name
        fake.listener?.onTransportText(authOkFrame(TEST_DESKTOP_NAME))
        assertEquals(PairingClient.State.Connected(TEST_DESKTOP_NAME), client.state.value)
    }

    @org.junit.Test
    fun `maps a terminal error to the matching user-facing failure`() {
        val fake = FakeTransport()
        val client = PairingClient({ fake }) { TEST_UA }

        client.startFromQrText(VALID_PAYLOAD)
        fake.listener?.onTransportOpen()
        fake.listener?.onTransportText(challengeFrame(VECTOR_NONCE))
        fake.listener?.onTransportText(errorFrame("unknown-session"))

        assertEquals(PairingClient.State.Failed(PairingError.EXPIRED), client.state.value)
    }

    @org.junit.Test
    fun `rejects a non-zfc QR without opening any connection`() {
        val fake = FakeTransport()
        val client = PairingClient({ fake }) { TEST_UA }

        client.startFromQrText("https://example.com")

        assertEquals(PairingClient.State.Failed(PairingError.NOT_ZFC_QR), client.state.value)
        assertEquals(0, fake.openedUrls.size)
    }

    @org.junit.Test
    fun `tries every candidate host before giving up as unreachable`() {
        val twoHostPayload = VALID_PAYLOAD.replace("192.168.137.1", "10.0.0.9")
        val fake = FakeTransport(failOnOpen = true)
        val client = PairingClient({ fake }) { TEST_UA }

        client.startFromQrText(twoHostPayload)

        assertEquals(
            listOf("ws://192.168.1.42:52341/zfc/v1", "ws://10.0.0.9:52341/zfc/v1"),
            fake.openedUrls,
        )
        assertEquals(PairingClient.State.Failed(PairingError.CONNECT_UNREACHABLE), client.state.value)
    }

    private fun parseFrame(raw: String): Envelope {
        val parsed = EnvelopeCodec.parse(raw)
        assertTrue(parsed is EnvelopeParseResult.Valid)
        return (parsed as EnvelopeParseResult.Valid).envelope
    }

    private fun challengeFrame(nonce: String): String =
        EnvelopeCodec.encode(Envelope.TYPE_CHALLENGE, 1, VECTOR_SID, buildJsonObject { put("n", nonce) })

    private fun authOkFrame(desktopName: String): String = EnvelopeCodec.encode(
        Envelope.TYPE_AUTH_OK,
        2,
        VECTOR_SID,
        buildJsonObject {
            put("name", desktopName)
            put("proto", 1)
        },
    )

    private fun errorFrame(code: String): String =
        EnvelopeCodec.encode(Envelope.TYPE_ERROR, 1, VECTOR_SID, buildJsonObject { put("code", code) })

    private companion object {
        const val TEST_UA = "ZeroFrictionCast/0.1.0 (Android 15; Pixel8)"
        const val TEST_DESKTOP_NAME = "test-desktop"
        const val VECTOR_SID = "Jm1LIOO4mWm0lRSSl2fClw"
        const val VECTOR_NONCE = "EBESExQVFhcYGRobHB0eHw"
        // Same cross-platform vector as HandshakeTest — the mac is verified there.
        val VECTOR_MAC = Handshake.computeMac(
            Handshake.decodeSecret("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
            VECTOR_SID,
            VECTOR_NONCE,
        )
        const val VALID_PAYLOAD =
            """{"v":1,"t":"zfc","h":["192.168.1.42","192.168.137.1"],"p":52341,"s":"Jm1LIOO4mWm0lRSSl2fClw","k":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","e":1758300000}"""
    }
}

/** Scripts the desktop's side of the transport; records everything the phone sends. */
private class FakeTransport(private val failOnOpen: Boolean = false) : SignalingTransport {

    val sentFrames = ArrayDeque<String>()
    val openedUrls = mutableListOf<String>()
    var listener: SignalingTransport.Listener? = null
        private set

    override fun open(url: String, listener: SignalingTransport.Listener) {
        openedUrls.add(url)
        this.listener = listener
        if (failOnOpen) listener.onTransportFailure(RuntimeException("simulated unreachable host"))
    }

    override fun send(text: String): Boolean {
        sentFrames.add(text)
        return true
    }

    override fun close(code: Int, reason: String) {
        listener?.onTransportClosed(code, reason)
    }
}
