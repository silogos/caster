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

    /**
     * Protocol versions this app understands (webrtc.md `proto`). Proto 2 adds
     * the vc-* virtual-camera control messages (ADR-007); an older desktop
     * negotiates 1 and the phone simply never shows its virtual-camera toggle.
     */
    const val PROTOCOL_MIN = 1
    const val PROTOCOL_MAX = 2

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

    // ---- Persistent session (pairing.md §Persistent sessions, ADR-006) ----

    /** HKDF info string — must match the desktop's TRUST_INFO byte-for-byte. */
    const val TRUST_INFO = "zfc-trust-v1"

    /** HKDF output length: a 256-bit resume key. */
    const val TRUST_KEY_BYTES = 32

    /**
     * trustKey = HKDF-SHA256(ikm = k, salt = ASCII(s), info = "zfc-trust-v1").
     * Both endpoints derive this locally after a successful QR handshake — it
     * is never transmitted, so a passive sniffer cannot compute it. Verified
     * byte-for-byte against the desktop's node:crypto hkdfSync by mirrored
     * test vectors. Re-pairing (fresh k) rotates the key.
     */
    fun deriveTrustKey(secret: ByteArray, sessionId: String): ByteArray {
        // RFC 5869 extract: PRK = HMAC-SHA256(salt, IKM)
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(sessionId.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        val prk = extract.doFinal(secret)

        // RFC 5869 expand: T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        val info = TRUST_INFO.toByteArray(Charsets.US_ASCII)
        var t = ByteArray(0)
        val okm = ArrayList<Byte>(TRUST_KEY_BYTES)
        var counter = 1
        while (okm.size < TRUST_KEY_BYTES) {
            expand.update(t)
            expand.update(info)
            expand.update(counter.toByte())
            t = expand.doFinal()
            for (b in t) {
                if (okm.size < TRUST_KEY_BYTES) okm.add(b)
            }
            counter++
        }
        return okm.toByteArray()
    }
}
