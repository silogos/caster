package com.zerofriction.localcast.signaling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray

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
        const val TYPE_VC_QUERY = "vc-query"
        const val TYPE_VC_START = "vc-start"
        const val TYPE_VC_STOP = "vc-stop"
        const val TYPE_VC_STATUS = "vc-status"
        const val TYPE_AUDIO_ROUTING_QUERY = "audio-routing-query"
        const val TYPE_AUDIO_ROUTING_SET = "audio-routing-set"
        const val TYPE_AUDIO_ROUTING_STATUS = "audio-routing-status"
        const val TYPE_PING = "ping"
        const val TYPE_PONG = "pong"

        /** Audio output routing modes (webrtc.md proto 2) — mirrors the desktop's receiver setting. */
        const val AUDIO_ROUTING_SPEAKERS = "speakers"
        const val AUDIO_ROUTING_CABLE = "cable"
        const val AUDIO_ROUTING_BOTH = "both"

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

    /** The desktop no longer trusts this phone (pairing.md §Persistent sessions) — re-scan. */
    const val UNKNOWN_DEVICE = "unknown-device"

    /** Recoverable (webrtc.md): the connection stays open after it. */
    const val BAD_MESSAGE = "bad-message"
}

/** Reserved `bye` reasons (pairing.md §Persistent sessions, ADR-006). */
object ByeReasons {
    /** End the cast/session AND revoke the persistent trust — what Disconnect sends. */
    const val FORGET = "forget"

    /** End the session but keep the trust (e.g. a stopping cast) — one-tap reconnect stays. */
    const val USER_ENDED = "user-ended"
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

    /**
     * `deviceId` is hello's optional registry field (pairing.md, ADR-005) —
     * never an auth secret; the desktop must accept handshakes without it,
     * so null simply omits the key.
     */
    fun hello(userAgent: String, deviceId: String?, protoMin: Int, protoMax: Int, resume: Boolean = false): JsonObject = buildJsonObject {
        put("ua", userAgent)
        if (deviceId !== null) put("deviceId", deviceId)
        if (resume) put("resume", true)
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

    fun helloDeviceId(envelope: Envelope): String? =
        envelope.payload["deviceId"]?.jsonPrimitive?.content

    fun helloProtoMin(envelope: Envelope): Int? =
        envelope.payload["protoMin"]?.jsonPrimitive?.content?.toIntOrNull()

    fun authOkName(envelope: Envelope): String? =
        envelope.payload["name"]?.jsonPrimitive?.content

    fun authOkProto(envelope: Envelope): Int? =
        envelope.payload["proto"]?.jsonPrimitive?.content?.toIntOrNull()

    /** The desktop's persistent UUID (pairing.md §Persistent sessions) — null on older desktops. */
    fun authOkDesktopId(envelope: Envelope): String? =
        envelope.payload["desktopId"]?.jsonPrimitive?.content

    /** The desktop's current LAN candidates from auth-ok — empty when absent. */
    fun authOkHosts(envelope: Envelope): List<String> =
        (envelope.payload["hosts"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()

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

    // ---- virtual camera control (proto 2, ADR-007) ----

    /** The three m→d vc messages carry empty payloads by spec. */
    fun vcCommand(): JsonObject = JsonObject(emptyMap())

    /**
     * The desktop's virtual-camera state (d→m) — the toggle renders exactly
     * this. Null when any field is missing or not a boolean (malformed frame).
     */
    fun vcStatus(envelope: Envelope): VcStatus? {
        val payload = envelope.payload
        val available = (payload["available"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val needsRegistration = (payload["needsRegistration"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val active = (payload["active"] as? JsonPrimitive)?.booleanOrNull ?: return null
        return VcStatus(available, needsRegistration, active)
    }

    // ---- audio output routing control (proto 2) ----

    /** `audio-routing-set` (m→d) — the phone's pick of the desktop's output route. */
    fun audioRoutingSet(mode: String): JsonObject = buildJsonObject { put("mode", mode) }

    /**
     * `audio-routing-status` (d→m) — the desktop's effective state. Null when
     * the mode is unknown or `cableAvailable` missing/not boolean.
     */
    fun audioRoutingStatus(envelope: Envelope): AudioRoutingStatus? {
        val payload = envelope.payload
        val mode = (payload["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (mode != Envelope.AUDIO_ROUTING_SPEAKERS && mode != Envelope.AUDIO_ROUTING_CABLE && mode != Envelope.AUDIO_ROUTING_BOTH) return null
        val cableAvailable = (payload["cableAvailable"] as? JsonPrimitive)?.booleanOrNull ?: return null
        return AudioRoutingStatus(mode, cableAvailable)
    }
}

/** `vc-status` payload (webrtc.md proto 2) — display-only state from the desktop. */
data class VcStatus(
    val available: Boolean,
    val needsRegistration: Boolean,
    val active: Boolean,
)

/** `audio-routing-status` payload (webrtc.md proto 2) — the desktop's effective output route. */
data class AudioRoutingStatus(
    val mode: String,
    val cableAvailable: Boolean,
)
