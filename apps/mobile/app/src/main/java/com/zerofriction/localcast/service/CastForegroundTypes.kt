package com.zerofriction.localcast.service

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * The cast foreground service's type set (docs/architecture/mobile.md):
 * `mediaProjection` always — it is what keeps screen and game-audio capture
 * alive while the app is backgrounded — plus `microphone` exactly while the
 * mic session is on.
 *
 * Found live on Android16 (2026-09-20): with only the mediaProjection type,
 * the system silences the microphone the moment the app leaves the foreground
 * — screen and game audio kept playing because mediaProjection covers them,
 * while the mic went silent (Android11+ restricts background microphone
 * access to a `microphone`-typed foreground service; audio.md).
 *
 * The `microphone` bit is added only when the mic is on — callers reach this
 * with RECORD_AUDIO granted only (Android14+ throws starting a
 * microphone-typed service without it), and only from API30, where the type
 * constant exists. Below API29 there is no typed start at all (minSdk29
 * makes that branch defensive, as everywhere else in the service).
 */
object CastForegroundTypes {

    fun types(micOn: Boolean, sdkInt: Int): Int {
        if (sdkInt < Build.VERSION_CODES.Q) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (micOn && sdkInt >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }
}
