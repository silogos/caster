package com.zerofriction.localcast.webrtc

import android.util.Log
import com.zerofriction.localcast.audio.GameAacEncoder
import com.zerofriction.localcast.audio.PcmEncoder
import com.zerofriction.localcast.audio.PlaybackCaptureAudioSource

/**
 * ADR-013: the game-audio send pipeline between the capture source and the
 * DataChannel. Two consumers of every ADM chunk:
 *
 * - the AAC encoder while AAC is still possible — warm through the announce
 *   window so the switch needs no priming when the desktop acks, and
 *   released once the window closes without an ack (PCM is settled: an old
 *   desktop, or the native receiver, which never acks — it used to encode
 *   the whole cast for nobody). A late ack starts it again before ADTS
 *   takes the wire;
 * - the channel itself only while PCM is the wire format (pre-ack, old
 *   desktop, encoder failure) — raw chunks, byte-identical to ADR-012, so
 *   *every* desktop hears audio from the first second.
 *
 * Once the negotiator's mode is AAC, encoded ADTS frames take the wire and
 * the PCM feed stops; an encoder failure flips the gate back (the
 * negotiator also re-announces `pcm16`), so the degrade is immediate — the
 * desktop never starves past its quiet-timer fallback.
 *
 * Owns the encoder lifecycle: [start] at channel attach, [release] at
 * teardown (and at any re-auth `negotiate()`, which rebuilds the whole
 * pipeline — the release of the previous sender is the caller's job,
 * matching how the channel itself is replaced). The caller may skip [start]
 * entirely when the low-latency toggle forces PCM (Feature 2026-11d): every
 * encoder touch here is null-safe, and [onSamples] keeps feeding raw PCM.
 */
class GameAudioSender(
    private val negotiator: GameAudioFormatNegotiator,
    /** The DataChannel's send queue ([GameAudioDataChannel.onEncoderInputSamples]): PCM chunks or ADTS frames. */
    private val wire: (ByteArray) -> Unit,
    /** Builds a started-on-[PcmEncoder.start] encoder; the AAC MediaCodec one in production. */
    private val encoderFactory: (onFrame: (ByteArray) -> Unit, onError: () -> Unit) -> PcmEncoder,
) {
    constructor(
        negotiator: GameAudioFormatNegotiator,
        channel: GameAudioDataChannel,
        sampleRateHz: Int,
        channels: Int,
    ) : this(
        negotiator = negotiator,
        wire = channel::onEncoderInputSamples,
        encoderFactory = { onFrame, onError ->
            GameAacEncoder(sampleRateHz, channels, GameAacEncoder.BITRATE_BPS, onFrame, onError)
        },
    )

    /** Guards [encoder]: swapped on the channel threads, fed on the ADM thread. */
    private val lock = Any()
    private var encoder: PcmEncoder? = null

    fun start() {
        negotiator.onPcmSettled = {
            if (stopEncoder()) Log.i(TAG, "no aac ack — encoder released, raw pcm for this cast")
        }
        negotiator.onAacAccepted = {
            synchronized(lock) {
                if (encoder == null) {
                    Log.i(TAG, "late aac ack — restarting the encoder")
                    encoder = newEncoder()
                }
            }
        }
        synchronized(lock) { encoder = newEncoder() }
    }

    private fun newEncoder(): PcmEncoder {
        val newEncoder = encoderFactory(
            { adts ->
                // Called on the encoder thread; the gate re-reads volatile
                // state per frame, so an encoder failure mid-cast stops the
                // AAC feed without any cross-thread handoff.
                if (negotiator.sendsAac) wire(adts)
            },
            {
                negotiator.onEncoderFailed()
                Log.w(TAG, "aac encoder failed — falling back to raw pcm (ADR-013)")
            },
        )
        newEncoder.start()
        return newEncoder
    }

    /**
     * Takes the encoder out under the lock and releases it outside, so the
     * ADM thread never waits on MediaCodec's teardown. True if one ran.
     */
    private fun stopEncoder(): Boolean {
        val stale = synchronized(lock) { encoder.also { encoder = null } } ?: return false
        stale.release()
        return true
    }

    /**
     * The capture source's encoder-input hook ([PlaybackCaptureAudioSource]
     * feeds this on the ADM's recording thread — enqueue only, never block).
     */
    fun onSamples(samples: ByteArray) {
        synchronized(lock) { encoder?.queuePcm(samples) }
        if (!negotiator.sendsAac) wire(samples)
    }

    fun release() {
        negotiator.onPcmSettled = null
        negotiator.onAacAccepted = null
        stopEncoder()
    }

    companion object {
        private const val TAG = "GameAudioSender"
    }
}
