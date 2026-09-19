package com.zerofriction.localcast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = CastPrimary,
    background = CastBackground,
    onBackground = CastOnBackground,
    surface = CastSurface,
    onSurface = CastOnSurface,
)

/** The app is intentionally dark-only (v1); a light variant is not planned. */
@Composable
fun LocalCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
