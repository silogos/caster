package com.zerofriction.localcast.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The encoder's input hand-off: the ADM thread never waits, the newest audio survives. */
class DropOldestQueueTest {

    @Test
    fun `items come out in order while there is room`() {
        val queue = DropOldestQueue<Int>(capacity = 3)
        queue.offer(1)
        queue.offer(2)
        assertEquals(1, queue.poll(0))
        assertEquals(2, queue.poll(0))
        assertNull(queue.poll(0))
        assertEquals(0L, queue.droppedCount)
    }

    @Test
    fun `a full queue drops the oldest and keeps the newest`() {
        val queue = DropOldestQueue<Int>(capacity = 3)
        for (i in 1..5) queue.offer(i) // a stalled encoder: 2 chunks too many
        assertEquals(3, queue.size)
        assertEquals(2L, queue.droppedCount)
        assertEquals(listOf(3, 4, 5), List(3) { queue.poll(0) })
    }

    @Test(timeout = 1_000)
    fun `offering to a full queue never blocks the producer`() {
        val queue = DropOldestQueue<Int>(capacity = 1)
        repeat(10_000) { queue.offer(it) } // would hang on a blocking put
        assertEquals(9_999, queue.poll(0))
    }
}
