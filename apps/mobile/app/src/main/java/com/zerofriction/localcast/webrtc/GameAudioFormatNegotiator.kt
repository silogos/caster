package com.zerofriction.localcast.webrtc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ADR-013's in-band format handshake, mobile side — a pure state machine
 * (no Android/WebRTC types; JVM-testable, injected clock). The channel opens
 * as ADR-012's raw PCM (byte-identical to what every shipped desktop
 * understands) and announces the AAC capability periodically while open:
 * the channel is unordered and unacked, so one announce can be lost, and an
 * old desktop ignores text messages entirely (main.ts drops them today).
 *
 * The desktop's ack switches this cast to AAC; silence past the window means
 * "old desktop" and PCM stands. A late ack (after the window) is still
 * honored — a desktop that acked understands the format regardless of when
 * it answered. A mid-cast encoder failure reverts to PCM and re-announces
 * `pcm16` so a capable desktop follows immediately instead of waiting out
 * its quiet-timer fallback.
 */
class GameAudioFormatNegotiator(
    private val nowMs: () -> Long,
    /** Whether this cast's encoder can actually produce AAC right now. */
    @Volatile var selfSupportsAac: Boolean = true,
) {
    enum class Mode { PCM, AAC }

    @Volatile
    var mode: Mode = Mode.PCM
        private set

    private var announcing = false
    private var hasAnnounced = false
    private var lastAnnounceMs = 0L
    private var windowEndMs = 0L

    /** The channel reached OPEN — start (or restart) the announce window. */
    fun onChannelOpen() {
        announcing = true
        hasAnnounced = false
        lastAnnounceMs = 0L
        windowEndMs = nowMs() + ANNOUNCE_WINDOW_MS
    }

    /**
     * Polled from the sender loop. Returns the announce text to send now, or
     * null (not yet due, or the window closed — an old desktop keeps PCM).
     */
    fun pollAnnounce(): String? {
        if (!announcing) return null
        val now = nowMs()
        if (hasAnnounced) {
            if (now >= windowEndMs) {
                announcing = false
                return null
            }
            if (now - lastAnnounceMs < ANNOUNCE_RETRY_MS) return null
        }
        hasAnnounced = true
        lastAnnounceMs = now
        return announcePayload()
    }

    /**
     * The desktop's ack. AAC wins only if the encoder is actually healthy —
     * an encoder that died before the ack arrived keeps the cast on PCM.
     */
    fun onAck(format: String) {
        announcing = false
        mode = if (format == FORMAT_AAC && selfSupportsAac) Mode.AAC else Mode.PCM
    }

    /** The encoder died mid-cast — revert to PCM and tell the desktop why. */
    fun onEncoderFailed() {
        selfSupportsAac = false
        mode = Mode.PCM
        onChannelOpen()
    }

    /** True when ADTS frames — not raw PCM — belong on the wire right now. */
    val sendsAac: Boolean get() = mode == Mode.AAC && selfSupportsAac

    private fun announcePayload(): String =
        """{"v":$PROTOCOL_VERSION,"type":"announce","format":"${if (selfSupportsAac) FORMAT_AAC else FORMAT_PCM}"}"""

    companion object {
        /** webrtc.md channel sub-protocol version (in-band, not the Envelope's). */
        const val PROTOCOL_VERSION = 1

        /** AAC-LC 192 kbps CBR stereo 48 kHz — the announced/acked format string. */
        const val FORMAT_AAC = "adts-aac-192"

        /** ADR-012's raw PCM — the revert/fallback format string. */
        const val FORMAT_PCM = "pcm16"

        /** Retry cadence for a lost announce on the unordered channel. */
        const val ANNOUNCE_RETRY_MS = 500L

        /**
         * How long a desktop has to ack before "old desktop" is assumed and
         * PCM stands for the cast. Long enough to cover SCTP open + an ack
         * round trip on a healthy LAN, short enough not to delay the AAC
         * switch noticeably.
         */
        const val ANNOUNCE_WINDOW_MS = 2_000L
    }
}

/** The channel sub-protocol's messages (webrtc.md) — announce mobile→desktop, ack back. */
sealed interface HandshakeMessage {
    data class Ack(val format: String) : HandshakeMessage

    companion object {
        /**
         * Minimal tolerant parse: anything that is not a well-formed ack
         * returns null (the desktop may send other text someday; the log
         * keeps the raw length only — control messages never carry media).
         */
        fun parse(text: String): HandshakeMessage? {
            val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
            val obj = json as? JsonObject ?: return null
            if (obj["v"]?.jsonPrimitive?.intOrNull != GameAudioFormatNegotiator.PROTOCOL_VERSION) return null
            return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "ack" -> Ack(obj["format"]?.jsonPrimitive?.contentOrNull ?: return null)
                else -> null
            }
        }
    }
}
