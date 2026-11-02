package com.zerofriction.localcast.hotspot

import com.zerofriction.localcast.pairing.PairingError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hotspot lifecycle's acceptance matrix (issue #96, ADR-011), driven on
 * the pure machine — the platform wrapper only feeds it OS callbacks. The
 * guided-manual flow's honesty lives here: every OS misbehavior lands in a
 * plain-words state, never a stuck one.
 */
class HotspotMachineTest {

    @Test
    fun `starts with permission straight to Starting`() {
        val machine = HotspotMachine()
        machine.startRequested(hasPermission = true)
        assertEquals(HotspotMachine.State.Starting, machine.state.value)
    }

    @Test
    fun `without permission it asks first, and the grant proceeds`() {
        val machine = HotspotMachine()
        machine.startRequested(hasPermission = false)
        assertEquals(HotspotMachine.State.NeedPermission, machine.state.value)

        machine.permissionResult(granted = true)
        assertEquals(HotspotMachine.State.Starting, machine.state.value)
    }

    @Test
    fun `a declined permission is an honest failure, retryable`() {
        val machine = HotspotMachine()
        machine.startRequested(hasPermission = false)
        machine.permissionResult(granted = false)
        assertEquals(HotspotMachine.State.Failed(HotspotMachine.Reason.PERMISSION_DECLINED), machine.state.value)

        machine.startRequested(hasPermission = true)
        assertEquals(HotspotMachine.State.Starting, machine.state.value)
    }

    @Test
    fun `the reservation's credentials become Active`() {
        val machine = HotspotMachine()
        machine.startRequested(hasPermission = true)
        machine.started(ssid = "AndroidAP_1234", password = "abcd1234")
        assertEquals(HotspotMachine.State.Active("AndroidAP_1234", "abcd1234"), machine.state.value)
    }

    @Test
    fun `every OS start failure lands in a distinct honest reason`() {
        val reasons = listOf(
            HotspotMachine.Reason.NOT_SUPPORTED,
            HotspotMachine.Reason.NO_CHANNEL,
            HotspotMachine.Reason.INCOMPATIBLE_MODE,
            HotspotMachine.Reason.UNKNOWN,
        )
        for (reason in reasons) {
            val machine = HotspotMachine()
            machine.startRequested(hasPermission = true)
            machine.startFailed(reason)
            assertEquals(HotspotMachine.State.Failed(reason), machine.state.value)
        }
    }

    @Test
    fun `the OS revoking a live hotspot is honest, not silent`() {
        val machine = HotspotMachine()
        machine.startRequested(hasPermission = true)
        machine.started(ssid = "s", password = "p")
        machine.osRevoked()
        assertEquals(HotspotMachine.State.Failed(HotspotMachine.Reason.OS_REVOKED), machine.state.value)
    }

    @Test
    fun `an explicit stop is the only normal exit from Active`() {
        val machine = HotspotMachine()
        machine.startRequested(hasPermission = true)
        machine.started(ssid = "s", password = "p")
        machine.stopRequested()
        assertEquals(HotspotMachine.State.Idle, machine.state.value)
    }

    @Test
    fun `double-taps never double-start`() {
        val machine = HotspotMachine()
        machine.startRequested(hasPermission = true)
        machine.startRequested(hasPermission = true)
        assertEquals(HotspotMachine.State.Starting, machine.state.value)

        machine.started(ssid = "s", password = "p")
        machine.startRequested(hasPermission = true)
        assertEquals(HotspotMachine.State.Active("s", "p"), machine.state.value)
    }

    @Test
    fun `stale callbacks cannot corrupt a fresh cycle`() {
        // The OS callback races the user's Stop: a late onStarted after a
        // stop must not resurrect a dead hotspot.
        val machine = HotspotMachine()
        machine.startRequested(hasPermission = true)
        machine.stopRequested()
        machine.started(ssid = "s", password = "p")
        assertEquals(HotspotMachine.State.Idle, machine.state.value)

        // Same for a late failure arriving after Idle.
        machine.startRequested(hasPermission = true)
        machine.stopRequested()
        machine.startFailed(HotspotMachine.Reason.NO_CHANNEL)
        assertEquals(HotspotMachine.State.Idle, machine.state.value)
    }

    @Test
    fun `a permission result without the ask is ignored`() {
        val machine = HotspotMachine()
        machine.permissionResult(granted = true)
        assertEquals(HotspotMachine.State.Idle, machine.state.value)
    }

    @Test
    fun `a retry from any failure goes straight to Starting with the grant`() {
        for (reason in HotspotMachine.Reason.entries) {
            val machine = HotspotMachine()
            machine.startRequested(hasPermission = true)
            machine.startFailed(reason)
            machine.startRequested(hasPermission = true)
            assertEquals(HotspotMachine.State.Starting, machine.state.value)
        }
    }

    @Test
    fun `the failure hint appears exactly for the unreachable-desktop error`() {
        // Issue #96's follow-up: the "Start a hotspot here" escape belongs to
        // the one failure that means "no shared network" — never to busy,
        // expired, bad-auth, or forgotten-desktop failures.
        assertTrue(hotspotHintFor(PairingError.CONNECT_UNREACHABLE))
        for (error in PairingError.entries - PairingError.CONNECT_UNREACHABLE) {
            assertEquals("no hint for $error", false, hotspotHintFor(error))
        }
    }
}
