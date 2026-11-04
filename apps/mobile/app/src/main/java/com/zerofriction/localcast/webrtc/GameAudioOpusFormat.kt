package com.zerofriction.localcast.webrtc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Channel payload v1 (ADR-013, [webrtc.md](docs/architecture/webrtc.md)): the
 * `game-audio` DataChannel's format announce and the OpusHead details behind
 * it. Pure and JSON-exact — the encoder builds the announce from this and the
 * desktop parses it back, so both ends must agree byte-for-byte.
 */
object GameAudioOpusFormat {

    /** The channel payload version — independent of the signaling envelope's version. */
    const val CHANNEL_PAYLOAD_VERSION = 1

    const val CODEC = "opus"
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNELS = 2

    /** libopus's encoder delay at 48 kHz — the preskip when MediaCodec emits no csd-0. */
    const val DEFAULT_PRESKIP_SAMPLES = 312

    private val OPUS_HEAD_MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)

    /** magic 8 + version 1 + channels 1 + preskip 2 + rate 4 + gain 2 + mapping 1. */
    const val OPUS_HEAD_SIZE = 19

    /** The announce JSON — sent once, immediately before the first Opus packet. */
    fun announce(preskipSamples: Int): String = buildJsonObject {
        put("v", CHANNEL_PAYLOAD_VERSION)
        put("codec", CODEC)
        put("sampleRate", SAMPLE_RATE_HZ)
        put("channels", CHANNELS)
        put("preskip", preskipSamples)
    }.toString()

    /**
     * Pre-skip from a csd-0 OpusHead (RFC 7845 §3.1: preskip is 16-bit
     * **little**-endian at offset 10 — verified against an ffmpeg-produced
     * file during the #104 live debug; sample rate 32-bit little-endian at
     * 12). Null when the bytes are not a well-formed stereo 48 kHz OpusHead —
     * the caller falls back to [DEFAULT_PRESKIP_SAMPLES].
     */
    fun preskipFromOpusHead(csd: ByteArray): Int? {
        if (csd.size < OPUS_HEAD_SIZE) return null
        for (i in OPUS_HEAD_MAGIC.indices) {
            if (csd[i] != OPUS_HEAD_MAGIC[i]) return null
        }
        if (csd[8].toInt() != 1) return null // version
        if (csd[9].toInt() != CHANNELS) return null
        val preskip = (csd[10].toInt() and 0xFF) or ((csd[11].toInt() and 0xFF) shl 8)
        val rate = (csd[12].toInt() and 0xFF) or
            ((csd[13].toInt() and 0xFF) shl 8) or
            ((csd[14].toInt() and 0xFF) shl 16) or
            ((csd[15].toInt() and 0xFF) shl 24)
        if (rate != SAMPLE_RATE_HZ) return null
        return preskip
    }

    /**
     * The desktop's mirror: parse the announce strictly — null unless it is a
     * v1 opus/48000/2 announce with an in-range preskip. Used by tests to pin
     * the wire contract; the desktop implements the same validation.
     */
    fun parseAnnounce(json: String): Int? {
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            if (obj["v"]?.jsonPrimitive?.int != CHANNEL_PAYLOAD_VERSION) return null
            if (obj["codec"]?.jsonPrimitive?.content != CODEC) return null
            if (obj["sampleRate"]?.jsonPrimitive?.int != SAMPLE_RATE_HZ) return null
            if (obj["channels"]?.jsonPrimitive?.int != CHANNELS) return null
            val preskip = obj["preskip"]?.jsonPrimitive?.int ?: return null
            if (preskip < 0 || preskip > SAMPLE_RATE_HZ) return null
            preskip
        } catch (_: Throwable) {
            null
        }
    }
}
