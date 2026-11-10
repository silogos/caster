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
    /**
     * Cumulative seconds the encoder spent encoding (RTCStats
     * `totalEncodeTime`) — Phase16's per-frame encode cost comes from its
     * delta over frames. Null while the prebuilt does not report it.
     */
    val totalEncodeTimeSeconds: Double? = null,
    /** Cumulative frames handed to the packetizer; null while not reported (Phase16). */
    val framesSent: Long? = null,
    /** Cumulative retransmitted packets; null while not reported (Phase16). */
    val retransmittedPacketsSent: Long? = null,
    /**
     * Cumulative seconds packets waited in libwebrtc's pacer before going on
     * the wire (`totalPacketSendDelay`) and the packets it counts over — a
     * standing pacer queue shows as a steady per-packet delay. Null while
     * not reported.
     */
    val totalPacketSendDelaySeconds: Double? = null,
    val packetsSent: Long? = null,
    /** What libwebrtc currently asks the encoder for (`targetBitrate`), bps; null while not reported. */
    val targetBitrateBps: Long? = null,
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
            totalEncodeTimeSeconds = (video["totalEncodeTime"] as? Number)?.toDouble(),
            framesSent = (video["framesSent"] as? Number)?.toLong(),
            retransmittedPacketsSent = (video["retransmittedPacketsSent"] as? Number)?.toLong(),
            totalPacketSendDelaySeconds = (video["totalPacketSendDelay"] as? Number)?.toDouble(),
            packetsSent = (video["packetsSent"] as? Number)?.toLong(),
            targetBitrateBps = (video["targetBitrate"] as? Number)?.toLong(),
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

/**
 * Send bitrate between two cumulative `bytesSent` samples, bps. 0 for the
 * first sample, and 0 when the counter went *backwards*: a rebuilt pc (the
 * re-auth path) restarts `bytesSent` at 0, and the plain delta reported a
 * large negative bitrate into the stats log and the `onVideoStats` consumer (mobile review, group B).
 */
/**
 * Milliseconds per unit from two cumulative samples (`total` seconds over a
 * `count`): null until both pairs exist, on a counter reset, or with no new
 * units.
 */
fun perDelta(total: Double?, previousTotal: Double?, count: Long?, previousCount: Long?): Double? {
    if (total == null || previousTotal == null || count == null || previousCount == null) return null
    val units = count - previousCount
    val seconds = total - previousTotal
    return if (units > 0 && seconds >= 0) seconds * MS_PER_SECOND / units else null
}

fun sendBitrateBps(previousBytes: Long, bytes: Long, previousAtMs: Long, nowMs: Long): Long =
    if (previousAtMs <= 0 || bytes < previousBytes) {
        0L
    } else {
        (bytes - previousBytes) * MS_PER_SECOND * BITS_PER_BYTE / (nowMs - previousAtMs).coerceAtLeast(1)
    }

private const val MS_PER_SECOND = 1_000L
private const val BITS_PER_BYTE = 8L
