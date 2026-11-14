package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val NekoDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF002336),
    primaryContainer = CyanContainerDark,
    onPrimaryContainer = OnCyanContainerDark,
    secondary = RealityViolet,
    onSecondary = Color(0xFF1E0B4E),
    secondaryContainer = RealityContainerDark,
    onSecondaryContainer = OnRealityContainerDark,
    tertiary = SignalEmerald,
    onTertiary = Color(0xFF002819),
    background = CyberObsidian,
    onBackground = TextPrimaryDark,
    surface = CyberSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = CyberSurfaceHigh,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLow = CyberSurface,
    surfaceContainer = CyberSurfaceHigh,
    surfaceContainerHigh = CyberSurfaceHighest,
    outline = CyberOutline,
    error = SignalCrimson,
    onError = Color(0xFF3E0012),
)

private val NekoLightColorScheme = lightColorScheme(
    primary = DeepCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = RealityVioletDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF4C1D95),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    background = LightBg,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = LightOutline,
    error = Color(0xFFE11D48),
    onError = Color.White,
)

private val NekoShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NekoXrayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> NekoDarkColorScheme
        else -> NekoLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NekoTypography,
        shapes = NekoShapes,
        content = content,
    )
}
