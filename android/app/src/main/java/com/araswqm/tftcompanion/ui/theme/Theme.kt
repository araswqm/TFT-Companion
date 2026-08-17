package com.araswqm.tftcompanion.ui.theme

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

// Anahtarlığın koyu temasıyla uyumlu özel renk paleti
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE94560),      // aksan kırmızı
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0F3460),    // lacivert
    background = Color(0xFF1A1A2E),   // koyu lacivert zemin
    onBackground = Color(0xFFEAEAEA),
    surface = Color(0xFF23233C),
    onSurface = Color(0xFFEAEAEA),
    surfaceVariant = Color(0xFF2B2B4A),
    onSurfaceVariant = Color(0xFFB8B8CC),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE94560),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0F3460),
    background = Color(0xFFF6F6FA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun TftCompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
