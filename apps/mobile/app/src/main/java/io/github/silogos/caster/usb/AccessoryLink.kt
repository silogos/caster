package io.github.silogos.caster.usb

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * One opened accessory: the byte stream of usb-link.md, framed. Owns the
 * file descriptor and the reader thread. Writes come from several threads
 * (video, audio, control) and are serialized here, one whole frame at a time.
 */
class AccessoryLink(
    private val fd: ParcelFileDescriptor,
    private val onFrame: (UsbFrame.Frame) -> Unit,
    private val onClosed: (reason: String) -> Unit,
) {
    private val input = FileInputStream(fd.fileDescriptor)
    private val output = FileOutputStream(fd.fileDescriptor)
    private val writeLock = Any()

    @Volatile private var closed = false

    @Volatile var bytesSent = 0L
        private set

    fun start() {
        thread(name = "usb-link-read", isDaemon = true) { readLoop() }
    }

    /** Writes one frame. False once the link is closed or the write failed (which closes it). */
    fun send(kind: Int, flags: Int, ptsUs: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size): Boolean =
        sendFramed(UsbFrame.encode(kind, flags, ptsUs, payload, offset, length))

    /** Writes a frame already laid out by [UsbFrame.framed] / [UsbFrame.encode]. Same result as [send]. */
    fun sendFramed(frame: ByteArray): Boolean {
        if (closed) return false
        return try {
            synchronized(writeLock) {
                output.write(frame)
                // Inside the lock: video, audio and mic threads all count here.
                bytesSent += frame.size
            }
            true
        } catch (e: IOException) {
            close("write failed: ${e.message}")
            false
        }
    }

    private fun readLoop() {
        // Accessory reads must offer at least one USB packet's worth; 16 KiB is the documented safe size.
        val buf = ByteArray(READ_BUFFER_BYTES)
        val reader = UsbFrame.Reader()
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                for (frame in reader.feed(buf, n)) onFrame(frame)
            }
            close("end of stream")
        } catch (e: UsbFrame.Broken) {
            close("broken stream: ${e.message}")
        } catch (e: IOException) {
            close("read failed: ${e.message}")
        }
    }

    fun close(reason: String) {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        Log.i(TAG, "link closed: $reason")
        runCatching { fd.close() }
        onClosed(reason)
    }

    private companion object {
        const val TAG = "UsbLink"
        const val READ_BUFFER_BYTES = 16 * 1024
    }
}
