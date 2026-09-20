package com.zerofriction.localcast.signaling

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeCodecTest {

    @Test
    fun `round-trips a well-formed envelope`() {
        val encoded = EnvelopeCodec.encode(
            Envelope.TYPE_HELLO,
            seq = 1,
            sid = "session-id",
            payload = Payloads.hello(TEST_UA, 1, 1),
        )

        val parsed = EnvelopeCodec.parse(encoded)

        assertTrue(parsed is EnvelopeParseResult.Valid)
        val envelope = (parsed as EnvelopeParseResult.Valid).envelope
        assertEquals(1, envelope.v)
        assertEquals(Envelope.TYPE_HELLO, envelope.type)
        assertEquals(1, envelope.seq)
        assertEquals("session-id", envelope.sid)
        assertEquals(TEST_UA, Payloads.helloUa(envelope))
        assertEquals(1, Payloads.helloProtoMin(envelope))
    }

    private companion object {
        const val TEST_UA = "ZeroFrictionCast/0.1.0 (Android 15; Pixel8)"
        const val SESSION_ID = "session-id"
        const val SDP_BLOB = "v=0\r\no=-4611731400430051336 2 IN IP4 127.0.0.1\r\n"
    }

    @Test
    fun `rejects an unknown envelope version as terminal`() {
        val raw = """{"v":2,"type":"hello","seq":1,"sid":"s","payload":{}}"""

        assertEquals(EnvelopeParseResult.BadVersion, EnvelopeCodec.parse(raw))
    }

    @Test
    fun `rejects malformed frames as recoverable`() {
        assertEquals(EnvelopeParseResult.BadMessage, EnvelopeCodec.parse("not json"))
        assertEquals(
            EnvelopeParseResult.BadMessage,
            EnvelopeCodec.parse("""{"v":1,"type":"hello","seq":0,"sid":"s","payload":{}}"""),
        )
        assertEquals(
            EnvelopeParseResult.BadMessage,
            EnvelopeCodec.parse("""{"v":1,"type":"hello","seq":1,"sid":"","payload":{}}"""),
        )
    }

    // ---- Phase4 message set (webrtc.md) ----

    @Test
    fun `round-trips sdp payloads with the pc discriminator`() {
        val encoded = EnvelopeCodec.encode(
            Envelope.TYPE_SDP_OFFER,
            seq = 1,
            sid = SESSION_ID,
            payload = Payloads.sdp(Envelope.PC_MEDIA, SDP_BLOB),
        )
        val envelope = parse(encoded)

        assertEquals(Envelope.TYPE_SDP_OFFER, envelope.type)
        assertEquals(Envelope.PC_MEDIA, Payloads.pc(envelope))
        assertEquals(SDP_BLOB, Payloads.sdpText(envelope))
    }

    @Test
    fun `round-trips ice candidates including end-of-gathering`() {
        val candidate = """
            {"candidate":"candidate:842163049 1 udp 1677729535 7f8a9b2c-3d4e-5f60-a7b8-c9d0e1f23a45.local 51812 typ host","sdpMid":"0","sdpMLineIndex":0}
        """.trimIndent()
        val candidateJson =
            kotlinx.serialization.json.Json.parseToJsonElement(candidate)

        val withCandidate = parse(
            EnvelopeCodec.encode(Envelope.TYPE_ICE, 1, SESSION_ID, Payloads.ice(Envelope.PC_MIC, candidateJson)),
        )
        assertEquals(Envelope.PC_MIC, Payloads.pc(withCandidate))
        assertEquals(candidateJson, Payloads.iceCandidate(withCandidate))

        // `candidate: null` marks end-of-gathering for the pc (webrtc.md).
        val endOfGathering = parse(
            EnvelopeCodec.encode(Envelope.TYPE_ICE, 2, SESSION_ID, Payloads.ice(Envelope.PC_MIC, null)),
        )
        assertTrue(Payloads.iceCandidate(endOfGathering) is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `round-trips heartbeat timestamps and bye reasons`() {
        val ping = parse(EnvelopeCodec.encode(Envelope.TYPE_PING, 1, SESSION_ID, Payloads.ping(1234567890)))
        assertEquals(1234567890L, Payloads.pingT(ping))

        val bye = parse(EnvelopeCodec.encode(Envelope.TYPE_BYE, 2, SESSION_ID, Payloads.bye("user-ended")))
        assertEquals("user-ended", Payloads.byeReason(bye))

        val bareBye = parse(EnvelopeCodec.encode(Envelope.TYPE_BYE, 3, SESSION_ID, Payloads.bye()))
        assertEquals(null, Payloads.byeReason(bareBye))
    }

    @Test
    fun `round-trips session-info fields`() {
        val info = parse(
            EnvelopeCodec.encode(
                Envelope.TYPE_SESSION_INFO,
                1,
                SESSION_ID,
                Payloads.sessionInfo(profile = "balanced", width = 1280, height = 720, fps = 30, gameAudio = true, mic = false),
            ),
        )
        val gameAudio = info.payload["gameAudio"]?.jsonPrimitive?.boolean
        val mic = info.payload["mic"]?.jsonPrimitive?.boolean
        val width = info.payload["width"]?.jsonPrimitive?.int
        val fps = info.payload["fps"]?.jsonPrimitive?.int
        val profile = info.payload["profile"]?.jsonPrimitive?.content
        assertEquals(true, gameAudio)
        assertEquals(false, mic)
        assertEquals(1280, width)
        assertEquals(30, fps)
        assertEquals("balanced", profile)
    }

    private fun parse(raw: String): Envelope {
        val parsed = EnvelopeCodec.parse(raw)
        assertTrue(parsed is EnvelopeParseResult.Valid)
        return (parsed as EnvelopeParseResult.Valid).envelope
    }
}
