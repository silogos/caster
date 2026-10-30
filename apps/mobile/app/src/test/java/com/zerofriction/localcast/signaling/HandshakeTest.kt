package com.zerofriction.localcast.signaling

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

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
    fun `matches the cross-platform HKDF trust-key vectors`() {
        // Mirrored in the desktop vitest (handshake.test.ts) — both HKDF
        // implementations must agree byte-for-byte or a saved session could
        // never resume (ADR-006).
        val key1 = Handshake.deriveTrustKey(
            Base64.getUrlDecoder().decode(TRUST_VECTOR_SECRET_B64URL),
            TRUST_VECTOR_SID,
        )
        assertEquals(TRUST_VECTOR_KEY_B64URL, Base64.getUrlEncoder().withoutPadding().encodeToString(key1))
        assertEquals(32, key1.size)

        val key2 = Handshake.deriveTrustKey(
            TRUST_VECTOR2_SECRET.hexToBytes(),
            TRUST_VECTOR2_SID,
        )
        assertEquals(TRUST_VECTOR2_KEY_B64URL, Base64.getUrlEncoder().withoutPadding().encodeToString(key2))
    }

    @Test
    fun `a different nonce produces a different mac`() {
        val secret = Handshake.decodeSecret(VECTOR_SECRET_B64URL)
        val mac = Handshake.computeMac(secret, VECTOR_SID, VECTOR_NONCE)

        assertFalse(mac == Handshake.computeMac(secret, VECTOR_SID, "EBESExQVFhcYGRobHB0eAA"))
        assertFalse(mac == Handshake.computeMac(secret, "AnotherSessionId", VECTOR_NONCE))
    }

    private fun bytes(from: Int, until: Int): ByteArray = (from until until).map { it.toByte() }.toByteArray()

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val VECTOR_SECRET_B64URL = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val VECTOR_SID = "Jm1LIOO4mWm0lRSSl2fClw"
        const val VECTOR_NONCE = "EBESExQVFhcYGRobHB0eHw"
        const val VECTOR_MAC = "cPTgkuJCjSp2z9MZyxVs-QtHs8fCYrMN7s-k0YfyXZQ"

        // HKDF trust-key vectors (ADR-006) — mirrored from the desktop tests.
        const val TRUST_VECTOR_SECRET_B64URL = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"
        const val TRUST_VECTOR_SID = "sess"
        const val TRUST_VECTOR_KEY_B64URL = "Hng10A_e7L4UF8QKc5Tf_Lv-Ut2wijkNvFFuqWaKfhU"
        const val TRUST_VECTOR2_SECRET = "9f2c7be14ad83a05c6f1b29e4d7a83c0b5e1962d483fc7abe1d05f92c38b7a41"
        const val TRUST_VECTOR2_SID = "c2Vzc2lvbi1pZA"
        const val TRUST_VECTOR2_KEY_B64URL = "GsBUjq7GFfivYpQyGAa4pYNgbBkFiFDfjK8KpCFRoF4"
    }
}
