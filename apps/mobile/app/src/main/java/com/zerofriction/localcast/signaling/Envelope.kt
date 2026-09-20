package com.zerofriction.localcast.signaling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Message envelope for the signaling WebSocket — docs/architecture/webrtc.md.
 * Every frame is JSON text: {v, type, seq, sid, payload}. Phase3 implements the
 * pairing-related types only; Phase4 adds sdp-offer/answer, ice, ping/pong,
 * session-info.
 */
@Serializable
data class Envelope(
    val v: Int,
    val type: String,
    /** Per-sender monotonic counter starting at 1. */
    val seq: Int,
    /** Session ID from the QR; required on every message. */
    val sid: String,
    val payload: JsonObject,
) {
    companion object {
        const val VERSION = 1

        const val TYPE_HELLO = "hello"
        const val TYPE_CHALLENGE = "challenge"
        const val TYPE_AUTH = "auth"
        const val TYPE_AUTH_OK = "auth-ok"
        const val TYPE_ERROR = "error"
        const val TYPE_BYE = "bye"

        /** Max envelope size per webrtc.md. */
        const val MAX_BYTES = 256 * 1024
    }
}

/** Inbound envelope parse failures — mirroring the desktop's error codes. */
sealed interface EnvelopeParseResult {
    data class Valid(val envelope: Envelope) : EnvelopeParseResult

    /** Terminal: envelope versions don't match. */
    data object BadVersion : EnvelopeParseResult

    /** Recoverable: malformed/unknown frame. */
    data object BadMessage : EnvelopeParseResult
}

/** Error codes carried in `error` payloads (pairing.md / webrtc.md). */
object ErrorCodes {
    const val UNKNOWN_SESSION = "unknown-session"
    const val EXPIRED = "expired"
    const val BAD_AUTH = "bad-auth"
    const val BUSY = "busy"
    const val BAD_VERSION = "bad-version"
}

object EnvelopeCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(type: String, seq: Int, sid: String, payload: JsonObject): String =
        json.encodeToString(
            Envelope.serializer(),
            Envelope(v = Envelope.VERSION, type = type, seq = seq, sid = sid, payload = payload),
        )

    fun parse(raw: String): EnvelopeParseResult {
        if (raw.length > Envelope.MAX_BYTES) return EnvelopeParseResult.BadMessage
        val envelope = try {
            json.decodeFromString<Envelope>(raw)
        } catch (_: Exception) {
            return EnvelopeParseResult.BadMessage
        }
        if (envelope.v != Envelope.VERSION) return EnvelopeParseResult.BadVersion
        if (envelope.seq < 1) return EnvelopeParseResult.BadMessage
        if (envelope.sid.isEmpty()) return EnvelopeParseResult.BadMessage
        return EnvelopeParseResult.Valid(envelope)
    }
}

/** Typed payload field accessors for the Phase3 message set. */
object Payloads {

    fun hello(userAgent: String, protoMin: Int, protoMax: Int): JsonObject = buildJsonObject {
        put("ua", userAgent)
        put("protoMin", protoMin)
        put("protoMax", protoMax)
    }

    fun auth(mac: String): JsonObject = buildJsonObject {
        put("mac", mac)
    }

    fun bye(): JsonObject = buildJsonObject {}

    fun challengeNonce(envelope: Envelope): String? =
        envelope.payload["n"]?.jsonPrimitive?.content

    fun helloUa(envelope: Envelope): String? =
        envelope.payload["ua"]?.jsonPrimitive?.content

    fun helloProtoMin(envelope: Envelope): Int? =
        envelope.payload["protoMin"]?.jsonPrimitive?.content?.toIntOrNull()

    fun authOkName(envelope: Envelope): String? =
        envelope.payload["name"]?.jsonPrimitive?.content

    fun authOkProto(envelope: Envelope): Int? =
        envelope.payload["proto"]?.jsonPrimitive?.content?.toIntOrNull()

    fun errorCode(envelope: Envelope): String? =
        envelope.payload["code"]?.jsonPrimitive?.content
}
