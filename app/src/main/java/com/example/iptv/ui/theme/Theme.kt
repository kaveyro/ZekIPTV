package com.example.iptv.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Azure,
    onPrimary = AzureDeep,
    primaryContainer = NightSelected,
    onPrimaryContainer = TextBright,
    secondary = AccentGlow,
    onSecondary = AzureDeep,
    secondaryContainer = NightSelected,
    onSecondaryContainer = TextBright,
    tertiary = Amber,
    onTertiary = AzureDeep,
    background = NightBackground,
    onBackground = TextBright,
    surface = NightSurface,
    onSurface = TextBright,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = TextMuted,
    outline = TextMuted,
    error = SoftRed,
    onError = AzureDeep
)

private val LightColorScheme = lightColorScheme(
    primary = AzureDay,
    onPrimary = DaySurface,
    primaryContainer = DaySelected,
    onPrimaryContainer = TextDark,
    secondary = AzureDay,
    onSecondary = DaySurface,
    secondaryContainer = DaySelected,
    onSecondaryContainer = TextDark,
    tertiary = AmberDay,
    onTertiary = DaySurface,
    background = DayBackground,
    onBackground = TextDark,
    surface = DaySurface,
    onSurface = TextDark,
    surfaceVariant = DaySurfaceVariant,
    onSurfaceVariant = TextMutedDay,
    outline = TextMutedDay,
    error = RedDay,
    onError = DaySurface
)

@Composable
fun IptvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
