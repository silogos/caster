package com.zerofriction.localcast.webrtc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.IceCandidate

/**
 * Candidate serialization for the `ice` envelope (webrtc.md): the wire value is
 * the platform candidate object, and signaling relays it verbatim. Both ends
 * therefore agree on the RTCIceCandidateInit shape — `{candidate, sdpMid,
 * sdpMLineIndex}` — which is exactly what Chromium's `candidate.toJSON()` on
 * the desktop produces, and what it feeds to `new RTCIceCandidate(...)` when
 * the same object comes back.
 *
 * `IceCandidate.sdp` holds the SDP line body ("candidate:…", no "a=" prefix) —
 * matching the `candidate` field of RTCIceCandidateInit on the wire.
 */
object IceCandidateJson {

    fun encode(candidate: IceCandidate): JsonObject = buildJsonObject {
        put("candidate", candidate.sdp)
        put("sdpMid", candidate.sdpMid)
        put("sdpMLineIndex", candidate.sdpMLineIndex)
    }

    /**
     * Decode a candidate the desktop sent. Returns null for anything that is
     * not a well-formed candidate object (logged by the caller; the cast must
     * not crash on a bad peer).
     */
    fun decode(element: JsonElement): IceCandidate? {
        if (element !is JsonObject) return null
        val candidate = element.stringField("candidate") ?: return null
        val sdpMid = element.stringField("sdpMid") ?: return null
        val sdpMLineIndex = element.stringField("sdpMLineIndex")?.toIntOrNull() ?: return null
        return IceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    /** Primitive string value; null for absent keys, non-strings, and JSON nulls. */
    private fun JsonObject.stringField(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content
    }
}
