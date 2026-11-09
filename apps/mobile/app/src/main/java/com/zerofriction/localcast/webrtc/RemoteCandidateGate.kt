package com.zerofriction.localcast.webrtc

/**
 * The trickle-ICE ordering rule both peer connections share (webrtc.md):
 * a desktop candidate may only be added once the **answer** for the offer in
 * flight set the remote description — libwebrtc rejects candidates that
 * arrive earlier. Until then they queue, and they flush in arrival order.
 *
 * Every new offer (a fresh pc, an ICE restart) [reset]s the gate: queued
 * candidates of the old offer are dropped, and the generation advances so a
 * remote-description success that belongs to the **previous** offer — its
 * SdpObserver callback hops threads and can land after the restart — cannot
 * open the gate for the new one ([open] with a stale generation is ignored).
 *
 * Pure and JVM-tested ([C] is libwebrtc's `IceCandidate` in production).
 * Synchronized: the mic session still opens it from libwebrtc's signaling
 * thread while candidates arrive on its own.
 */
class RemoteCandidateGate<C> {
    private val pending = mutableListOf<C>()
    private var isOpen = false

    /** The offer the gate currently belongs to. Capture it when the answer arrives. */
    @get:Synchronized
    var generation = 0
        private set

    /** A new offer is in flight: drop the old offer's candidates, close the gate. */
    @Synchronized
    fun reset() {
        pending.clear()
        isOpen = false
        generation++
    }

    /** A desktop candidate: [add]ed now when the gate is open, queued otherwise. */
    @Synchronized
    fun offer(candidate: C, add: (C) -> Unit) {
        if (isOpen) add(candidate) else pending.add(candidate)
    }

    /**
     * The answer of offer [answerGeneration] set the remote description:
     * open the gate and flush the queue in arrival order. False — nothing
     * flushed — when a newer offer has reset the gate since.
     */
    @Synchronized
    fun open(answerGeneration: Int, add: (C) -> Unit): Boolean {
        if (answerGeneration != generation) return false
        isOpen = true
        val queued = pending.toList()
        pending.clear()
        queued.forEach(add)
        return true
    }
}
