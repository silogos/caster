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

    fun parse(qrText: String): Result<QrPayload> {
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
        return Result.success(payload)
    }
}

class PairingParseException(val error: QrPayloadError) : Exception(error.name)
