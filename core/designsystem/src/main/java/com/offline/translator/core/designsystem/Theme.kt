package com.offline.translator.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ForestNight,
    onPrimary = ForestNightInk,
    primaryContainer = ForestNightContainer,
    onPrimaryContainer = ForestNightContainerInk,
    secondary = SandNight,
    onSecondary = SandNightContainer,
    secondaryContainer = SandNightContainer,
    onSecondaryContainer = SandNightContainerInk,
    background = PaperNight,
    onBackground = InkNight,
    surface = CardNight,
    onSurface = InkNight,
    surfaceVariant = MistNight,
    onSurfaceVariant = MutedNight,
    tertiary = ApricotNight,
    onTertiary = ApricotNightContainer,
    tertiaryContainer = ApricotNightContainer,
    onTertiaryContainer = ApricotNightContainerInk,
    outline = OutlineNight,
    outlineVariant = MistNight,
    surfaceContainerLowest = PaperNight,
    surfaceContainerLow = CardNight,
    surfaceContainer = Color(0xFF1E3032),
    surfaceContainerHigh = Color(0xFF263A3C),
    surfaceContainerHighest = MistNight,
)

private val LightColorScheme = lightColorScheme(
    primary = ForestDay,
    onPrimary = ForestDayInk,
    primaryContainer = ForestDayContainer,
    onPrimaryContainer = ForestDayContainerInk,
    secondary = SandDay,
    onSecondary = ForestDayInk,
    secondaryContainer = SandDayContainer,
    onSecondaryContainer = SandDayContainerInk,
    background = PaperDay,
    onBackground = InkDay,
    surface = CardDay,
    onSurface = InkDay,
    surfaceVariant = MistDay,
    onSurfaceVariant = MutedDay,
    tertiary = ApricotDay,
    onTertiary = ForestDayInk,
    tertiaryContainer = ApricotDayContainer,
    onTertiaryContainer = ApricotDayContainerInk,
    outline = OutlineDay,
    outlineVariant = Color(0xFFC8D4C7),
    surfaceContainerLowest = CardDay,
    surfaceContainerLow = Color(0xFFF6F1E5),
    surfaceContainer = Color(0xFFF0ECE0),
    surfaceContainerHigh = Color(0xFFECE8DA),
    surfaceContainerHighest = MistDay,
)

@Composable
fun OfflineTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
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

    val resolvedColors = if (amoled && darkTheme) colorScheme.copy(
        background = Color.Black,
        surface = Color(0xFF080808),
        surfaceVariant = Color(0xFF1A1A1D),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF080808),
        surfaceContainer = Color(0xFF101012),
        surfaceContainerHigh = Color(0xFF18181B),
        surfaceContainerHighest = Color(0xFF222225),
    ) else colorScheme

    MaterialTheme(
        colorScheme = resolvedColors,
        typography = Typography,
        content = content
    )
}
