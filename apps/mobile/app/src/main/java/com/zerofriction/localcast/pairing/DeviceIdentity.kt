package com.zerofriction.localcast.pairing

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * The persistent device identity (ADR-005): a random UUID generated once per
 * install and sent as `hello`'s optional `deviceId` (pairing.md) so the
 * desktop can keep an adb-like device registry.
 *
 * Deliberately NOT derived from any hardware identifier (MAC/ANDROID_ID —
 * privacy-sensitive and unstable across resets/networks) and NOT a secret:
 * authentication remains the HMAC pairing handshake (ADR-002), and this value
 * grants nothing. Reinstalling the app legitimately creates a new identity.
 */
object DeviceIdentity {

    private const val TAG = "DeviceIdentity"
    private const val PREFS_NAME = "zfc_device"
    private const val KEY = "device_id"

    /** Stable for this install; generates (and persists) on the first call. */
    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).apply()
        Log.i(TAG, "device identity generated for this install")
        return fresh
    }
}
