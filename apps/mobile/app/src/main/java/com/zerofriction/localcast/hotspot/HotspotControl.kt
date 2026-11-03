package com.zerofriction.localcast.hotspot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

/**
 * The platform half of hotspot pairing mode (issue #96, ADR-011): wraps
 * `WifiManager.startLocalOnlyHotspot` — a local AP that shares NO internet
 * (no mobile data flows; the LAN-only posture holds) — behind the pure
 * [HotspotMachine]. App-scoped object (the ConnectedDesktop precedent): the
 * hotspot outlives screen navigation because the guided-manual flow walks
 * between Home (credentials) and the scanner (the desktop's fresh QR).
 *
 * Honest platform caveats, documented in ADR-011/R11: the OS can revoke the
 * hotspot at any moment (onStopped); SSID/password exist only while the
 * reservation is held; single-radio devices drop their own Wi-Fi while
 * hosting (the intended case — there is no shared network); permission and
 * behavior vary per OEM — every failure lands in a plain-words state.
 */
object HotspotControl {

    private const val TAG = "HotspotControl"

    private val machine = HotspotMachine()
    val state: StateFlow<HotspotMachine.State> = machine.state

    private var wifi: WifiManager? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * The runtime permission this OS version ties to local-only hotspots:
     * NEARBY_WIFI_DEVICES on 13+ (the Wi-Fi API migration), location below.
     * An OEM demanding more surfaces as an honest failure (R11 watch list).
     */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    /**
     * Start (or retry after a failure). No permission yet → the machine asks
     * for it ([State.NeedPermission]) and the UI launches the system prompt;
     * the grant arrives in [permissionResult].
     */
    fun start(context: Context) {
        val app = context.applicationContext
        wifi = app.getSystemService(WifiManager::class.java)
        machine.startRequested(hasPermission(app))
        if (machine.state.value == HotspotMachine.State.Starting) requestReservation(app)
    }

    /** The UI's permission prompt resolved — a grant proceeds to the reservation. */
    fun permissionResult(granted: Boolean, context: Context) {
        val app = context.applicationContext
        machine.permissionResult(granted)
        if (machine.state.value == HotspotMachine.State.Starting) requestReservation(app)
    }

    /** Explicit stop — the only normal exit (the cast runs over the hotspot). */
    fun stop() {
        machine.stopRequested()
        closeReservation()
    }

    @SuppressLint("MissingPermission") // the machine only reaches Starting with the grant
    private fun requestReservation(context: Context) {
        val manager = wifi
        if (manager === null) {
            machine.startFailed(HotspotMachine.Reason.UNKNOWN)
            return
        }
        try {
            // The API was awkwardly versioned: SDK 33+ exposes only the
            // (callback, Handler) overload — which does not exist on API 29–32
            // runtimes — while the API-26 single-arg method is hidden from
            // newer compile SDKs but alive on every device. Dispatch by
            // version; reflection below 33 is the only cross-version path.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
            } else {
                WifiManager::class.java
                    .getMethod("startLocalOnlyHotspot", WifiManager.LocalOnlyHotspotCallback::class.java)
                    .invoke(manager, callback)
            }
        } catch (e: SecurityException) {
            // An OEM permission quirk beyond the documented set — honest failure.
            Log.w(TAG, "hotspot start rejected: ${e.message}")
            machine.startFailed(HotspotMachine.Reason.UNKNOWN)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "hotspot start blocked by state: ${e.message}")
            machine.startFailed(HotspotMachine.Reason.INCOMPATIBLE_MODE)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "hotspot start method missing: ${e.message}")
            machine.startFailed(HotspotMachine.Reason.NOT_SUPPORTED)
        }
    }

    private val callback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
            this@HotspotControl.reservation = reservation
            val credentials = readCredentials(reservation)
            if (credentials === null) {
                // A reservation without readable credentials is useless — close it honestly.
                closeReservation()
                machine.startFailed(HotspotMachine.Reason.UNKNOWN)
                return
            }
            machine.started(credentials.first, credentials.second)
        }

        override fun onFailed(reason: Int) {
            Log.w(TAG, "hotspot onFailed reason=$reason")
            machine.startFailed(
                when (reason) {
                    WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                        HotspotMachine.Reason.NOT_SUPPORTED

                    WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> HotspotMachine.Reason.NO_CHANNEL
                    WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> HotspotMachine.Reason.INCOMPATIBLE_MODE
                    else -> HotspotMachine.Reason.UNKNOWN // incl. ERROR_GENERIC
                }
            )
        }

        override fun onStopped() {
            // Documented OS behavior: the hotspot can be torn down at will.
            machine.osRevoked()
            closeReservation()
        }
    }

    /**
     * SSID + passphrase are readable only while the reservation is held.
     * API 30+: SoftApConfiguration; API 29: the deprecated WifiConfiguration
     * (its SSID may arrive quoted — strip it).
     */
    private fun readCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation
    ): Pair<String, String>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = reservation.softApConfiguration ?: return null
            val ssid = config.ssid ?: return null
            val passphrase = config.passphrase ?: return null
            if (ssid.isEmpty() || passphrase.isEmpty()) return null
            return ssid to passphrase
        }
        @Suppress("DEPRECATION")
        val config = reservation.wifiConfiguration ?: return null
        val ssid = config.SSID?.removeSurrounding("\"") ?: return null
        val passphrase = config.preSharedKey ?: return null
        if (ssid.isEmpty() || passphrase.isEmpty()) return null
        return ssid to passphrase
    }

    private fun closeReservation() {
        try {
            reservation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "reservation close threw: ${e.message}")
        }
        reservation = null
    }
}
