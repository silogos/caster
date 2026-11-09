package com.zerofriction.localcast.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Where RECORD_AUDIO stands for the mic row (review group F).
 *
 * Android has no direct "permanently denied" query: `shouldShowRequestPermissionRationale`
 * is false both before the first request and after Android stops asking
 * (Android 11+: the second denial; older: "Don't ask again"). Remembering
 * that the app has asked at least once tells the two apart.
 *
 * Known limitation: on Android 11+ dismissing the dialog without answering
 * also leaves no rationale, so the row may offer Android settings one step
 * early — settings grants it all the same.
 */
enum class MicPermission { Granted, Askable, Blocked }

internal fun micPermissionOf(granted: Boolean, askedBefore: Boolean, showRationale: Boolean): MicPermission = when {
    granted -> MicPermission.Granted
    askedBefore && !showRationale -> MicPermission.Blocked
    else -> MicPermission.Askable
}

/** The "has asked" flag — one boolean, persisted so a restart (Android kills the app on a revoke) keeps it. */
internal object MicPermissionAsks {
    private const val PREFS = "mic_permission"
    private const val KEY_ASKED = "asked"

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun current(context: Context): MicPermission {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
        val rationale = (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: false
        return micPermissionOf(granted, asked, rationale)
    }
}
