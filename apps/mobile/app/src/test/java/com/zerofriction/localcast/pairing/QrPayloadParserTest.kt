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

    private fun qr(s: String, k: String) =
        """{"v":1,"t":"zfc","h":["192.168.1.42"],"p":52341,"s":"$s","k":"$k","e":1758300000}"""

    @Test
    fun `a secret that is not base64url is a foreign QR, not a crash`() {
        val result = QrPayloadParser.parse(qr("Jm1LIOO4mWm0lRSSl2fClw", "!!"))
        assertEquals(QrPayloadError.NOT_ZFC_QR, (result.exceptionOrNull() as PairingParseException).error)
    }

    @Test
    fun `wrong-length session or secret is rejected`() {
        val shortSecret = QrPayloadParser.parse(qr("Jm1LIOO4mWm0lRSSl2fClw", "AAECAwQF"))
        val shortSession = QrPayloadParser.parse(qr("AAEC", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
        assertTrue(shortSecret.isFailure && shortSession.isFailure)
    }

    @Test
    fun `the documented sizes still parse`() {
        assertTrue(QrPayloadParser.parse(qr("Jm1LIOO4mWm0lRSSl2fClw", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")).isSuccess)
    }

    private fun errorOf(text: String, subnets: List<LanHosts.Subnet> = emptyList()) =
        (QrPayloadParser.parse(text, subnets).exceptionOrNull() as PairingParseException).error

    @Test
    fun `a QR pointing only at internet hosts is refused - LAN only`() {
        val internet = VALID_PAYLOAD.replace("\"192.168.1.42\",\"192.168.137.1\"", "\"203.0.113.9\",\"evil.example.com\"")
        assertEquals(QrPayloadError.NO_LAN_HOST, errorOf(internet))
    }

    @Test
    fun `off-LAN hosts are dropped and the LAN ones kept in order`() {
        val mixed = VALID_PAYLOAD.replace("\"192.168.1.42\",\"192.168.137.1\"", "\"100.101.1.2\",\"192.168.1.42\"")
        assertEquals(listOf("192.168.1.42"), QrPayloadParser.parse(mixed).getOrThrow().h)
    }

    @Test
    fun `a public desktop address on the phone's own subnet is accepted`() {
        val campus = VALID_PAYLOAD.replace("\"192.168.1.42\",\"192.168.137.1\"", "\"130.89.13.7\"")
        val phoneSubnet = listOf(LanHosts.Subnet(LanHosts.parseIpv4("130.89.12.34")!!, 22))
        assertEquals(listOf("130.89.13.7"), QrPayloadParser.parse(campus, phoneSubnet).getOrThrow().h)
    }

    @Test
    fun `a port beyond 65535 is a foreign QR`() {
        assertEquals(QrPayloadError.NOT_ZFC_QR, errorOf(VALID_PAYLOAD.replace("52341", "70000")))
    }
}
