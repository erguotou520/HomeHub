package me.erguotou.homehub.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = BrandBlue80,
    onPrimary = OnBrandBlue20,
    primaryContainer = BrandBlueContainer30,
    onPrimaryContainer = OnBrandBlueContainer90,
    secondary = BrandViolet80,
    onSecondary = OnBrandViolet20,
    secondaryContainer = BrandVioletContainer30,
    onSecondaryContainer = OnBrandVioletContainer90,
    tertiary = BrandPink80,
    onTertiary = OnBrandPink20,
    tertiaryContainer = BrandPinkContainer30,
    onTertiaryContainer = OnBrandPinkContainer90,
    surface = DarkSurface,
    onSurface = Color(0xFFE4E2EC),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceTint = BrandBlue80,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue40,
    onPrimary = OnBrandBlue40,
    primaryContainer = BrandBlueContainer90,
    onPrimaryContainer = OnBrandBlueContainer10,
    secondary = BrandViolet40,
    onSecondary = OnBrandViolet40,
    secondaryContainer = BrandVioletContainer90,
    onSecondaryContainer = OnBrandVioletContainer10,
    tertiary = BrandPink40,
    onTertiary = OnBrandPink40,
    tertiaryContainer = BrandPinkContainer90,
    onTertiaryContainer = OnBrandPinkContainer10,
    surface = LightSurface,
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF464654),
    surfaceTint = BrandBlue40,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

@Composable
fun HomeHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
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
