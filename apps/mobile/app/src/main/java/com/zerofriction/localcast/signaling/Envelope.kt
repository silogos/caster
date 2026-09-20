package com.zerofriction.localcast.signaling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Message envelope for the signaling WebSocket — docs/architecture/webrtc.md.
 * Every frame is JSON text: {v, type, seq, sid, payload}. The full Phase4
 * message set: pairing (hello/challenge/auth/auth-ok), media plumbing
 * (sdp-offer/sdp-answer/ice/session-info), lifecycle (ping/pong/bye/error).
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
        const val TYPE_SDP_OFFER = "sdp-offer"
        const val TYPE_SDP_ANSWER = "sdp-answer"
        const val TYPE_ICE = "ice"
        const val TYPE_SESSION_INFO = "session-info"
        const val TYPE_PING = "ping"
        const val TYPE_PONG = "pong"

        /** PeerConnection discriminators — both PCs share one channel (ADR-003). */
        const val PC_MEDIA = "media"
        const val PC_MIC = "mic"

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

    /** Recoverable (webrtc.md): the connection stays open after it. */
    const val BAD_MESSAGE = "bad-message"
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

/**
 * Typed payload builders/accessors (webrtc.md message table). Media payloads
 * treat SDP as an opaque blob and candidates as opaque JSON — signaling is
 * data plumbing, never media logic.
 */
object Payloads {

    // ---- pairing (Phase3) ----

    fun hello(userAgent: String, protoMin: Int, protoMax: Int): JsonObject = buildJsonObject {
        put("ua", userAgent)
        put("protoMin", protoMin)
        put("protoMax", protoMax)
    }

    fun auth(mac: String): JsonObject = buildJsonObject {
        put("mac", mac)
    }

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

    // ---- lifecycle (Phase4) ----

    fun bye(reason: String? = null): JsonObject = buildJsonObject {
        reason?.let { put("reason", it) }
    }

    fun byeReason(envelope: Envelope): String? =
        envelope.payload["reason"]?.jsonPrimitive?.content

    /** Heartbeat timestamps are epoch milliseconds (webrtc.md ping/pong {t}). */
    fun ping(t: Long): JsonObject = buildJsonObject {
        put("t", t)
    }

    fun pingT(envelope: Envelope): Long? =
        envelope.payload["t"]?.jsonPrimitive?.content?.toLongOrNull()

    // ---- media plumbing (Phase4; consumed by webrtc in Phase5) ----

    fun sdp(pc: String, sdp: String): JsonObject = buildJsonObject {
        put("pc", pc)
        put("sdp", sdp)
    }

    /** `candidate: null` marks end-of-gathering for `pc` (webrtc.md). */
    fun ice(pc: String, candidate: JsonElement?): JsonObject = buildJsonObject {
        put("pc", pc)
        put("candidate", candidate ?: JsonNull)
    }

    fun sessionInfo(
        profile: String,
        width: Int,
        height: Int,
        fps: Int,
        gameAudio: Boolean,
        mic: Boolean,
    ): JsonObject = buildJsonObject {
        put("profile", profile)
        put("width", width)
        put("height", height)
        put("fps", fps)
        put("gameAudio", gameAudio)
        put("mic", mic)
    }

    /** `pc` from an sdp-offer, sdp-answer or ice frame; null unless it names a known PeerConnection. */
    fun pc(envelope: Envelope): String? {
        val pc = envelope.payload["pc"]?.jsonPrimitive?.content
        return if (pc == Envelope.PC_MEDIA || pc == Envelope.PC_MIC) pc else null
    }

    fun sdpText(envelope: Envelope): String? =
        envelope.payload["sdp"]?.jsonPrimitive?.content

    /**
     * The candidate element as-is; `JsonNull` = end-of-gathering,
     * null = key missing (malformed frame).
     */
    fun iceCandidate(envelope: Envelope): JsonElement? =
        envelope.payload["candidate"]
}
