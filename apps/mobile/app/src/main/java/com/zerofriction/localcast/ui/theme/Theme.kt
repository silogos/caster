package com.zerofriction.localcast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = CastPrimary,
    onPrimary = CastOnPrimary,
    background = CastBackground,
    onBackground = CastOnBackground,
    surface = CastSurface,
    onSurface = CastOnSurface,
    onSurfaceVariant = CastOnSurfaceVariant,
    outline = CastOutline,
    error = CastError,
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
