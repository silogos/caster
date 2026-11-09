package com.zerofriction.localcast.audio

/**
 * A PCM-in encoder the game-audio sender drives ([GameAacEncoder] in
 * production): [queuePcm] is called on the ADM's recording thread and must
 * never block; frames and failures come back through the callbacks the
 * encoder was built with.
 */
interface PcmEncoder {
    fun start()
    fun queuePcm(chunk: ByteArray)
    fun release()
}
