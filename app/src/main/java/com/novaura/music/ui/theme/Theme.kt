package com.novaura.music.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = VioletBrand,
    onPrimary = Color.White,
    secondary = Purple40,
    tertiary = Pink40,
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF0EAF8),
    onSurfaceVariant = Color(0xFF4F475C),
    outline = Color(0xFF807A8B),
    surfaceContainer = Color(0xFFF2ECFB),
    surfaceContainerHigh = Color(0xFFECE6F6)
)

private val DarkColorScheme = darkColorScheme(
    primary = VioletBright,
    onPrimary = Color(0xFF2A1140),
    secondary = Purple80,
    tertiary = Pink80,
    background = Color(0xFF12101A),
    onBackground = Color(0xFFF3EEF8),
    surface = Color(0xFF12101A),
    onSurface = Color(0xFFF3EEF8),
    surfaceVariant = Color(0xFF211C2B),
    onSurfaceVariant = Color(0xFFC9C1D4),
    outline = Color(0xFF958DA6),
    surfaceContainer = Color(0xFF1B1626),
    surfaceContainerHigh = Color(0xFF251F31)
)

@Composable
fun NovauraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}