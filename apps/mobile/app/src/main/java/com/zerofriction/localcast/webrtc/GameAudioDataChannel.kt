package com.zerofriction.localcast.webrtc

import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-012/ADR-013: game audio over a DataChannel. The pinned libwebrtc build
 * hard-downmixes the ADM's stereo input to mono before the Opus encoder (its
 * only gate, `WebRTC-Audio-MultiChannelSend`, is absent from every obtainable
 * native lib — 1.3.8/1.3.9/1.3.10 grep'd), so the encoder path can never carry
 * stereo. True stereo rides this channel instead, and the desktop renders it
 * itself. Payload (channel payload v1, webrtc.md): a **text** format announce,
 * then one platform-encoded Opus packet per binary message ([GameAudioOpusEncoder]);
 * the raw-PCM passthrough ([onEncoderInputSamples]) is the legacy fallback for
 * a device without the platform Opus encoder — the desktop classifies by
 * message type (binary-first = PCM).
 *
 * Semantics tuned for positional audio on a LAN:
 * - **unordered, unacknowledged** (`ordered=false`, `maxRetransmits=0`) — a
 *   lost 20 ms sliver is inaudible, a stalled stream is not;
 * - **bounded queue, drop policy, no blocking** — the producers run on the
 *   ADM's recording and encoder threads; they may never wait. The queue keeps
 *   the newest frames (drop-oldest), and a `bufferedAmount` past the payload's
 *   ceiling skips sending outright until the pipe drains;
 * - the **announce rides the same queue** as the packets, so the desktop never
 *   sees a format the sender cannot serve, and its ordering vs the first
 *   packet is the queue's ordering;
 * - a **muted cast keeps sending** (the ADM's mute already zeroes the
 *   frames) — identical flow for the desktop's fallback timer.
 *
 * Numbers only in the logs — never the samples (logging rules).
 */
class GameAudioDataChannel {
    private sealed interface Frame {
        /** Channel payload v1 announce (JSON text, webrtc.md). */
        data class Announce(val json: String) : Frame

        /** One Opus packet (opus mode) or one raw PCM chunk (legacy mode). */
        data class Binary(val bytes: ByteArray) : Frame
    }

    private val closed = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<Frame>(QUEUE_CAPACITY)
    private val droppedByOverflow = AtomicLong()
    private val droppedByBackpressure = AtomicLong()
    private val sentFrames = AtomicLong()
    private val sentBytes = AtomicLong()
    /** The payload ceiling switches with the mode — the announce marks opus. */
    private val bufferedAmountCeiling = AtomicLong(MAX_BUFFERED_BYTES)
    private var channel: DataChannel? = null
    private var sender: Thread? = null

    /** Opens the channel on the media pc (before createOffer — in-band negotiation). */
    fun attach(pc: PeerConnection): Boolean {
        if (channel !== null) {
            Log.w(TAG, "game audio channel already attached")
            return false
        }
        val init = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        val newChannel = try {
            pc.createDataChannel(CHANNEL_LABEL, init)
        } catch (t: Throwable) {
            Log.w(TAG, "createDataChannel failed: ${t.message}")
            null
        }
        if (newChannel === null) {
            Log.w(TAG, "game audio DataChannel unavailable — desktop falls back to the audio track")
            return false
        }
        channel = newChannel
        sender = Thread { drain() }.apply {
            name = "game-audio-send"
            start()
        }
        Log.i(TAG, "game audio DataChannel attached ($CHANNEL_LABEL, unordered, unacked)")
        return true
    }

    /**
     * Channel payload v1: the format announce, queued before the first packet
     * (ADR-013). Also switches the bufferedAmount ceiling to the Opus one —
     * opus packets are ~20× smaller than PCM chunks, and the same byte count
     * would otherwise allow seconds of latency creep.
     */
    fun queueAnnounce(json: String) {
        bufferedAmountCeiling.set(OPUS_MAX_BUFFERED_BYTES)
        enqueue(Frame.Announce(json))
    }

    /** One encoded Opus packet (opus mode). Called on the encoder thread. */
    fun onEncodedPacket(packet: ByteArray) {
        enqueue(Frame.Binary(packet.copyOf()))
    }

    /**
     * One encoder-input read (stereo PCM16, legacy mode). Called on the ADM's
     * recording thread — enqueue only, never block; the queue keeps the
     * freshest audio.
     */
    fun onEncoderInputSamples(samples: ByteArray) {
        enqueue(Frame.Binary(samples.copyOf()))
    }

    private fun enqueue(frame: Frame) {
        if (closed.get()) return
        if (!queue.offer(frame)) {
            queue.poll()
            if (!queue.offer(frame)) return
            droppedByOverflow.incrementAndGet()
        }
    }

    private fun drain() {
        while (!closed.get()) {
            val frame = try {
                queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
            } catch (t: InterruptedException) {
                return
            }
            val current = channel ?: continue
            try {
                if (current.state() != DataChannel.State.OPEN) continue
                if (current.bufferedAmount() > bufferedAmountCeiling.get()) {
                    droppedByBackpressure.incrementAndGet()
                    continue
                }
                val buffer = when (frame) {
                    is Frame.Announce ->
                        DataChannel.Buffer(ByteBuffer.wrap(frame.json.toByteArray(Charsets.UTF_8)), false)
                    is Frame.Binary -> DataChannel.Buffer(ByteBuffer.wrap(frame.bytes), true)
                }
                current.send(buffer)
                sentFrames.incrementAndGet()
                sentBytes.addAndGet(
                    when (frame) {
                        is Frame.Announce -> frame.json.length.toLong()
                        is Frame.Binary -> frame.bytes.size.toLong()
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "game audio send failed: ${t.message}")
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        sender?.interrupt()
        sender = null
        channel?.let {
            try {
                it.close()
            } catch (t: Throwable) {
                Log.d(TAG, "closing the game audio channel: ${t.message}")
            }
        }
        channel = null
        Log.i(
            TAG,
            "game audio channel closed — sent ${sentFrames.get()} frames / ${sentBytes.get()} bytes, " +
                "dropped ${droppedByOverflow.get()} (overflow) / ${droppedByBackpressure.get()} (backpressure)",
        )
    }

    companion object {
        private const val TAG = "GameAudioDataCh"

        /** The desktop switches sinks on this label (webrtc.md channel registry). */
        const val CHANNEL_LABEL = "game-audio"

        /**
         * 50 frames — ~1 s of 20 ms opus packets, ~0.5 s of 10 ms PCM chunks:
         * long enough to absorb a scheduler hiccup, short enough that overflow
         * means the pipe is truly gone.
         */
        const val QUEUE_CAPACITY = 50

        /** Legacy PCM drop-send ceiling (~0.5 s at the raw 1.5 Mbps payload). */
        const val MAX_BUFFERED_BYTES = 100_000L

        /** Opus drop-send ceiling (~1.5 s at 128 kbps) — smaller payload, same latency budget. */
        const val OPUS_MAX_BUFFERED_BYTES = 24_000L
    }
}
