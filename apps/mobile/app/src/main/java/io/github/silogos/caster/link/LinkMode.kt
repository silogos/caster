package io.github.silogos.caster.link

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.silogos.caster.pairing.CableTrust
import io.github.silogos.caster.pairing.SavedDesktops
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The link a pairing runs on (ADR-037). */
enum class Link { Wireless, Cable }

/**
 * Which link runs (ADR-037: the link is chosen first, and only that link
 * runs) — the one place that decides it on the phone:
 *
 * - **Wireless**: the Wi-Fi pairing, signaling and WebRTC (only on Connect /
 *   Share). The cable is never opened, and Android doesn't even offer
 *   "Open Caster?" for it: the accessory entry ([CABLE_ENTRY]) is disabled.
 * - **Cable**: the accessory only. No signaling socket, no WebRTC, no
 *   WifiLock — the cast goes through UsbCastService.
 * - **None** (the choice screen): neither.
 *
 * A saved pairing decides the mode at launch (its link wins); before
 * pairing, the user's choice is kept so a plug-in that launches the app
 * finds it in Cable mode. Writing one link's pairing removes the other's.
 */
object LinkMode {
    private const val TAG = "LinkMode"
    private const val PREFS = "caster.link"
    private const val KEY_CHOSEN = "chosen"
    /** The activity-alias carrying USB_ACCESSORY_ATTACHED (AndroidManifest.xml): enabled in Cable mode only. */
    const val CABLE_ENTRY = "io.github.silogos.caster.CableEntry"

    private val _mode = MutableStateFlow<Link?>(null)
    val mode: StateFlow<Link?> = _mode.asStateFlow()

    /** Whether the current mode has its pairing (Home) or not yet (Scan / the cable guide). */
    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    /** App start: the saved pairing's link, else the user's earlier choice, else none. */
    fun hydrate(context: Context) {
        val app = context.applicationContext
        val saved = savedPairing(app)
        val chosen = saved ?: app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHOSEN, null)
            ?.let { runCatching { Link.valueOf(it) }.getOrNull() }
        set(app, chosen, paired = saved != null)
        Log.i(TAG, "mode at start: ${chosen ?: "choice"}${if (saved != null) " (paired)" else ""}")
    }

    /** The choice screen's Wireless / Cable. */
    fun choose(context: Context, link: Link) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHOSEN, link.name).apply()
        set(app, link, paired = false)
        Log.i(TAG, "$link chosen — only this link runs")
    }

    /** A pairing on [link] was just made: the other link's pairing goes (one at a time). */
    fun paired(context: Context, link: Link) {
        val app = context.applicationContext
        when (link) {
            Link.Wireless -> CableTrust(app).clear()
            Link.Cable -> SavedDesktops(app).clear()
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHOSEN, link.name).apply()
        set(app, link, paired = true)
    }

    /** Back from Scan / the guide, or the pairing ended (Terminate, the computer's `bye forget`): the choice. */
    fun backToChoice(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CHOSEN).apply()
        set(app, null, paired = false)
        Log.i(TAG, "back to the choice — nothing runs")
    }

    private fun savedPairing(app: Context): Link? = when {
        CableTrust(app).load() != null -> Link.Cable
        SavedDesktops(app).load() != null -> Link.Wireless
        else -> null
    }

    private fun set(app: Context, link: Link?, paired: Boolean) {
        _mode.value = link
        _paired.value = paired
        setCableEntry(app, link == Link.Cable)
    }

    private fun setCableEntry(app: Context, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val component = ComponentName(app, CABLE_ENTRY)
        val pm = app.packageManager
        if (pm.getComponentEnabledSetting(component) == state) return
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
