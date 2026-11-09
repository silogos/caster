package com.zerofriction.localcast.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class MicPermissionTest {

    @Test
    fun `granted wins whatever was asked`() {
        assertEquals(MicPermission.Granted, micPermissionOf(granted = true, askedBefore = true, showRationale = false))
    }

    @Test
    fun `never asked is askable, not blocked, even without a rationale`() {
        assertEquals(MicPermission.Askable, micPermissionOf(granted = false, askedBefore = false, showRationale = false))
    }

    @Test
    fun `one denial leaves a rationale - android will still ask`() {
        assertEquals(MicPermission.Askable, micPermissionOf(granted = false, askedBefore = true, showRationale = true))
    }

    @Test
    fun `asked and no rationale left means android stopped asking`() {
        assertEquals(MicPermission.Blocked, micPermissionOf(granted = false, askedBefore = true, showRationale = false))
    }
}
