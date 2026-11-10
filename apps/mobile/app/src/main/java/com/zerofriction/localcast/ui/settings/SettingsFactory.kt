package com.zerofriction.localcast.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zerofriction.localcast.config.CastSettingsStore

/**
 * The settings store, injected once. Home and the profile screens ask for
 * the same [SettingsViewModel] (one per activity), so a profile edited on
 * one screen is the profile the other shows.
 */
@Composable
internal fun settingsFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val store = CastSettingsStore(context.applicationContext)
                return SettingsViewModel(store.load(), store::save) as T
            }
        }
    }
}
