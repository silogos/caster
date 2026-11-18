package io.github.silogos.caster

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.silogos.caster.link.Link
import io.github.silogos.caster.link.LinkMode
import io.github.silogos.caster.service.ConnectedDesktop
import io.github.silogos.caster.ui.home.HomeScreen
import io.github.silogos.caster.ui.link.BackLink
import io.github.silogos.caster.ui.link.CableGuideScreen
import io.github.silogos.caster.ui.link.ChoiceScreen
import io.github.silogos.caster.ui.pairing.ScanScreen
import io.github.silogos.caster.ui.profiles.ProfileEditScreen
import io.github.silogos.caster.ui.theme.CasterTheme
import io.github.silogos.caster.usb.UsbLink

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only app: always-dark system bars (light icons), never the
        // system-theme-dependent default that turns icons dark on our bg.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // ADR-037: the link is chosen first. The saved pairing (or the
        // user's earlier choice) decides the mode, and only that link runs.
        LinkMode.hydrate(this)
        // Persistent session (ADR-006): a saved desktop means Home with its
        // one-tap Connect. In Cable mode there is none (one pairing at a time).
        ConnectedDesktop.hydrateSaved(this)
        // USB cast (ADR-034): Android opened us for Caster's accessory — used only in Cable mode.
        UsbLink.attachFrom(this, intent)
        setContent {
            CasterTheme {
                // The screen follows the link mode (ADR-037): nothing chosen →
                // the choice; chosen, not paired → Scan (Wireless) or the cable
                // guide (Cable); paired → Home. Plain state-based navigation,
                // no nav library (docs/development/mobile.md: add libraries
                // when the need is real).
                val mode by LinkMode.mode.collectAsStateWithLifecycle()
                val paired by LinkMode.paired.collectAsStateWithLifecycle()
                // Which of the user's profiles the edit screen shows (ADR-024); NO_PROFILE: Home.
                var editingProfileId by rememberSaveable { mutableIntStateOf(NO_PROFILE) }
                // Wireless: a failed reconnect's "Scan QR code" shows Scan without leaving the pairing.
                var rescanning by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(mode) { if (mode == Link.Cable) UsbLink.attachFrom(this@MainActivity, null) }
                val back = { LinkMode.backToChoice(this@MainActivity) }
                when {
                    mode == null -> ChoiceScreen(onChoose = { LinkMode.choose(this@MainActivity, it) })
                    mode == Link.Wireless && (!paired || rescanning) -> {
                        BackHandler(onBack = back)
                        Box {
                            ScanScreen(onPaired = {
                                rescanning = false
                                LinkMode.paired(this@MainActivity, Link.Wireless)
                            })
                            BackLink(onBack = back, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding())
                        }
                    }
                    mode == Link.Cable && !paired -> {
                        BackHandler(onBack = back)
                        CableGuideScreen(phoneModel = android.os.Build.MODEL, onBack = back)
                    }
                    editingProfileId != NO_PROFILE -> ProfileEditScreen(
                        profileId = editingProfileId,
                        onBack = { editingProfileId = NO_PROFILE },
                        onOpenProfile = { id -> editingProfileId = id },
                    )
                    else -> HomeScreen(
                        onEditProfile = { id -> editingProfileId = id },
                        onDisconnected = back,
                        onScanAgain = { rescanning = true },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        UsbLink.attachFrom(this, intent)
    }

    override fun onResume() {
        super.onResume()
        // A cable already in (and granted) when the app comes back — Cable mode only (UsbLink checks).
        UsbLink.attachFrom(this, null)
    }

    companion object {
        /** No profile is open for editing. */
        private const val NO_PROFILE = 0
    }
}
