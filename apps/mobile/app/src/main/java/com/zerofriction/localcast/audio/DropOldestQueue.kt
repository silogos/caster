package com.zerofriction.localcast.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The bounded hand-off between a real-time producer that may **never wait**
 * (the ADM's recording thread) and a worker thread (the AAC encoder): when
 * full, the **oldest** item is dropped — stale audio is worth less than the
 * newest. Pure and JVM-tested; [droppedCount] feeds the encoder's teardown log.
 */
class DropOldestQueue<T : Any>(capacity: Int) {
    private val queue = ArrayBlockingQueue<T>(capacity)

    @Volatile
    var droppedCount = 0L
        private set

    /** Never blocks. A full queue sheds its oldest item to make room. */
    fun offer(item: T) {
        if (queue.offer(item)) return
        queue.poll()
        if (queue.offer(item)) droppedCount++
    }

    /** The oldest item, waiting up to [timeoutMs]; null on timeout. Throws on interrupt. */
    fun poll(timeoutMs: Long): T? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    val size: Int get() = queue.size
}
