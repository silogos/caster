package com.zerofriction.localcast.webrtc

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The offerer side of one peer connection (webrtc.md) — the sequencing both
 * casts' pcs share (`media` and `mic`; mobile review 2026-11-09, finding
 * #23, ~150 lines that used to be copy-pasted between the two sessions):
 *
 *  - **offer:** createOffer → [offerTransform] (codec order, Opus fmtp) →
 *    setLocalDescription → [sendOffer] — the offer leaves only once the pc
 *    holds it;
 *  - **answer:** [answerTransform] → setRemoteDescription → [onAnswerApplied]
 *    → the [RemoteCandidateGate] flushes the desktop's early candidates;
 *  - **remote candidates:** decoded, queued until the answer, then added;
 *    an end-of-candidates `null` is informational;
 *  - **ICE restart:** gate reset + fresh credentials + a new offer on the
 *    same pc.
 *
 * libwebrtc's SdpObserver callbacks arrive on its own signaling thread: every
 * outcome is hopped onto the session thread through [post] and dropped when
 * the peer it belongs to has since been replaced ([attach]/[detach]) — a late
 * callback from a dead pc can never send an offer, open the gate, or fail
 * the current one. What a session *does* about failures (the media recovery
 * ladder, the mic reconnect ladder) stays in the session.
 *
 * Pure over [PeerPort] — JVM-tested; [LibWebRtcPeer] is the production port.
 */
class PeerNegotiator<C>(
    /** "media" / "mic" — the pc id, for logs. */
    private val label: String,
    /** Runs a block on the owning session's thread. */
    private val post: (() -> Unit) -> Unit,
    private val sendOffer: (sdp: String) -> Unit,
    private val decodeCandidate: (JsonElement) -> C?,
    /** createOffer / setLocalDescription failed — the detail is for logs only. */
    private val onOfferFailed: (detail: String) -> Unit,
    private val offerTransform: (String) -> String = { it },
    private val answerTransform: (String) -> String = { it },
    /** The answer is set — runs before the queued candidates flush. */
    private val onAnswerApplied: () -> Unit = {},
    private val onAnswerFailed: (error: String?) -> Unit = {},
) {
    private var peer: PeerPort<C>? = null
    private val gate = RemoteCandidateGate<C>()

    /** A fresh pc takes over (the previous one, if any, is disposed); its first offer is [offer]. */
    fun attach(newPeer: PeerPort<C>) {
        peer?.dispose()
        peer = newPeer
        gate.reset()
    }

    /** The pc is gone (teardown, rebuild): dispose it; its late callbacks are ignored. */
    fun detach() {
        peer?.dispose()
        peer = null
        gate.reset()
    }

    fun offer() {
        val target = peer ?: return
        target.createOffer(
            onSuccess = { original ->
                val munged = offerTransform(original)
                target.setLocalOffer(
                    munged,
                    onSuccess = {
                        post {
                            if (peer !== target) return@post
                            sendOffer(munged)
                            Log.i(TAG, "$label: sdp offer sent (${munged.length} bytes)")
                        }
                    },
                    onFailure = { error -> post { if (peer === target) onOfferFailed("setLocalDescription: $error") } },
                )
            },
            onFailure = { error -> post { if (peer === target) onOfferFailed("createOffer: $error") } },
        )
    }

    /**
     * Fresh ICE credentials on the **same** pc — tracks, senders and DTLS
     * survive. Candidates from here on belong to the restarted ICE: they
     * queue until the new answer lands.
     */
    fun restartIce() {
        val target = peer ?: return
        gate.reset()
        target.restartIce()
        offer()
    }

    fun applyAnswer(sdp: String) {
        val target = peer ?: return
        // Captured now: an ICE restart before the callback lands supersedes this answer.
        val answerGeneration = gate.generation
        target.setRemoteAnswer(
            answerTransform(sdp),
            onSuccess = {
                post {
                    if (peer !== target) return@post // a newer pc owns the queue
                    onAnswerApplied()
                    if (!gate.open(answerGeneration) { target.addRemoteCandidate(it) }) {
                        Log.i(TAG, "$label: answer applied for a superseded offer — candidates keep waiting")
                    }
                }
            },
            onFailure = { error ->
                post {
                    Log.e(TAG, "$label: setRemoteDescription failed: $error")
                    if (peer === target) onAnswerFailed(error)
                }
            },
        )
    }

    fun applyRemoteCandidate(element: JsonElement) {
        val target = peer ?: return
        if (element is JsonNull) {
            Log.d(TAG, "$label: desktop finished ice gathering")
            return
        }
        val candidate = decodeCandidate(element)
        if (candidate === null) {
            Log.w(TAG, "$label: malformed candidate from desktop — ignoring")
            return
        }
        gate.offer(candidate) { target.addRemoteCandidate(it) }
    }

    private companion object {
        const val TAG = "PeerNegotiator"
    }
}

/**
 * The slice of a peer connection [PeerNegotiator] drives. Callbacks may
 * arrive on any thread — the negotiator hops them itself.
 */
interface PeerPort<C> {
    fun createOffer(onSuccess: (sdp: String) -> Unit, onFailure: (error: String?) -> Unit)
    fun setLocalOffer(sdp: String, onSuccess: () -> Unit, onFailure: (error: String?) -> Unit)
    fun setRemoteAnswer(sdp: String, onSuccess: () -> Unit, onFailure: (error: String?) -> Unit)
    fun addRemoteCandidate(candidate: C)
    fun restartIce()
    fun dispose()
}
