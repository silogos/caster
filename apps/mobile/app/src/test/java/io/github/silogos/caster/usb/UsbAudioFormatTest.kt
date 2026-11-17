package io.github.silogos.caster.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The mic's link format (usb-link.md kind 3): 48 kHz mono PCM16LE, whatever the input's own rate. */
class UsbAudioFormatTest {

    @Test
    fun `48 kHz passes through as little-endian bytes`() {
        val out = UsbAudioFormat.toPcm48kMono(shortArrayOf(1, -1, 0x1234), 48_000)
        assertArrayEquals(byteArrayOf(1, 0, -1, -1, 0x34, 0x12), out)
    }

    @Test
    fun `a 16 kHz built-in mic becomes three times the samples, the 10 ms block 960 bytes`() {
        val block = ShortArray(160) { (it * 10).toShort() }
        val out = UsbAudioFormat.toPcm48kMono(block, 16_000)
        assertEquals(960, out.size)
        fun sample(i: Int) = (out[2 * i].toInt() and 0xFF) or (out[2 * i + 1].toInt() shl 8)
        assertEquals(0, sample(0))
        assertEquals(10, sample(3)) // every third output sample is an input sample
        assertEquals(3, sample(1)) // between input samples: interpolated (0 → 10 gives 3.33)
    }
}
