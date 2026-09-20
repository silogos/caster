package com.zerofriction.localcast.webrtc

/**
 * One sender-side video sample from a libwebrtc RTCStatsReport — extracted
 * from the raw `members` maps so this is plain-Kotlin unit-testable (the
 * report itself is native and only exists on a device).
 *
 * These numbers are the input for Phase12 (adaptive quality) — Phase5 only
 * logs them (webrtc.md: ~1 Hz, no quality automation before Phase12).
 */
data class SenderSample(
    val bytesSent: Long,
    val framesEncoded: Long,
    val framesDropped: Long,
    val framesPerSecond: Double,
    /** Round-trip of the nominated candidate pair, ms — null until known. */
    val rttMs: Long?,
    /** e.g. "OMX.qcom.video.encoder.avc" vs the software encoder name. */
    val encoderImplementation: String?,
)

object SenderStats {

    /** Returns null while the report has no video outbound-rtp entry yet. */
    fun sampleVideoSend(entries: Iterable<Map<String, Any>>): SenderSample? {
        var outbound: Map<String, Any>? = null
        var rttSeconds: Double? = null
        for (entry in entries) {
            when (entry["type"]) {
                "outbound-rtp" ->
                    if (entry["kind"] == "video") outbound = entry
                "candidate-pair" ->
                    if (entry["nominated"] == true && entry["state"] == "succeeded") {
                        rttSeconds = (entry["currentRoundTripTime"] as? Number)?.toDouble()
                    }
            }
        }
        val video = outbound ?: return null
        return SenderSample(
            bytesSent = (video["bytesSent"] as? Number)?.toLong() ?: 0L,
            framesEncoded = (video["framesEncoded"] as? Number)?.toLong() ?: 0L,
            framesDropped = (video["framesDropped"] as? Number)?.toLong() ?: 0L,
            framesPerSecond = (video["framesPerSecond"] as? Number)?.toDouble() ?: 0.0,
            rttMs = rttSeconds?.let { (it * 1000).toLong() },
            encoderImplementation = video["encoderImplementation"] as? String,
        )
    }
}
