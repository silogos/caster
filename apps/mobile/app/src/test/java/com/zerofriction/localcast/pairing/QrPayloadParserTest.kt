package com.zerofriction.localcast.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadParserTest {

    @Test
    fun `parses a spec-shaped payload v1`() {
        val payload = QrPayloadParser.parse(VALID_PAYLOAD).getOrThrow()

        assertEquals(1, payload.v)
        assertEquals("zfc", payload.t)
        assertEquals(listOf("192.168.1.42", "192.168.137.1"), payload.h)
        assertEquals(52341, payload.p)
        assertEquals("Jm1LIOO4mWm0lRSSl2fClw", payload.s)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", payload.k)
        assertEquals(1758300000L, payload.e)
    }

    @Test
    fun `rejects a non-zfc QR code`() {
        val result = QrPayloadParser.parse("""{"v":1,"t":"other","h":["1.2.3.4"],"p":1,"s":"x","k":"y","e":5}""")

        assertTrue(result.exceptionOrNull() is PairingParseException)
        assertEquals(QrPayloadError.NOT_ZFC_QR, (result.exceptionOrNull() as PairingParseException).error)
    }

    @Test
    fun `rejects an unknown payload version as unsupported`() {
        val result = QrPayloadParser.parse(VALID_PAYLOAD.replace("\"v\":1", "\"v\":2"))

        assertTrue(result.exceptionOrNull() is PairingParseException)
        assertEquals(QrPayloadError.UNSUPPORTED_VERSION, (result.exceptionOrNull() as PairingParseException).error)
    }

    @Test
    fun `rejects unparseable text as not zfc`() {
        val result = QrPayloadParser.parse("https://example.com/some-page")

        assertTrue(result.exceptionOrNull() is PairingParseException)
        assertEquals(QrPayloadError.NOT_ZFC_QR, (result.exceptionOrNull() as PairingParseException).error)
    }

    @Test
    fun `rejects a payload without hosts`() {
        val result = QrPayloadParser.parse(VALID_PAYLOAD.replace("192.168.1.42", ""))

        assertTrue(result.exceptionOrNull() is PairingParseException)
    }

    private companion object {
        const val VALID_PAYLOAD =
            """{"v":1,"t":"zfc","h":["192.168.1.42","192.168.137.1"],"p":52341,"s":"Jm1LIOO4mWm0lRSSl2fClw","k":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","e":1758300000}"""
    }
}
