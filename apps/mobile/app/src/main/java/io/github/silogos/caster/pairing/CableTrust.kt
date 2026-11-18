package io.github.silogos.caster.pairing

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * The phone's cable pairing (usb-link.md §Cable trust, ADR-037): ONE
 * computer this phone enrolled with over the cable, its cable key sealed by
 * the [TrustVault] like the Wi-Fi entry's. Written when the enrolment's
 * `auth-ok` checks; removed by Terminate, by the computer's `bye forget`, or
 * when the computer answers `unknown-device` to the stored key.
 *
 * Unreadable storage degrades to "nothing paired" (logged): the honest
 * fallback is one more Allow on the computer, never a link on a guessed key.
 */
class CableTrust(
    context: Context,
    private val vault: TrustVault = KeystoreTrustVault(),
) {
    data class Entry(val desktopId: String, val name: String, val key: ByteArray)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Entry? {
        val document = prefs.getString(KEY, null) ?: return null
        return try {
            val json = JSONObject(document)
            val key = vault.open(json.getString("key"))
            if (key == null) {
                Log.w(TAG, "cable key failed to unseal — degrading to a new Allow")
                clear()
                return null
            }
            Entry(json.getString("desktopId"), json.optString("name", "Computer"), key)
        } catch (e: JSONException) {
            Log.w(TAG, "cable pairing failed to parse — degrading to a new Allow")
            null
        }
    }

    fun save(desktopId: String, name: String, key: ByteArray) {
        val document = JSONObject()
            .put("desktopId", desktopId)
            .put("name", name)
            .put("key", vault.seal(key))
            .put("createdAt", System.currentTimeMillis())
        prefs.edit().putString(KEY, document.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val TAG = "CableTrust"
        const val PREFS_NAME = "caster.cable-trust"
        const val KEY = "caster.cable-trust.v1"
    }
}
