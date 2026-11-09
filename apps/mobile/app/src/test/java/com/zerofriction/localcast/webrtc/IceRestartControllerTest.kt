package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ICE-restart policy's acceptance matrix (issue #86): a transient UDP
 * outage mid-cast is survived with bounded restarts; a genuinely dead network
 * fails honestly within a bounded window. Every scenario drives the pure
 * state machine with the event/tick cadence the session produces.
 *
 * Timeline constants under test: grace 10 s, deadline 15 s, backoff 2 s → 5 s
 * (rung 3 unused before exhaustion), max 3 restarts, sustained give-up 60 s.
 */
class IceRestartControllerTest {

    /** Records the commands the session would execute. */
    private class Harness {
        val restarts = mutableListOf<Int>()
        val giveUps = mutableListOf<String>()
        val controller = IceRestartController(
            onRestart = { restarts.add(it) },
            onGiveUp = { giveUps.add(it) },
        )

        /** One watchdog tick — the policy's clock. */
        fun tick(nowMs: Long) = controller.onTick(nowMs)
    }

    @Test
    fun `a lone disconnect heals by itself — no restart, no give-up`() {
        val h = Harness()
        h.controller.onDisconnected(0)
        // Ticks through the whole grace window while merely disconnected.
        for (t in 1_000L until IceRestartController.DISCONNECT_GRACE_MS step 1_000) h.tick(t)
        assertTrue(h.restarts.isEmpty())
        assertTrue(h.giveUps.isEmpty())
        h.controller.onConnected(IceRestartController.DISCONNECT_GRACE_MS)
        assertFalse(h.controller.busy)
        assertTrue(h.restarts.isEmpty())
    }

    @Test
    fun `failed triggers the first restart immediately — no grace wait`() {
        val h = Harness()
        h.controller.onFailed(1_000)
        assertEquals(listOf(1), h.restarts)
        assertTrue(h.controller.busy)
        assertTrue(h.giveUps.isEmpty())
    }

    @Test
    fun `a disconnect that outlives the grace triggers the first restart`() {
        val h = Harness()
        h.controller.onDisconnected(0)
        for (t in 1_000L until IceRestartController.DISCONNECT_GRACE_MS step 1_000) h.tick(t)
        assertTrue(h.restarts.isEmpty())
        h.tick(IceRestartController.DISCONNECT_GRACE_MS)
        assertEquals(listOf(1), h.restarts)
    }

    @Test
    fun `recovery after a restart resets the ladder — the next outage gets a fresh budget`() {
        val h = Harness()
        h.controller.onFailed(1_000)
        assertEquals(listOf(1), h.restarts)
        h.controller.onConnected(3_000)
        assertFalse(h.controller.busy)
        // A second outage, later: full budget again.
        h.controller.onFailed(60_000)
        assertEquals(listOf(1, 1), h.restarts)
        assertTrue(h.giveUps.isEmpty())
    }

    @Test
    fun `a restart that fails again backs off before the next — 2s then 5s`() {
        val h = Harness()
        h.controller.onFailed(1_000) // restart #1 fires
        h.controller.onFailed(2_000) // in-flight attempt dies early
        assertEquals(listOf(1), h.restarts) // no immediate second offer
        h.tick(3_500) // backoff (2 s) not yet elapsed
        assertEquals(listOf(1), h.restarts)
        h.tick(4_001) // 2 s after the failure — restart #2
        assertEquals(listOf(1, 2), h.restarts)
        h.controller.onFailed(4_500) // #2 dies too
        h.tick(9_000) // 5 s rung not yet elapsed
        assertEquals(listOf(1, 2), h.restarts)
        h.tick(9_501)
        assertEquals(listOf(1, 2, 3), h.restarts)
        // The third failure exhausts the budget — honest give-up.
        h.controller.onFailed(10_000)
        assertEquals(1, h.giveUps.size)
        assertFalse(h.controller.busy)
    }

    @Test
    fun `a restart that never reconnects times out by deadline and counts as failed`() {
        val h = Harness()
        h.controller.onFailed(1_000) // restart #1 fires at 1_000
        // No further events — the offer just never leads anywhere.
        h.tick(1_000 + IceRestartController.RESTART_DEADLINE_MS - 1)
        assertEquals(listOf(1), h.restarts) // still in flight
        h.tick(1_000 + IceRestartController.RESTART_DEADLINE_MS + 1)
        // Deadline expired: backoff scheduled, not fired.
        assertEquals(listOf(1), h.restarts)
        h.tick(1_000 + IceRestartController.RESTART_DEADLINE_MS + 1 + 2_000)
        assertEquals(listOf(1, 2), h.restarts)
        assertTrue(h.giveUps.isEmpty())
    }

    @Test
    fun `a dead network with fast failures exhausts the restart ladder - bounded and honest`() {
        val h = Harness()
        h.controller.onFailed(0)
        // Every restart fails shortly after its offer goes out (fast FAILED
        // events, like a radio that is up enough to answer checks with
        // nothing). The ladder runs dry long before the sustained window.
        var now = 0L
        while (h.giveUps.isEmpty() && now < IceRestartController.SUSTAINED_FAILURE_GIVE_UP_MS + 5_000) {
            now += 500
            h.tick(now)
            if (now % 3_000 == 0L) h.controller.onFailed(now)
        }
        assertEquals(listOf("3 ice restarts exhausted"), h.giveUps)
        assertEquals(3, h.restarts.size) // never more than the budget
        assertTrue(now < IceRestartController.SUSTAINED_FAILURE_GIVE_UP_MS) // honest, in time
        assertFalse(h.controller.busy)
    }

    @Test
    fun `a dead-silent network gives up at the sustained-failure window`() {
        val h = Harness()
        // Only `disconnected`, then nothing: the offers go out over the
        // healthy TCP and nothing ever comes back (grace → #1 → deadline →
        // backoff → #2 → … ). The ladder would need 62 s; the sustained
        // window closes it honestly first.
        h.controller.onDisconnected(0)
        var now = 0L
        while (h.giveUps.isEmpty() && now < IceRestartController.SUSTAINED_FAILURE_GIVE_UP_MS + 5_000) {
            now += 500
            h.tick(now)
        }
        assertEquals(1, h.giveUps.size)
        assertTrue(h.giveUps[0].contains("sustained failure"))
        assertTrue(h.restarts.size <= IceRestartController.MAX_RESTARTS)
        assertTrue(now <= IceRestartController.SUSTAINED_FAILURE_GIVE_UP_MS)
        assertFalse(h.controller.busy)
    }

    @Test
    fun `a full renegotiation joins the deadline watch without spending the restart budget`() {
        val h = Harness()
        h.controller.onFailed(1_000) // restart #1
        // The socket dropped and came back — the session rebuilds the pc and
        // sends a full offer. That is the recovery attempt in flight now.
        h.controller.onNegotiating(5_000)
        h.tick(5_000 + IceRestartController.RESTART_DEADLINE_MS - 1)
        assertEquals(listOf(1), h.restarts) // the full offer still in flight
        h.controller.onConnected(10_000)
        assertTrue(h.restarts.size == 1)
        // Fresh budget after the recovery.
        h.controller.onFailed(30_000)
        assertEquals(listOf(1, 1), h.restarts)
    }

    // Found live 2026-11-02 (the overnight crash, issue #88): a full
    // negotiate (cast start / re-auth) that never connects reaches
    // attemptFailed with restartsSent == 0 — the backoff indexed -1 and
    // ArrayIndexOutOfBoundsException killed the process, five times. Both
    // entry paths are pinned here: the deadline expiry via the tick, and a
    // `failed` arriving while the negotiate is in flight.
    @Test
    fun `a timed-out full negotiate schedules the first restart immediately - the overnight crash`() {
        val h = Harness()
        h.controller.onDisconnected(0)
        h.controller.onNegotiating(1_000) // cast start / re-auth rebuild
        // Deadline expires with no FAILED event and no restart ever sent.
        h.tick(1_000 + IceRestartController.RESTART_DEADLINE_MS + 1)
        assertEquals(0, h.restarts.size) // scheduled, not yet fired
        // Backoff is zero before the first restart: it fires on the next tick.
        h.tick(1_000 + IceRestartController.RESTART_DEADLINE_MS + 501)
        assertEquals(listOf(1), h.restarts)
        assertTrue(h.giveUps.isEmpty())
    }

    @Test
    fun `a failed event during a full negotiate schedules the first restart - no negative index`() {
        val h = Harness()
        h.controller.onNegotiating(0)
        h.controller.onFailed(2_000) // in-flight negotiate dies — was the crash
        assertEquals(0, h.restarts.size) // nothing immediate…
        h.tick(2_000) // …zero backoff: restart #1 fires on the tick
        assertEquals(listOf(1), h.restarts)
        assertTrue(h.giveUps.isEmpty())
    }

    @Test
    fun `a failed event during a backoff never bypasses it`() {
        val h = Harness()
        h.controller.onFailed(1_000) // restart #1
        h.controller.onFailed(2_000) // dies → 2 s backoff until 4_000
        h.controller.onFailed(3_000) // stray failure mid-backoff
        h.tick(3_999)
        assertEquals(listOf(1), h.restarts)
        h.tick(4_000)
        assertEquals(listOf(1, 2), h.restarts)
    }

    @Test
    fun `events after give-up are inert`() {
        val h = Harness()
        h.controller.onFailed(0)
        h.controller.onFailed(1_000)
        h.controller.onFailed(2_000) // #1 died → backoff
        h.tick(4_000) // #2
        h.controller.onFailed(5_000)
        h.tick(10_000) // #3
        h.controller.onFailed(11_000) // budget exhausted → give up
        assertEquals(1, h.giveUps.size)
        val gaveUps = h.giveUps.size
        h.controller.onFailed(12_000)
        h.tick(13_000)
        h.controller.onDisconnected(14_000)
        assertEquals(gaveUps, h.giveUps.size)
        assertEquals(3, h.restarts.size)
        assertFalse(h.controller.busy)
    }

    @Test
    fun `give-up messages say which bound fired`() {
        val h = Harness()
        h.controller.onFailed(0)
        h.controller.onFailed(1_000)
        h.controller.onFailed(2_000)
        h.tick(4_000) // #2
        h.controller.onFailed(5_000)
        h.tick(10_000) // #3
        h.controller.onFailed(11_000) // exhausted
        assertEquals(listOf("3 ice restarts exhausted"), h.giveUps)
    }

    // Mobile review, group A: an answer that never arrives (or a failed
    // setRemoteDescription) used to leave the cast in NEGOTIATING forever —
    // the offer did not open a bad stretch, so nothing ever ticked.
    @Test
    fun `a cast-start offer with no answer is watched - restarts then gives up honestly`() {
        val h = Harness()
        h.controller.onNegotiating(0) // cast start, healthy before
        assertTrue(h.controller.busy) // the session's watchdog runs now
        var t = 0L
        while (h.giveUps.isEmpty() && t <= IceRestartController.SUSTAINED_FAILURE_GIVE_UP_MS) {
            t += 500
            h.tick(t)
        }
        assertEquals(listOf(1, 2, 3), h.restarts) // recovery attempts first
        assertEquals(1, h.giveUps.size) // then an honest, bounded failure
        assertTrue(t <= IceRestartController.SUSTAINED_FAILURE_GIVE_UP_MS)
    }

    @Test
    fun `a cast-start offer that connects leaves nothing running`() {
        val h = Harness()
        h.controller.onNegotiating(0)
        h.controller.onConnected(1_200)
        assertFalse(h.controller.busy)
        h.tick(IceRestartController.SUSTAINED_FAILURE_GIVE_UP_MS * 2)
        assertTrue(h.restarts.isEmpty())
        assertTrue(h.giveUps.isEmpty())
    }
}
