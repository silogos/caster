package com.zerofriction.localcast.pairing

import android.content.Context
import android.util.Log
import com.zerofriction.localcast.signaling.SignalingClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The phone's half of the persistent session (pairing.md §Persistent
 * sessions, ADR-006): ONE saved desktop, written automatically on every
 * successful pairing that the desktop identified itself in (auth-ok
 * `desktopId`) and kept until a Disconnect on either side — the phone
 * reconnects with one tap via `hello {resume}` + the sealed trustKey.
 *
 * One JSON document in SharedPreferences (same convention as
 * CastSettingsStore): corrupt or wrong-shaped storage degrades to "nothing
 * saved" with a logged warning — the honest fallback is a QR re-scan, never a
 * cast on guessed values. The trustKey itself is sealed by a [TrustVault]
 * (Android Keystore in production), so the persisted document alone cannot
 * yield the resume key.
 */
class SavedDesktops(
    context: Context,
    private val vault: TrustVault = KeystoreTrustVault(),
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved desktop, or null when nothing (usable) is stored. */
    fun load(): SavedDesktop? {
        val document = prefs.getString(KEY, null) ?: return null
        val saved = try {
            decodeDocument(document)
        } catch (e: Exception) {
            Log.w(TAG, "saved desktop failed to parse (${e.javaClass.simpleName}) — degrading to re-scan")
            null
        } ?: return null
        // A seal the vault cannot open is a dead session — drop it honestly.
        if (vault.open(saved.sealedTrustKey) == null) {
            Log.w(TAG, "saved trustKey failed to unseal — degrading to re-scan")
            clear()
            return null
        }
        return saved
    }

    /** The resume key for a stored entry — null when the vault cannot open it. */
    fun trustKeyOf(entry: SavedDesktop): ByteArray? = vault.open(entry.sealedTrustKey)

    /** Save/replace the saved desktop from a first pairing (fresh sealed key). */
    fun save(desktopId: String, name: String, hosts: List<String>, port: Int, trustKey: ByteArray) {
        val now = System.currentTimeMillis() / 1000
        val previous = load()
        prefs.edit()
            .putString(
                KEY,
                encodeDocument(
                    SavedDesktop(
                        desktopId = desktopId,
                        name = name,
                        hosts = hosts,
                        port = port,
                        sealedTrustKey = vault.seal(trustKey),
                        savedAtUnixSeconds = previous?.savedAtUnixSeconds ?: now,
                        lastConnectedAtUnixSeconds = now,
                    )
                )
            )
            .apply()
    }

    /** Refresh name/hosts/port after a successful reconnection — the key is untouched. */
    fun updateAfterConnection(name: String, hosts: List<String>, port: Int) {
        val saved = load() ?: return
        if (saved.name == name && saved.hosts == hosts && saved.port == port) return
        prefs.edit()
            .putString(
                KEY,
                encodeDocument(
                    saved.copy(
                        name = name,
                        hosts = hosts,
                        port = port,
                        lastConnectedAtUnixSeconds = System.currentTimeMillis() / 1000
                    )
                )
            )
            .apply()
    }

    /** Forget the saved desktop (Disconnect, desktop forget, corrupted state). */
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun encodeDocument(entry: SavedDesktop): String = encodeSavedDesktop(entry)

    private fun decodeDocument(document: String): SavedDesktop? = decodeSavedDesktop(document)

    private companion object {
        const val TAG = "SavedDesktops"
        const val PREFS_NAME = "zfc.saved-desktop"
        const val KEY = "zfc.saved-desktop.v1"
    }
}

/**
 * The stored document's codec (same testability convention as
 * CastSettingsCodec): one versioned JSON object, strict shape, wrong-shaped
 * input degrades to null — never a session on guessed values.
 */
internal fun encodeSavedDesktop(entry: SavedDesktop): String {
    val document: JsonObject = buildJsonObject {
        put("version", 1)
        put("desktopId", entry.desktopId)
        put("name", entry.name)
        put("hosts", JsonArray(entry.hosts.map { JsonPrimitive(it) }))
        put("port", entry.port)
        put("key", entry.sealedTrustKey)
        put("savedAt", entry.savedAtUnixSeconds)
        put("lastConnectedAt", entry.lastConnectedAtUnixSeconds)
    }
    return document.toString()
}

internal fun decodeSavedDesktop(document: String): SavedDesktop? {
    return try {
        val root = Json.parseToJsonElement(document).jsonObject
        if (root["version"]?.jsonPrimitive?.intOrNull != 1) return null
        val desktopId = root["desktopId"]?.jsonPrimitive?.content ?: return null
        if (desktopId.isEmpty() || desktopId.length > 128) return null
        val name = root["name"]?.jsonPrimitive?.content ?: return null
        val hosts = root["hosts"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return null
        if (hosts.isEmpty()) return null
        val port = root["port"]?.jsonPrimitive?.intOrNull ?: return null
        if (port !in 1..65535) return null
        val key = root["key"]?.jsonPrimitive?.content ?: return null
        if (key.isEmpty()) return null
        val savedAt = root["savedAt"]?.jsonPrimitive?.intOrNull?.toLong() ?: return null
        val lastConnectedAt = root["lastConnectedAt"]?.jsonPrimitive?.intOrNull?.toLong() ?: return null
        SavedDesktop(desktopId, name, hosts, port, key, savedAt, lastConnectedAt)
    } catch (_: Exception) {
        null
    }
}

/** What the phone remembers about its one paired desktop. */
data class SavedDesktop(
    /** The desktop's persistent UUID (auth-ok `desktopId`) — display keying, never a secret. */
    val desktopId: String,
    /** Human name (auth-ok `name`, the desktop's hostname) — shown on the Connect button's hero. */
    val name: String,
    /** Last-known LAN candidates, refreshed on every successful connection. */
    val hosts: List<String>,
    /** Last-known signaling port. */
    val port: Int,
    /** Vault-sealed trustKey — the resume HMAC key, unusable without the vault. */
    val sealedTrustKey: String,
    val savedAtUnixSeconds: Long,
    val lastConnectedAtUnixSeconds: Long,
)

/**
 * Persist a [SignalingClient.Event.TrustGrant] under one rule: a grant with a
 * derived key is a first pairing (full save); without one it is a resume
 * re-auth (refresh the identity fields only).
 */
fun SavedDesktops.applyGrant(grant: SignalingClient.Event.TrustGrant, desktopName: String) {
    val key = grant.trustKey
    if (key != null && grant.desktopId != null) {
        save(grant.desktopId, desktopName, grant.hosts, grant.port, key)
    } else {
        updateAfterConnection(desktopName, grant.hosts, grant.port)
    }
}
