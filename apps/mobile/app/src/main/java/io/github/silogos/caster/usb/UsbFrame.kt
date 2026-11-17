package io.github.silogos.caster.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The USB link's frame (docs/architecture/usb-link.md, ADR-034): a 16-byte
 * big-endian header, then the payload. Pure — JVM-tested against the
 * receiver's own encoding (mirrored vectors in both apps).
 */
object UsbFrame {
    const val LINK_VERSION = 1
    const val HEADER_LEN = 16
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    const val KIND_CONTROL = 0
    const val KIND_VIDEO = 1
    const val KIND_GAME_AUDIO = 2
    const val KIND_MIC_AUDIO = 3
    private const val KIND_LAST = KIND_MIC_AUDIO

    const val FLAG_KEYFRAME = 0x1
    const val FLAG_CONFIG = 0x2

    /** One frame, header and payload in a single array (one bulk write). */
    fun encode(kind: Int, flags: Int, ptsUs: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        require(length in 0..MAX_PAYLOAD) { "payload of $length bytes over the cap" }
        val out = ByteBuffer.allocate(HEADER_LEN + length).order(ByteOrder.BIG_ENDIAN)
        out.put(LINK_VERSION.toByte()).put(kind.toByte()).putShort(flags.toShort()).putLong(ptsUs).putInt(length)
        out.put(payload, offset, length)
        return out.array()
    }

    data class Frame(val kind: Int, val flags: Int, val ptsUs: Long, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && kind == other.kind && flags == other.flags && ptsUs == other.ptsUs &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int = (kind * 31 + flags) * 31 + payload.contentHashCode()
    }

    /** A stream that cannot be read any further: the link closes (never resynchronised by searching). */
    class Broken(message: String) : Exception(message)

    /**
     * Turns bulk reads (any split) back into frames. Not thread-safe: one
     * reader thread owns it.
     */
    class Reader {
        private var buf = ByteArray(64 * 1024)
        private var len = 0

        /** Appends [n] bytes and returns every frame now complete. */
        fun feed(bytes: ByteArray, n: Int = bytes.size): List<Frame> {
            ensure(len + n)
            System.arraycopy(bytes, 0, buf, len, n)
            len += n
            val frames = ArrayList<Frame>(2)
            var start = 0
            while (len - start >= HEADER_LEN) {
                val h = ByteBuffer.wrap(buf, start, HEADER_LEN).order(ByteOrder.BIG_ENDIAN)
                val version = h.get().toInt() and 0xFF
                val kind = h.get().toInt() and 0xFF
                val flags = h.short.toInt() and 0xFFFF
                val pts = h.long
                val size = h.int
                if (version != LINK_VERSION) throw Broken("link version $version, expected $LINK_VERSION")
                if (kind > KIND_LAST) throw Broken("unknown frame kind $kind")
                if (size < 0 || size > MAX_PAYLOAD) throw Broken("payload length $size over the cap")
                if (len - start < HEADER_LEN + size) break
                frames += Frame(kind, flags, pts, buf.copyOfRange(start + HEADER_LEN, start + HEADER_LEN + size))
                start += HEADER_LEN + size
            }
            if (start > 0) {
                System.arraycopy(buf, start, buf, 0, len - start)
                len -= start
            }
            return frames
        }

        private fun ensure(capacity: Int) {
            if (capacity <= buf.size) return
            buf = buf.copyOf(maxOf(capacity, buf.size * 2))
        }
    }
}
