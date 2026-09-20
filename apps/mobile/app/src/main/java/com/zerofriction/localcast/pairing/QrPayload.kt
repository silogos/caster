package com.zerofriction.localcast.pairing

import kotlinx.serialization.Serializable

/**
 * QR pairing payload v1 — docs/architecture/pairing.md. Bootstrap data only:
 * rendered by the desktop, scanned here. Field names are deliberately short to
 * keep QR density low. The QR encodes compact JSON of this shape.
 */
@Serializable
data class QrPayload(
    /** Payload schema version. */
    val v: Int,
    /** Literal "zfc" — guards against scanning unrelated QR codes. */
    val t: String,
    /** All candidate IPv4 addresses of the desktop; tried in order. */
    val h: List<String>,
    /** WebSocket signaling port. */
    val p: Int,
    /** Session ID — 16 random bytes, base64url. */
    val s: String,
    /** Pairing secret — 32 random bytes, base64url. Never transmitted; used only as HMAC key. */
    val k: String,
    /** Expiry — Unix seconds. The desktop clock is authoritative. */
    val e: Long,
) {
    companion object {
        const val PAYLOAD_VERSION = 1
        const val PAYLOAD_TYPE = "zfc"
    }
}

/** Parse/validation failures mapped to the pairing.md failure-mode table. */
enum class QrPayloadError {
    /** Not a Zero-Friction Cast QR code (wrong `t`, unparseable JSON, bad fields). */
    NOT_ZFC_QR,

    /** Unknown payload version `v` — apps are incompatible. */
    UNSUPPORTED_VERSION,
}
