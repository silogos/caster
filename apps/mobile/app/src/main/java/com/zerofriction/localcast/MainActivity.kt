package com.zerofriction.localcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zerofriction.localcast.ui.home.HomeScreen
import com.zerofriction.localcast.ui.theme.LocalCastTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalCastTheme {
                HomeScreen()
            }
        }
    }
}
