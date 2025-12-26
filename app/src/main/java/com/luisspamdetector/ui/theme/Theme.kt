package com.luisspamdetector.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Colores principales
val Primary = Color(0xFF4ecca3)
val PrimaryVariant = Color(0xFF3da88a)
val Secondary = Color(0xFF0f3460)
val Background = Color(0xFF1a1a2e)
val Surface = Color(0xFF16213e)
val Error = Color(0xFFe74c3c)
val OnPrimary = Color.White
val OnSecondary = Color.White
val OnBackground = Color.White
val OnSurface = Color.White
val OnError = Color.White

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryVariant,
    secondary = Secondary,
    onSecondary = OnSecondary,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    error = Error,
    onError = OnError,
    surfaceVariant = Color(0xFF232946),
    outline = Color(0xFF394867)
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryVariant,
    secondary = Secondary,
    onSecondary = OnSecondary,
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1a1a2e),
    surface = Color.White,
    onSurface = Color(0xFF1a1a2e),
    error = Error,
    onError = OnError,
    surfaceVariant = Color(0xFFE8E8E8),
    outline = Color(0xFFBDBDBD)
)

@Composable
fun LinphoneSpamDetectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Deshabilitado para mantener branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

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

val Typography = Typography()
