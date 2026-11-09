package com.zerofriction.localcast.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** The share consent ladder (mobile.md): ordered, skips held grants, never loops on a denial. */
class ShareConsentTest {

    @Test
    fun `a fresh install walks every step in order`() {
        assertEquals(ShareStep.RECORD_AUDIO, nextShareStep(null, recordAudioGranted = false, notificationsGranted = false))
        assertEquals(ShareStep.NOTIFICATIONS, nextShareStep(ShareStep.RECORD_AUDIO, recordAudioGranted = true, notificationsGranted = false))
        assertEquals(ShareStep.PROJECTION, nextShareStep(ShareStep.NOTIFICATIONS, recordAudioGranted = true, notificationsGranted = true))
    }

    @Test
    fun `held permissions are skipped straight to the projection consent`() {
        assertEquals(ShareStep.PROJECTION, nextShareStep(null, recordAudioGranted = true, notificationsGranted = true))
        assertEquals(ShareStep.NOTIFICATIONS, nextShareStep(null, recordAudioGranted = true, notificationsGranted = false))
    }

    @Test
    fun `a denial is non-fatal - the ladder moves on instead of asking again`() {
        // RECORD_AUDIO denied: still not granted, yet the next step is the notification prompt.
        assertEquals(ShareStep.NOTIFICATIONS, nextShareStep(ShareStep.RECORD_AUDIO, recordAudioGranted = false, notificationsGranted = false))
        // Both denied: a video-only, notification-less cast still reaches the consent.
        assertEquals(ShareStep.PROJECTION, nextShareStep(ShareStep.NOTIFICATIONS, recordAudioGranted = false, notificationsGranted = false))
    }
}
