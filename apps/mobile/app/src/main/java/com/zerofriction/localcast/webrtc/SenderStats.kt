package com.zerofriction.localcast.webrtc

/**
 * One sender-side video sample from a libwebrtc RTCStatsReport — extracted
 * from the raw `members` maps so this is plain-Kotlin unit-testable (the
 * report itself is native and only exists on a device).
 *
 * These numbers feed Phase12's adaptive quality (the `adaptive` module) —
 * and are logged at ~1 Hz (webrtc.md).
 */
data class SenderSample(
    val bytesSent: Long,
    val framesEncoded: Long,
    val framesDropped: Long,
    val framesPerSecond: Double,
    /** Sent frame size — the proof a Phase10 settings change reached the encoder. */
    val frameWidth: Int,
    val frameHeight: Int,
    /** Round-trip of the nominated candidate pair, ms — null until known. */
    val rttMs: Long?,
    /** e.g. "OMX.qcom.video.encoder.avc" vs the software encoder name. */
    val encoderImplementation: String?,
    /** Remote receiver's reported loss fraction (0..1) — the link-struggle signal for Phase12. */
    val fractionLost: Double?,
)

/**
 * One sender-side audio sample from a libwebrtc RTCStatsReport (the mic pc,
 * Phase8) — same extraction idea as [SenderSample]: raw `members` maps in,
 * plain values out, so it is unit-testable on the JVM.
 */
data class AudioSenderSample(
    val bytesSent: Long,
    /** Round-trip of the nominated candidate pair, ms — null until known. */
    val rttMs: Long?,
)

object SenderStats {

    /** Returns null while the report has no video outbound-rtp entry yet. */
    fun sampleVideoSend(entries: Iterable<Map<String, Any>>): SenderSample? {
        var outbound: Map<String, Any>? = null
        var rttSeconds: Double? = null
        var fractionLost: Double? = null
        for (entry in entries) {
            when (entry["type"]) {
                "outbound-rtp" ->
                    if (entry["kind"] == "video") outbound = entry
                // The sending side's view of the desktop's receiver — only
                // the receiver knows what actually arrived (and what didn't).
                "remote-inbound-rtp" ->
                    if (entry["kind"] == "video") {
                        fractionLost = (entry["fractionLost"] as? Number)?.toDouble()
                    }
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
            frameWidth = (video["frameWidth"] as? Number)?.toInt() ?: 0,
            frameHeight = (video["frameHeight"] as? Number)?.toInt() ?:0,
            rttMs = rttSeconds?.let { (it * 1000).toLong() },
            encoderImplementation = video["encoderImplementation"] as? String,
            fractionLost = fractionLost,
        )
    }

    /** Returns null while the report has no audio outbound-rtp entry yet. */
    fun sampleAudioSend(entries: Iterable<Map<String, Any>>): AudioSenderSample? {
        var outbound: Map<String, Any>? = null
        var rttSeconds: Double? = null
        for (entry in entries) {
            when (entry["type"]) {
                "outbound-rtp" ->
                    if (entry["kind"] == "audio") outbound = entry
                "candidate-pair" ->
                    if (entry["nominated"] == true && entry["state"] == "succeeded") {
                        rttSeconds = (entry["currentRoundTripTime"] as? Number)?.toDouble()
                    }
            }
        }
        val audio = outbound ?: return null
        return AudioSenderSample(
            bytesSent = (audio["bytesSent"] as? Number)?.toLong() ?: 0L,
            rttMs = rttSeconds?.let { (it * 1000).toLong() },
        )
    }
}
