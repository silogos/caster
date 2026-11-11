package com.zerofriction.localcast

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.service.ConnectedDesktop
import com.zerofriction.localcast.ui.home.HomeScreen
import com.zerofriction.localcast.ui.pairing.ScanScreen
import com.zerofriction.localcast.ui.profiles.ProfileEditScreen
import com.zerofriction.localcast.ui.theme.LocalCastTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only app: always-dark system bars (light icons), never the
        // system-theme-dependent default that turns icons dark on our bg.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Persistent session (ADR-006): a saved desktop means the hub leads —
        // Home with its one-tap Connect. No store (or an unusable one) keeps
        // the scan-first flow.
        ConnectedDesktop.hydrateSaved(this)
        setContent {
            LocalCastTheme {
                // Scan-first (designs/mobile-app.html): a fresh launch opens on
                // the scanner — unless a pairing session is already live or
                // saved: the session/cast live in the process
                // (ConnectedDesktop / CastService statics), and the saved
                // desktop lives in the trust store — so reopening the app
                // (notification, launcher, back-then-again) lands on the Home
                // hub, never on the scanner. Plain state-based navigation, no
                // nav library (docs/development/mobile.md: add libraries when
                // the need is real).
                var screen by rememberSaveable { mutableStateOf(initialScreen()) }
                // Which of the user's profiles the edit screen shows (ADR-024).
                var editingProfileId by rememberSaveable { mutableIntStateOf(NO_PROFILE) }
                when (screen) {
                    SCREEN_SCAN -> ScanScreen(onPaired = { screen = SCREEN_HOME })
                    SCREEN_HOME -> HomeScreen(
                        onEditProfile = { id ->
                            editingProfileId = id
                            screen = SCREEN_PROFILE
                        },
                        onDisconnected = { screen = SCREEN_SCAN },
                    )

                    else -> ProfileEditScreen(
                        profileId = editingProfileId,
                        onBack = { screen = SCREEN_HOME },
                        onOpenProfile = { id -> editingProfileId = id },
                    )
                }
            }
        }
    }

    /**
     * The real first screen: Home when the process still holds a pairing
     * session (connected hub, a cast starting/running, or a cast failure
     * with the session retained) — the scanner otherwise. `rememberSaveable`
     * only runs this on a genuinely fresh launch; rotation and config changes
     * keep the current screen.
     */
    private fun initialScreen(): String {
        val connected = ConnectedDesktop.state.value is ConnectedDesktop.State.Connected
        val saved = ConnectedDesktop.state.value is ConnectedDesktop.State.Saved
        val casting = CastService.state.value is CastState.Starting || CastService.state.value is CastState.Casting
        return if (connected || saved || casting) SCREEN_HOME else SCREEN_SCAN
    }

    companion object {
        private const val SCREEN_SCAN = "scan"
        private const val SCREEN_HOME = "home"
        private const val SCREEN_PROFILE = "profile"

        /** No profile is open for editing. */
        private const val NO_PROFILE = 0
    }
}
