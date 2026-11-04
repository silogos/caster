package com.zerofriction.localcast.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-013's wire framing: the ADTS header must be byte-for-byte what the
 * teardown's `addADTStoPacket` produces (48 kHz stereo LC) so the desktop's
 * parser — and every other ADTS consumer — reads it natively, and the
 * accumulator must slice the ADM's ~10 ms chunks into exact 1024-sample
 * access units across arbitrary chunk boundaries.
 */
class AdtsFramerTest {

    private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 0xFF

    /** The 13-bit ADTS frame length split over three header bytes. */
    private fun adtsLength(bytes: ByteArray): Int =
        ((u8(bytes, 3) and 0x03) shl 11) or (u8(bytes, 4) shl 3) or (u8(bytes, 5) shr 5)

    @Test
    fun `48k stereo header matches the reference byte layout`() {
        val raw = ByteArray(512) { (it % 251).toByte() }
        val packet = AdtsHeader.wrap(raw, sampleRateHz = 48_000, channels = 2)

        assertEquals(512 + 7, packet.size)
        assertEquals(0xFF, u8(packet, 0)) // syncword hi
        assertEquals(0xF9, u8(packet, 1)) // syncword lo | MPEG-4 | layer 0 | no CRC
        // profile LC (01) | private 0 | sf index 3 (48k) | channel cfg hi (0)
        assertEquals(0b01001100, u8(packet, 2))
        // channel cfg lo (10) | length hi
        assertEquals(((2 and 0x3) shl 6) or (519 shr 11), u8(packet, 3))
        assertEquals(519, adtsLength(packet))
        assertEquals(0xFC, u8(packet, 6)) // fullness 0x7FF | one raw-data-block
    }

    @Test
    fun `length field carries the payload across boundary values`() {
        // The length field is 13 bits; check its three-byte split at the seams.
        for (payload in intArrayOf(1, 7, 8, 2047, 2048, 4096, 519)) {
            val packet = AdtsHeader.wrap(ByteArray(payload), 48_000, 2)
            assertEquals(payload + 7, adtsLength(packet))
        }
    }

    @Test
    fun `sampling frequency index table matches the reference`() {
        assertEquals(11, AdtsHeader.samplingFrequencyIndex(8000))
        assertEquals(8, AdtsHeader.samplingFrequencyIndex(16000))
        assertEquals(5, AdtsHeader.samplingFrequencyIndex(32000))
        assertEquals(4, AdtsHeader.samplingFrequencyIndex(44100))
        assertEquals(3, AdtsHeader.samplingFrequencyIndex(48000))
    }

    @Test
    fun `channel config rides bytes 2 and 3`() {
        val mono = AdtsHeader.wrap(ByteArray(100), 48_000, channels = 1)
        val stereo = AdtsHeader.wrap(ByteArray(100), 48_000, channels = 2)
        // channel config: mono = 1 → hi bits 0, lo bits 01; stereo = 2 → lo bits 10.
        assertEquals(0, (u8(mono, 2) and 0x01))
        assertEquals(1, (u8(mono, 3) shr 6) and 0x03)
        assertEquals(0, (u8(stereo, 2) and 0x01))
        assertEquals(2, (u8(stereo, 3) shr 6) and 0x03)
    }

    @Test
    fun `accumulator slices 10ms chunks into exact 1024-frame units`() {
        val accumulator = PcmUnitAccumulator(unitFrames = 1024, bytesPerFrame = 4)
        val chunk = ByteArray(480 * 4) // the ADM's ~10 ms stereo PCM16
        val unitBytes = accumulator.bytesPerUnit
        assertEquals(4096, unitBytes)

        val units = mutableListOf<Int>()
        repeat(10) { accumulator.push(chunk) { units.add(it.size) } }
        // 10 × 1920 B = 19200 B = 4 full units (16384 B) + 2816 B buffered.
        assertEquals(4, units.size)
        assertTrue(units.all { it == 4096 })
    }

    @Test
    fun `accumulator unit content is contiguous across chunk boundaries`() {
        val accumulator = PcmUnitAccumulator(unitFrames = 4, bytesPerFrame = 4)
        val first = byteArrayOf(1, 1, 1, 1, 2, 2, 2, 2) // 2 frames
        val second = byteArrayOf(3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5) // 3 frames
        val units = mutableListOf<Byte>()
        accumulator.push(first) { it.forEach { b -> units.add(b) } }
        accumulator.push(second) { it.forEach { b -> units.add(b) } }
        // One unit (4 frames: 1,2,3,4) — emitted when `second` completes it.
        assertEquals(listOf<Byte>(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4), units)
    }

    @Test
    fun `accumulator reset drops the partial tail`() {
        val accumulator = PcmUnitAccumulator(unitFrames = 2, bytesPerFrame = 4)
        var emitted = 0
        accumulator.push(ByteArray(4)) {} // half a unit
        accumulator.reset()
        accumulator.push(ByteArray(8)) { emitted++ }
        assertEquals(1, emitted)
    }
}
