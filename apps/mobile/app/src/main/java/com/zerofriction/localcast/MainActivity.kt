package com.zerofriction.localcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.zerofriction.localcast.ui.home.HomeScreen
import com.zerofriction.localcast.ui.pairing.ScanScreen
import com.zerofriction.localcast.ui.settings.CastSettingsScreen
import com.zerofriction.localcast.ui.theme.LocalCastTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalCastTheme {
                // Scan-first (designs/mobile-app.html): the app opens on the
                // scanner, a successful pairing moves to the Home hub, and the
                // gear opens the cast settings. Plain state-based navigation,
                // no nav library (docs/development/mobile.md: add libraries
                // when the need is real).
                var screen by rememberSaveable { mutableStateOf(SCREEN_SCAN) }
                when (screen) {
                    SCREEN_SCAN -> ScanScreen(onPaired = { screen = SCREEN_HOME })
                    SCREEN_HOME -> HomeScreen(
                        onOpenSettings = { screen = SCREEN_SETTINGS },
                        onDisconnected = { screen = SCREEN_SCAN },
                    )

                    else -> CastSettingsScreen(onBack = { screen = SCREEN_HOME })
                }
            }
        }
    }

    companion object {
        private const val SCREEN_SCAN = "scan"
        private const val SCREEN_HOME = "home"
        private const val SCREEN_SETTINGS = "settings"
    }
}
