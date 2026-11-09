package com.zerofriction.localcast.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.pairing.DeviceIdentity
import com.zerofriction.localcast.pairing.KeystoreTrustVault
import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.pairing.SavedDesktops
import com.zerofriction.localcast.pairing.applyGrant
import com.zerofriction.localcast.pairing.defaultSignalingScheduler
import com.zerofriction.localcast.pairing.defaultTransportFactory
import com.zerofriction.localcast.ui.pairing.defaultUserAgent
import com.zerofriction.localcast.ui.settings.SettingsViewModel

/**
 * The Home's ViewModels, injected once (ADR-006): the saved-desktop store
 * (Keystore-sealed) and the resume pairing machine — the machine writes every
 * granted/refreshed desktop identity straight into the store.
 */
@Composable
internal fun homeFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val store = SavedDesktops(appContext, KeystoreTrustVault())
                return HomeViewModel(
                    savedDesktops = store,
                    pairingClient = PairingClient(
                        ::defaultTransportFactory,
                        ::defaultSignalingScheduler,
                        ::defaultUserAgent,
                        deviceIdProvider = { DeviceIdentity.id(appContext) },
                        onDesktopIdentity = { name, grant -> store.applyGrant(grant, name) },
                    ),
                ) as T
            }
        }
    }
}

/** The settings store, injected once — the Home's audio rows persist through it. */
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
