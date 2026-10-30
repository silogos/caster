package com.zerofriction.localcast.ui.home

import com.zerofriction.localcast.R
import com.zerofriction.localcast.service.VcCapability
import com.zerofriction.localcast.service.VirtualCameraUiState
import com.zerofriction.localcast.service.toUiState
import com.zerofriction.localcast.signaling.VcStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The virtual camera's UI mapping (ADR-007): vc-status → the card's state,
 * and the state → the honest subline. Pure — the phone renders exactly what
 * the desktop reported, never inferring more (AGENTS.md: simple copy).
 */
class VcUiMappingTest {

    @Test
    fun `unavailable desktops hide the card entirely`() {
        assertEquals(
            VirtualCameraUiState(VcCapability.Hidden, active = false),
            VcStatus(available = false, needsRegistration = false, active = false).toUiState(),
        )
        // Even a lying "active" on an unavailable desktop shows nothing.
        assertEquals(
            VirtualCameraUiState(VcCapability.Hidden, active = false),
            VcStatus(available = false, needsRegistration = false, active = true).toUiState(),
        )
    }

    @Test
    fun `needsRegistration maps to the one-time-setup state`() {
        assertEquals(
            VirtualCameraUiState(VcCapability.NeedsSetup, active = false),
            VcStatus(available = true, needsRegistration = true, active = false).toUiState(),
        )
    }

    @Test
    fun `available desktops map to ready with the active flag`() {
        assertEquals(
            VirtualCameraUiState(VcCapability.Ready, active = false),
            VcStatus(available = true, needsRegistration = false, active = false).toUiState(),
        )
        assertEquals(
            VirtualCameraUiState(VcCapability.Ready, active = true),
            VcStatus(available = true, needsRegistration = false, active = true).toUiState(),
        )
    }

    @Test
    fun `the subline states what turning it on does right now`() {
        assertEquals(R.string.vc_hint_active, vcSublineRes(VirtualCameraUiState(VcCapability.Ready, active = true)))
        assertEquals(R.string.vc_hint_needs_setup, vcSublineRes(VirtualCameraUiState(VcCapability.NeedsSetup)))
        assertEquals(R.string.vc_hint_ready, vcSublineRes(VirtualCameraUiState(VcCapability.Ready, active = false)))
        // Hidden never renders a subline (the card itself is gone).
        assertEquals(R.string.vc_hint_ready, vcSublineRes(VirtualCameraUiState(VcCapability.Hidden)))
    }
}
