package com.serkantkn.synapse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalAppleColors = staticCompositionLocalOf { DarkAppleColors }

/** Shortcut for the Apple palette of the current theme. */
object Apple {
    val colors: AppleColors
        @Composable get() = LocalAppleColors.current
}

/**
 * Apple-style theme shared with the iOS and macOS apps. No Material You dynamic colour:
 * the app uses the same fixed palette on every phone so it matches its Apple siblings.
 */
@Composable
fun SynapseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val apple = if (darkTheme) DarkAppleColors else LightAppleColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = apple.blue,
            onPrimary = Color.White,
            secondary = SynapseCyan,
            tertiary = SynapseIndigo,
            background = Color(0xFF0B0B0C),
            surface = Color(0xFF1C1C1E),
            onBackground = apple.label,
            onSurface = apple.label,
            onSurfaceVariant = apple.secondaryLabel,
            error = apple.red,
        )
    } else {
        lightColorScheme(
            primary = apple.blue,
            onPrimary = Color.White,
            secondary = SynapseCyan,
            tertiary = SynapseIndigo,
            background = Color(0xFFF2F2F7),
            surface = Color.White,
            onBackground = apple.label,
            onSurface = apple.label,
            onSurfaceVariant = apple.secondaryLabel,
            error = apple.red,
        )
    }

    CompositionLocalProvider(LocalAppleColors provides apple) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
