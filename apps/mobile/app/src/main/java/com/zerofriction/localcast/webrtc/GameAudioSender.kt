package com.zerofriction.localcast.webrtc

import android.util.Log
import com.zerofriction.localcast.audio.GameAacEncoder
import com.zerofriction.localcast.audio.PlaybackCaptureAudioSource

/**
 * ADR-013: the game-audio send pipeline between the capture source and the
 * DataChannel. Two consumers of every ADM chunk:
 *
 * - the AAC encoder always — so it stays warm and the AAC switch needs no
 *   priming when the desktop acks;
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
 * matching how the channel itself is replaced).
 */
class GameAudioSender(
    private val negotiator: GameAudioFormatNegotiator,
    private val channel: GameAudioDataChannel,
    private val sampleRateHz: Int,
    private val channels: Int,
) {
    private var encoder: GameAacEncoder? = null

    fun start() {
        val newEncoder = GameAacEncoder(
            sampleRateHz = sampleRateHz,
            channels = channels,
            bitrateBps = GameAacEncoder.BITRATE_BPS,
            onFrame = { adts ->
                // Called on the encoder thread; the gate re-reads volatile
                // state per frame, so an encoder failure mid-cast stops the
                // AAC feed without any cross-thread handoff.
                if (negotiator.sendsAac) channel.onEncoderInputSamples(adts)
            },
            onError = {
                negotiator.onEncoderFailed()
                Log.w(TAG, "aac encoder failed — falling back to raw pcm (ADR-013)")
            },
        )
        newEncoder.start()
        encoder = newEncoder
    }

    /**
     * The capture source's encoder-input hook ([PlaybackCaptureAudioSource]
     * feeds this on the ADM's recording thread — enqueue only, never block).
     */
    fun onSamples(samples: ByteArray) {
        encoder?.queuePcm(samples)
        if (!negotiator.sendsAac) channel.onEncoderInputSamples(samples)
    }

    fun release() {
        encoder?.release()
        encoder = null
    }

    companion object {
        private const val TAG = "GameAudioSender"
    }
}
