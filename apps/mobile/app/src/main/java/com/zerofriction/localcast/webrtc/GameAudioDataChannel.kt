package com.zerofriction.localcast.webrtc

import com.zerofriction.localcast.BuildConfig
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The game-audio DataChannel (ADR-012: the pinned libwebrtc build
 * hard-downmixes the ADM's stereo input to mono before its Opus encoder, so
 * the encoder path can never carry stereo). Payload per ADR-013: ADTS-framed
 * AAC-LC by default — negotiated in-band through [negotiator]'s
 * announce/ack — with ADR-012's raw PCM as the fallback format, so an old
 * desktop (it ignores text messages) degrades exactly as it always did.
 *
 * Semantics tuned for positional audio on a LAN (unchanged by ADR-013):
 * - **unordered, unacknowledged** (`ordered=false`, `maxRetransmits=0`) — a
 *   lost sliver is inaudible, a stalled stream is not;
 * - **bounded queue, drop policy, no blocking** — the producer runs on the
 *   ADM's or the AAC encoder's thread; it may never wait. The queue keeps
 *   the newest audio (drop-oldest), and a `bufferedAmount` past
 *   [MAX_BUFFERED_BYTES] skips sending outright until the pipe drains;
 * - a **muted cast keeps sending** (the ADM's mute already zeroes the
 *   frames — AAC-encoding silence is still a valid stream) — identical flow
 *   for the desktop's fallback timer.
 *
 * While the channel is open it also drives the handshake: the first OPEN
 * tick starts the negotiator's announce window, each tick polls for a due
 * announce (text message), and inbound text messages are acks. Numbers only
 * in the logs — never the samples (logging rules).
 */
class GameAudioDataChannel(private val negotiator: GameAudioFormatNegotiator? = null) {
    private val closed = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val droppedByOverflow = AtomicLong()
    private val droppedByBackpressure = AtomicLong()
    private val sentChunks = AtomicLong()
    private var channel: DataChannel? = null
    private var sender: Thread? = null
    /** Fired once, in the drain loop, when the SCTP handshake completes. */
    private var openAnnounced = false

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
        if (negotiator !== null) {
            newChannel.registerObserver(
                object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit
                    override fun onStateChange() {
                        Log.d(TAG, "game audio channel state: ${newChannel.state()}")
                    }

                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (buffer.binary) return
                        handleControlMessage(buffer)
                    }
                },
            )
        }
        sender = Thread { drain() }.apply {
            name = "game-audio-send"
            start()
        }
        Log.i(TAG, "game audio DataChannel attached ($CHANNEL_LABEL, unordered, unacked)")
        return true
    }

    /**
     * One wire-format chunk (PCM16 stereo while in PCM mode, one ADTS packet
     * in AAC mode — the sender decides). Called on the producer's thread —
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

    private fun handleControlMessage(buffer: DataChannel.Buffer) {
        val negotiator = negotiator ?: return
        try {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val parsed = HandshakeMessage.parse(String(bytes))
            if (parsed is HandshakeMessage.Ack) {
                negotiator.onAck(parsed.format)
                Log.i(TAG, "game audio format ack: ${parsed.format} — wire mode ${negotiator.mode}")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "ignoring malformed control message: ${t.message}")
        }
    }

    // Debug-only latency probe: how full the SCTP send buffer runs. A buffer
    // pinned near MAX_BUFFERED_BYTES is a standing queue of that much audio.
    private var bufferedSum = 0L
    private var bufferedMax = 0L
    private var bufferedSamples = 0
    private var bufferedWindowStartMs = 0L

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
                if (!openAnnounced) {
                    // First OPEN tick: start the announce window (ADR-013).
                    openAnnounced = true
                    negotiator?.onChannelOpen()
                }
                negotiator?.pollAnnounce()?.let { text ->
                    current.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))
                }
                if (BuildConfig.DEBUG) noteBuffered(current.bufferedAmount())
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
            "game audio channel closed — mode ${negotiator?.mode ?: GameAudioFormatNegotiator.Mode.PCM}, " +
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
         * ~0.5 s of AAC access units (~21.3 ms each), ~0.24 s of PCM fallback
         * chunks (~10 ms each) — long enough to absorb a scheduler hiccup,
         * short enough that overflow means the pipe is truly gone.
         */
        const val QUEUE_CAPACITY = 24

        /**
         * Skip sending above this buffered bytes downstream — ~0.5 s of AAC
         * (24 frames × ~500 B), ~60 ms of PCM; both far inside the queue's
         * own bound, so hitting this means the SCTP pipe itself is jammed.
         */
        const val MAX_BUFFERED_BYTES = 12_000L
    }
}
