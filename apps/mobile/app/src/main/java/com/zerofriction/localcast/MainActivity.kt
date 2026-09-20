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
import com.zerofriction.localcast.ui.theme.LocalCastTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalCastTheme {
                // Two screens so far — plain state-based navigation, no nav library
                // (docs/development/mobile.md: add libraries when the need is real).
                var showScan by rememberSaveable { mutableStateOf(false) }
                if (showScan) {
                    ScanScreen()
                } else {
                    HomeScreen(onStartCast = { showScan = true })
                }
            }
        }
    }
}
