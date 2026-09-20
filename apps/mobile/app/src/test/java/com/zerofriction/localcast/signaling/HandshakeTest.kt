package com.zerofriction.localcast.signaling

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HandshakeTest {

    @Test
    fun `matches the cross-platform HMAC-SHA256 vector`() {
        // Identical values and expected mac are asserted in the desktop vitest
        // (apps/desktop/src/main/signaling/handshake.test.ts) — both platforms
        // must agree byte-for-byte.
        val secret = Handshake.decodeSecret(VECTOR_SECRET_B64URL)

        assertArrayEquals(bytes(0, 32), secret)
        val mac = Handshake.computeMac(secret, VECTOR_SID, VECTOR_NONCE)

        assertEquals(VECTOR_MAC, mac)
    }

    @Test
    fun `a different nonce produces a different mac`() {
        val secret = Handshake.decodeSecret(VECTOR_SECRET_B64URL)
        val mac = Handshake.computeMac(secret, VECTOR_SID, VECTOR_NONCE)

        assertFalse(mac == Handshake.computeMac(secret, VECTOR_SID, "EBESExQVFhcYGRobHB0eAA"))
        assertFalse(mac == Handshake.computeMac(secret, "AnotherSessionId", VECTOR_NONCE))
    }

    private fun bytes(from: Int, until: Int): ByteArray = (from until until).map { it.toByte() }.toByteArray()

    private companion object {
        const val VECTOR_SECRET_B64URL = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val VECTOR_SID = "Jm1LIOO4mWm0lRSSl2fClw"
        const val VECTOR_NONCE = "EBESExQVFhcYGRobHB0eHw"
        const val VECTOR_MAC = "cPTgkuJCjSp2z9MZyxVs-QtHs8fCYrMN7s-k0YfyXZQ"
    }
}
