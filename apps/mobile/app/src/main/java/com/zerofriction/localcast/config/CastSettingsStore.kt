package com.zerofriction.localcast.config

import android.content.Context
import android.util.Log

/**
 * Persistence for [CastSettings] (Phase10): a single JSON document in
 * SharedPreferences, written on every settings change and read at cast
 * start (ScanScreen) — the settings screen's "changes take effect on the
 * next cast" semantics. Corrupt or unknown-version storage degrades to
 * defaults with a logged warning (never a cast on guessed values).
 */
class CastSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CastSettings {
        val document = prefs.getString(KEY, null) ?: return CastSettings.default()
        val settings = CastSettingsCodec.decode(document)
        if (settings === null) {
            Log.w(TAG, "stored cast settings failed validation — falling back to defaults")
            return CastSettings.default()
        }
        return settings
    }

    fun save(settings: CastSettings) {
        prefs.edit().putString(KEY, CastSettingsCodec.encode(settings)).apply()
    }

    companion object {
        private const val TAG = "CastSettingsStore"
        private const val PREFS_NAME = "zfc.cast-settings"
        private const val KEY = "zfc.cast-settings.v${CastSettingsCodec.VERSION}"
    }
}
