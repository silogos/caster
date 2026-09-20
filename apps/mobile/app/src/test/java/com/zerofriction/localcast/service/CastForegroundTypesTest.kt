package com.zerofriction.localcast.service

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavior tests for the cast FGS's type set (mobile.md): `mediaProjection`
 * always — the cover that keeps screen + game-audio capture alive while the
 * app is backgrounded — plus `microphone` exactly while the mic session is
 * on, from the API level where the type exists (found live on Android16: a
 * mic without the type is muted by the system the moment the app leaves the
 * foreground). The constants are compile-time inlined, so this runs in plain
 * JVM; production passes Build.VERSION.SDK_INT.
 */
class CastForegroundTypesTest {

    @Test
    fun `mic off is mediaProjection only`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            CastForegroundTypes.types(micOn = false, sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM),
        )
    }

    @Test
    fun `mic on adds the microphone type from API30 up`() {
        val expected =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        assertEquals(expected, CastForegroundTypes.types(micOn = true, sdkInt = Build.VERSION_CODES.R))
        assertEquals(expected, CastForegroundTypes.types(micOn = true, sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM))
    }

    @Test
    fun `API29 has no microphone type — background mic restriction starts at API30`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            CastForegroundTypes.types(micOn = true, sdkInt = Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun `below API29 there is no typed start at all`() {
        assertEquals(0, CastForegroundTypes.types(micOn = false, sdkInt = Build.VERSION_CODES.P))
        assertEquals(0, CastForegroundTypes.types(micOn = true, sdkInt = Build.VERSION_CODES.P))
    }
}
