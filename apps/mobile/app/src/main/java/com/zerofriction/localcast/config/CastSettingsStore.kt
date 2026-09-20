package com.zerofriction.localcast.config

import android.content.Context
import android.util.Log

/**
 * Persistence for [CastSettings] (Phase10): a single JSON document in
 * SharedPreferences, written on every settings change and read at cast
 * start (ScanScreen) — the settings screen's "changes take effect on the
 * next cast" semantics. Corrupt or unknown-version storage degrades to
 * defaults with a logged warning (never a cast on guessed values).
 *
 * The document lives under a key per codec version — so stored settings must
 * migrate across version bumps: [load] falls back to the newest legacy key
 * that still decodes and writes it forward (found in Phase12 review: the
 * Phase11 codec bump would otherwise have silently reset every user's
 * settings, stranding the codec's own legacy decode ladder).
 */
class CastSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CastSettings {
        val document = prefs.getString(KEY, null)
        if (document !== null) {
            val settings = CastSettingsCodec.decode(document)
            if (settings !== null) return settings
            Log.w(TAG, "stored cast settings failed validation — falling back to defaults")
            return CastSettings.default()
        }
        for (legacyKey in legacyKeys.asReversed()) {
            val legacyDocument = prefs.getString(legacyKey, null) ?: continue
            val settings = CastSettingsCodec.decode(legacyDocument)
            if (settings !== null) {
                save(settings)
                Log.i(TAG, "migrated stored settings from $legacyKey")
                return settings
            }
        }
        return CastSettings.default()
    }

    fun save(settings: CastSettings) {
        prefs.edit().putString(KEY, CastSettingsCodec.encode(settings)).apply()
    }

    companion object {
        private const val TAG = "CastSettingsStore"
        private const val PREFS_NAME = "zfc.cast-settings"
        private const val KEY_PREFIX = "zfc.cast-settings.v"
        private const val KEY = "$KEY_PREFIX${CastSettingsCodec.VERSION}"

        /** Documents of every codec version below the current one. */
        private val legacyKeys = (1 until CastSettingsCodec.VERSION).map { "$KEY_PREFIX$it" }
    }
}
