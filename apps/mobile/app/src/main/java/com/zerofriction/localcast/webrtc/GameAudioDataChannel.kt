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
 * ADR-012: game audio as raw stereo PCM over a DataChannel. The pinned
 * libwebrtc build hard-downmixes the ADM's stereo input to mono before the
 * Opus encoder (its only gate, `WebRTC-Audio-MultiChannelSend`, is absent
 * from every obtainable native lib — 1.3.8/1.3.9/1.3.10 grep'd), so the
 * encoder path can never carry stereo. The capture source's encoder-input
 * bytes ride this channel instead, and the desktop renders them itself.
 *
 * Semantics tuned for positional audio on a LAN:
 * - **unordered, unacknowledged** (`ordered=false`, `maxRetransmits=0`) — a
 *   lost 20 ms sliver is inaudible, a stalled stream is not;
 * - **bounded queue, drop policy, no blocking** — the producer runs on the
 *   ADM's recording thread; it may never wait. The queue keeps the newest
 *   audio (drop-oldest), and a `bufferedAmount` past [MAX_BUFFERED_BYTES]
 *   skips sending outright until the pipe drains;
 * - a **muted cast keeps sending** (the ADM's mute already zeroes the
 *   frames) — identical flow for the desktop's fallback timer.
 *
 * Numbers only in the logs — never the samples (logging rules).
 */
class GameAudioDataChannel {
    private val closed = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val droppedByOverflow = AtomicLong()
    private val droppedByBackpressure = AtomicLong()
    private val sentChunks = AtomicLong()
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
     * One encoder-input read (stereo PCM16). Called on the ADM's recording
     * thread — enqueue only, never block; the queue keeps the freshest audio.
     */
    fun onEncoderInputSamples(samples: ByteArray) {
        if (closed.get()) return
        val chunk = samples.copyOf() // the producer's buffer is reused downstream
        if (!queue.offer(chunk)) {
            queue.poll()
            if (!queue.offer(chunk)) return
            droppedByOverflow.incrementAndGet()
        }
    }

    private fun drain() {
        while (!closed.get()) {
            val chunk = try {
                queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
            } catch (t: InterruptedException) {
                return
            }
            val current = channel ?: continue
            try {
                if (current.state() != DataChannel.State.OPEN) continue
                if (current.bufferedAmount() > MAX_BUFFERED_BYTES) {
                    droppedByBackpressure.incrementAndGet()
                    continue
                }
                current.send(DataChannel.Buffer(ByteBuffer.wrap(chunk), true))
                sentChunks.incrementAndGet()
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
            "game audio channel closed — sent ${sentChunks.get()}, " +
                "dropped ${droppedByOverflow.get()} (overflow) / ${droppedByBackpressure.get()} (backpressure)",
        )
    }

    companion object {
        private const val TAG = "GameAudioDataCh"

        /** The desktop switches sinks on this label (webrtc.md channel registry). */
        const val CHANNEL_LABEL = "game-audio"

        /**
         * ~0.5 s of 48 kHz stereo PCM16 at 10 ms chunks — long enough to
         * absorb a scheduler hiccup, short enough that overflow means the
         * pipe is truly gone.
         */
        const val QUEUE_CAPACITY = 50

        /** Drop-send above this buffered bytes (~0.5 s downstream). */
        const val MAX_BUFFERED_BYTES = 100_000L
    }
}
