package com.zerofriction.localcast.pairing

import kotlinx.serialization.json.Json

/**
 * Parses the raw text decoded from the desktop's QR into a [QrPayload].
 * Version/tag checks follow docs/architecture/pairing.md: unknown `t` →
 * NOT_ZFC_QR, unknown `v` → UNSUPPORTED_VERSION (actionable update message).
 * Expiry is NOT checked here — the desktop enforces it authoritatively and
 * answers `expired`; the mobile's ±120 s tolerance is UX-only (pairing.md).
 */
object QrPayloadParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [localSubnets]: the phone's current subnets ([LocalSubnets]) — hosts
     * outside them and outside the private ranges are dropped ([LanHosts]).
     */
    fun parse(qrText: String, localSubnets: List<LanHosts.Subnet> = emptyList()): Result<QrPayload> {
        val payload = try {
            json.decodeFromString<QrPayload>(qrText)
        } catch (_: Exception) {
            return Result.failure(PairingParseException(QrPayloadError.NOT_ZFC_QR))
        }

        if (payload.t != QrPayload.PAYLOAD_TYPE) {
            return Result.failure(PairingParseException(QrPayloadError.NOT_ZFC_QR))
        }
        if (payload.v != QrPayload.PAYLOAD_VERSION) {
            return Result.failure(PairingParseException(QrPayloadError.UNSUPPORTED_VERSION))
        }
        // Structural sanity: without hosts/port/session/secret nothing can connect.
        if (payload.h.isEmpty() || payload.h.any { it.isBlank() } ||
            payload.p <= 0 || payload.s.isEmpty() || payload.k.isEmpty()
        ) {
            return Result.failure(PairingParseException(QrPayloadError.NOT_ZFC_QR))
        }
        // pairing.md: `s` is 16 random bytes and `k` 32, both base64url. A
        // malformed value used to reach Handshake.decodeSecret unguarded and
        // crash the app (review 2026-11-09) — reject it as a foreign QR.
        if (decodedSize(payload.s) != SESSION_ID_BYTES || decodedSize(payload.k) != SECRET_BYTES) {
            return Result.failure(PairingParseException(QrPayloadError.NOT_ZFC_QR))
        }
        if (payload.p > MAX_PORT) {
            return Result.failure(PairingParseException(QrPayloadError.NOT_ZFC_QR))
        }
        // LAN only (AGENTS.md): a QR must never point the phone at an internet
        // host — the screen cast would follow it (review 2026-11-09, group C).
        val lanHosts = LanHosts.filter(payload.h, localSubnets)
        if (lanHosts.isEmpty()) {
            return Result.failure(PairingParseException(QrPayloadError.NO_LAN_HOST))
        }
        return Result.success(payload.copy(h = lanHosts))
    }

    /** Decoded byte count of a base64url value, or -1 when it is not base64url. */
    private fun decodedSize(base64Url: String): Int = try {
        java.util.Base64.getUrlDecoder().decode(base64Url).size
    } catch (_: IllegalArgumentException) {
        -1
    }

    /** pairing.md QR payload v1: `s` = 16 random bytes, `k` = 32 random bytes. */
    const val SESSION_ID_BYTES = 16
    const val SECRET_BYTES = 32

    /** The highest TCP port. */
    private const val MAX_PORT = 65_535
}

class PairingParseException(val error: QrPayloadError) : Exception(error.name)
