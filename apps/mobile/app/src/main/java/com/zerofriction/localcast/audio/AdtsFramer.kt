package com.zerofriction.localcast.audio

/**
 * ADR-013: the ADTS framing TikTok's production cast ships — the codec +
 * framing import from the teardown ([audio-importable.md]; the transport
 * stays WebRTC per the ADR). Pure Kotlin so the byte layout and the slicing
 * are JVM-unit-testable; the MediaCodec driver lives in [GameAacEncoder].
 *
 * ADTS makes every frame self-describing (syncword, sampling-frequency
 * index, channel config, frame length): the receiver needs no out-of-band
 * csd-0 and gains free re-sync points after a lost frame — exactly why
 * TikTok frames this way.
 */
object AdtsHeader {

    /** ADTS header length — protection_absent=1, no CRC (TikTok's config). */
    const val LENGTH_BYTES = 7

    /** 13-bit frame-length field caps one ADTS packet. */
    const val MAX_TOTAL_BYTES = 0x1FFF

    /**
     * Sampling-frequency index: 48 kHz → 3 (the teardown's
     * `AudioProfile.getSFI`, same table and same fallback).
     */
    fun samplingFrequencyIndex(sampleRateHz: Int): Int = when (sampleRateHz) {
        8000 -> 11
        16000 -> 8
        32000 -> 5
        44100 -> 4
        48000 -> 3
        else -> 4
    }

    /**
     * Wraps one raw AAC frame into a complete ADTS packet. Byte layout is
     * TikTok's `addADTStoPacket` ported verbatim: [0xFF,0xF9] (MPEG-4, layer
     * 0, no CRC), LC profile, the rate's index, channel config, the
     * 13-bit total length (header + payload), 0x7FF buffer fullness
     * (VBR-style marker the reference uses) and one raw-data-block.
     */
    fun wrap(rawFrame: ByteArray, sampleRateHz: Int, channels: Int): ByteArray {
        require(rawFrame.isNotEmpty()) { "raw AAC frame is empty" }
        require(channels in 1..7) { "channel config out of ADTS range: $channels" }
        val total = rawFrame.size + LENGTH_BYTES
        require(total <= MAX_TOTAL_BYTES) { "ADTS frame too large: $total" }
        val sfi = samplingFrequencyIndex(sampleRateHz)
        val header = byteArrayOf(
            0xFF.toByte(), // syncword hi
            0xF9.toByte(), // syncword lo | MPEG-4 | layer 0 | protection_absent
            // profile LC (01) | private 0 | sf index hi 2 | channel config hi
            ((0x01 shl 6) or (sfi shl 2) or (channels shr 2)).toByte(),
            // channel config low bits | frame length bits 12-11
            (((channels and 0x3) shl 6) or (total shr 11)).toByte(),
            // frame length bits 10-3 — a full byte (the reference's `(i & 2047) >> 3`)
            ((total shr 3) and 0xFF).toByte(),
            // frame length bits 2-0 | buffer fullness hi (0x1F → 0x7FF with byte 6)
            (((total and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(), // buffer fullness lo | one raw-data-block
        )
        return header + rawFrame
    }
}

/**
 * Slices the ADM's arbitrary capture chunks (ADR-012: ~10 ms stereo PCM16)
 * into AAC-LC's fixed 1024-sample access units — the codec consumes whole
 * frames only. Runs on the encoder's thread ([GameAacEncoder]), so the
 * unit callback may copy synchronously: the handed array is the internal
 * buffer and is reused after the callback returns.
 */
class PcmUnitAccumulator(
    /** Interleaved frames per emitted unit — AAC-LC's 1024. */
    private val unitFrames: Int,
    /** Bytes per interleaved frame — 16-bit stereo = 4. */
    private val bytesPerFrame: Int,
) {
    private val buffer = ByteArray(unitFrames * bytesPerFrame)
    private var filled = 0

    /** Bytes of one complete unit — the encoder's input size. */
    val bytesPerUnit: Int get() = buffer.size

    /** Emits every complete unit [chunk] completes; a partial tail stays buffered. */
    fun push(chunk: ByteArray, onUnit: (ByteArray) -> Unit) {
        var pos = 0
        while (pos < chunk.size) {
            val n = minOf(chunk.size - pos, buffer.size - filled)
            System.arraycopy(chunk, pos, buffer, filled, n)
            filled += n
            pos += n
            if (filled == buffer.size) {
                onUnit(buffer)
                filled = 0
            }
        }
    }

    /** Discards the partial tail (a sub-unit remainder is inaudible). */
    fun reset() {
        filled = 0
    }
}
