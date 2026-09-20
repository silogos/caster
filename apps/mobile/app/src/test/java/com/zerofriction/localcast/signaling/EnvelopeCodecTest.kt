package com.zerofriction.localcast.signaling

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
}
