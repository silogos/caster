package io.github.silogos.caster.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The USB link's frame (usb-link.md): what the receiver's usb_protocol tests pin too. */
class UsbFrameTest {

    @Test
    fun `the header matches the receiver's vector`() {
        val bytes = UsbFrame.encode(UsbFrame.KIND_GAME_AUDIO, 0, 0x0102030405060708L, byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        val expected = intArrayOf(1, 2, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 2, 0xAA, 0xBB).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `a frame filled in place is the same bytes as an encoded one`() {
        val payload = byteArrayOf(9, 8, 7, 6, 5, 4)
        val framed = UsbFrame.framed(UsbFrame.KIND_VIDEO, UsbFrame.FLAG_KEYFRAME or UsbFrame.FLAG_CONFIG, 42L, payload.size)
        System.arraycopy(payload, 0, framed, UsbFrame.HEADER_LEN, payload.size)
        assertArrayEquals(UsbFrame.encode(UsbFrame.KIND_VIDEO, UsbFrame.FLAG_KEYFRAME or UsbFrame.FLAG_CONFIG, 42L, payload), framed)
        assertEquals(
            listOf(UsbFrame.Frame(UsbFrame.KIND_VIDEO, UsbFrame.FLAG_KEYFRAME or UsbFrame.FLAG_CONFIG, 42L, payload)),
            UsbFrame.Reader().feed(framed),
        )
    }

    @Test
    fun `a frame over the payload cap is refused before it is built`() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbFrame.framed(UsbFrame.KIND_VIDEO, 0, 0, UsbFrame.MAX_PAYLOAD + 1)
        }
    }

    @Test
    fun `frames come back whole from any split of the stream`() {
        val video = UsbFrame.encode(UsbFrame.KIND_VIDEO, UsbFrame.FLAG_KEYFRAME, 123_456_789L, byteArrayOf(1, 2, 3, 4, 5))
        val control = UsbFrame.encode(UsbFrame.KIND_CONTROL, 0, 0, """{"type":"hello"}""".toByteArray())
        val stream = video + control
        for (split in 0..stream.size) {
            val reader = UsbFrame.Reader()
            val frames = reader.feed(stream.copyOfRange(0, split)) + reader.feed(stream.copyOfRange(split, stream.size))
            assertEquals("split at $split", 2, frames.size)
            assertEquals(UsbFrame.Frame(UsbFrame.KIND_VIDEO, UsbFrame.FLAG_KEYFRAME, 123_456_789L, byteArrayOf(1, 2, 3, 4, 5)), frames[0])
            assertEquals(UsbFrame.KIND_CONTROL, frames[1].kind)
        }
    }

    @Test
    fun `a wrong version, an unknown kind or a huge length breaks the stream`() {
        val badVersion = UsbFrame.encode(UsbFrame.KIND_VIDEO, 0, 0, ByteArray(0)).also { it[0] = 2 }
        val badKind = UsbFrame.encode(UsbFrame.KIND_VIDEO, 0, 0, ByteArray(0)).also { it[1] = 9 }
        val huge = UsbFrame.encode(UsbFrame.KIND_VIDEO, 0, 0, ByteArray(0)).also {
            val n = UsbFrame.MAX_PAYLOAD + 1
            it[12] = (n ushr 24).toByte(); it[13] = (n ushr 16).toByte(); it[14] = (n ushr 8).toByte(); it[15] = n.toByte()
        }
        for (bytes in listOf(badVersion, badKind, huge)) {
            assertThrows(UsbFrame.Broken::class.java) { UsbFrame.Reader().feed(bytes) }
        }
    }
}
