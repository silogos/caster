package com.zerofriction.localcast.signaling

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Handshake crypto per docs/architecture/pairing.md and ADR-002:
 * mac = base64url(HMAC-SHA256(key = k, msg = ASCII(s) || ASCII(n))).
 * The secret never crosses the wire; the nonce is bound to the session id so a
 * captured mac cannot be replayed against another session.
 */
object Handshake {

    /** Protocol versions this app understands (webrtc.md `proto`). */
    const val PROTOCOL_MIN = 1
    const val PROTOCOL_MAX = 1

    fun computeMac(secret: ByteArray, sessionId: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(sessionId.toByteArray(Charsets.US_ASCII))
        mac.update(nonce.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal())
    }

    /** Decode the base64url pairing secret from the QR payload. */
    fun decodeSecret(secretBase64Url: String): ByteArray =
        Base64.getUrlDecoder().decode(secretBase64Url)
}
