package com.zerofriction.localcast.service

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * The Wi-Fi radio hold (issue #86): held for exactly the cast's lifetime,
 * released on every teardown path. Keeps the radio out of aggressive
 * power-save while the caster app sits in the background during a game —
 * one half of surviving transient mid-game UDP outages (the other half is
 * the media session's ICE-restart ladder).
 *
 * `WIFI_MODE_FULL_LOW_LATENCY` (minSdk 29 makes it always available) — the
 * strongest hold, and the only effective one on Android 14+, where the
 * classic `WIFI_MODE_FULL_HIGH_PERF` is treated as plain FULL.
 * Non-reference-counted: one acquire, one release.
 */
internal class CastWifiLock(private val context: Context) {
    private var lock: WifiManager.WifiLock? = null

    fun acquire() {
        if (lock !== null) return
        val wifiManager = context.getSystemService(WifiManager::class.java) ?: run {
            Log.w(TAG, "no WifiManager — casting without the radio hold")
            return
        }
        val newLock = wifiManager
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, LOCK_TAG)
            .apply { setReferenceCounted(false) }
        try {
            newLock.acquire()
            lock = newLock
            Log.i(TAG, "wifi lock acquired (low-latency) for the cast")
        } catch (t: Throwable) {
            Log.w(TAG, "wifi lock acquire failed — casting without it: ${t.message}")
        }
    }

    fun release() {
        val held = lock ?: return
        lock = null
        if (held.isHeld) {
            held.release()
            Log.i(TAG, "wifi lock released")
        }
    }

    private companion object {
        const val TAG = "CastService"

        /** The radio hold's name in system dumps (issue #86). */
        const val LOCK_TAG = "localcast:cast"
    }
}
