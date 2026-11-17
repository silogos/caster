package io.github.silogos.caster.webrtc

import io.github.silogos.caster.BuildConfig
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The game-audio DataChannel (ADR-012): raw PCM16 stereo, opened only when
 * the profile's Low-latency audio is "Low latency" (ADR-033) — the
 * receiver plays it through its own low-latency playout (ADR-014/020).
 * With "Standard" there is no channel and the stereo Opus track is the
 * game audio (ADR-032).
 *
 * Semantics tuned for positional audio on a LAN:
 * - **unordered, unacknowledged** (`ordered=false`, `maxRetransmits=0`) — a
 *   lost sliver is inaudible, a stalled stream is not;
 * - **bounded queue, drop policy, no blocking** — the producer runs on the
 *   ADM's recording thread; it may never wait. The queue keeps
 *   the newest audio (drop-oldest), and a `bufferedAmount` past
 *   [MAX_BUFFERED_BYTES] skips sending outright until the pipe drains;
 * - a **muted cast keeps sending** (the ADM's mute already zeroes the
 *   frames) — identical flow for the desktop's fallback timer.
 *
 * Binary messages only: ADR-013's text handshake (announce/ack) is retired
 * (ADR-033). Numbers only in the logs — never the samples (logging rules).
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
     * One PCM16 stereo chunk from the ADM. Called on the producer's thread —
     * enqueue only, never block; the queue keeps the freshest audio.
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

    // Debug-only latency probe: how full the SCTP send buffer runs. A buffer
    // pinned near MAX_BUFFERED_BYTES is a standing queue of that much audio.
    private var bufferedSum = 0L
    private var bufferedMax = 0L
    private var bufferedSamples = 0
    private var bufferedWindowStartMs = 0L

    /** Debug-only: how late a chunk is handed to the channel. */
    private val sendGaps = io.github.silogos.caster.debug.GapProbe("audio send")

    private fun noteBuffered(bytes: Long) {
        val now = System.currentTimeMillis()
        if (bufferedWindowStartMs == 0L) bufferedWindowStartMs = now
        bufferedSum += bytes
        bufferedMax = maxOf(bufferedMax, bytes)
        bufferedSamples += 1
        if (now - bufferedWindowStartMs >= 1_000L) {
            Log.i("LatencyTest", "dc buffered avg=${bufferedSum / bufferedSamples} max=$bufferedMax bytes, backpressure drops=${droppedByBackpressure.get()}")
            bufferedSum = 0; bufferedMax = 0; bufferedSamples = 0; bufferedWindowStartMs = now
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
                if (BuildConfig.DEBUG) noteBuffered(current.bufferedAmount())
                if (current.bufferedAmount() > MAX_BUFFERED_BYTES) {
                    droppedByBackpressure.incrementAndGet()
                    continue
                }
                current.send(DataChannel.Buffer(ByteBuffer.wrap(chunk), true))
                sentChunks.incrementAndGet()
                if (BuildConfig.DEBUG) sendGaps.onEvent()
            } catch (t: Throwable) {
                Log.w(TAG, "game audio send failed: ${t.message}")
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val drainer = sender
        sender = null
        drainer?.interrupt()
        // The native channel is freed only once the drain thread is gone — a
        // send racing dispose() would touch freed memory. A thread that does
        // not stop in time leaks the channel instead (logged, never a crash).
        drainer?.join(SENDER_JOIN_TIMEOUT_MS)
        val drained = drainer?.isAlive != true
        channel?.let {
            try {
                it.unregisterObserver()
                it.close()
                if (drained) it.dispose() else Log.w(TAG, "game audio sender did not stop — channel not disposed")
            } catch (t: Throwable) {
                Log.d(TAG, "closing the game audio channel: ${t.message}")
            }
        }
        channel = null
        Log.i(
            TAG,
            "game audio channel closed — " +
                "sent ${sentChunks.get()}, dropped ${droppedByOverflow.get()} (overflow) / " +
                "${droppedByBackpressure.get()} (backpressure)",
        )
    }

    companion object {
        private const val TAG = "GameAudioDataCh"

        /** How long close() waits for the drain thread (it polls every 50 ms). */
        private const val SENDER_JOIN_TIMEOUT_MS = 500L

        /** The desktop switches sinks on this label (webrtc.md channel registry). */
        const val CHANNEL_LABEL = "game-audio"

        /**
         * ~0.24 s of PCM chunks (~10 ms each) — long enough to absorb a
         * scheduler hiccup, short enough that overflow means the pipe is
         * truly gone.
         */
        const val QUEUE_CAPACITY = 24

        /**
         * Skip sending above this buffered bytes downstream — ~60 ms of PCM,
         * far inside the queue's own bound, so hitting this means the SCTP
         * pipe itself is jammed.
         */
        const val MAX_BUFFERED_BYTES = 12_000L
    }
}
