package io.github.silogos.caster.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.silogos.caster.pairing.DeviceIdentity
import io.github.silogos.caster.pairing.KeystoreTrustVault
import io.github.silogos.caster.pairing.PairingClient
import io.github.silogos.caster.pairing.SavedDesktops
import io.github.silogos.caster.pairing.applyGrant
import io.github.silogos.caster.pairing.defaultSignalingScheduler
import io.github.silogos.caster.pairing.defaultTransportFactory
import io.github.silogos.caster.ui.pairing.defaultUserAgent

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
