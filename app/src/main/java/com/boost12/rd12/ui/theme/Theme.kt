package com.boost12.rd12.ui.theme

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
import com.boost12.rd12.data.adb.crypto.AppThemeMode

private val Boost12DarkColors = darkColorScheme(
    primary = ElectricMint,
    onPrimary = OnElectricMint,
    primaryContainer = ElectricMintContainer,
    onPrimaryContainer = ElectricMint,
    secondary = ElectricMint,
    onSecondary = OnElectricMint,
    secondaryContainer = ObsidianSurfaceElevated,
    onSecondaryContainer = Color(0xFFD6E2F0),
    background = ObsidianBackground,
    onBackground = Color(0xFFECF2F8),
    surface = ObsidianSurface,
    onSurface = Color(0xFFECF2F8),
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = Color(0xFF98A6B8),
    outline = ObsidianOutline,
    error = Color(0xFFFF7A85),
    errorContainer = ErrorSubtleBg,
    onErrorContainer = ErrorSubtleText
)

private val Boost12LightColors = lightColorScheme(
    primary = MintLightPrimary,
    onPrimary = Color.White,
    primaryContainer = MintLightContainer,
    onPrimaryContainer = Color(0xFF042919),
    secondary = MintLightPrimary,
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = Color(0xFF1F2937),
    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF4B5563),
    outline = LightOutline,
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B)
)

val Boost12Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun Boost12Theme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> Boost12DarkColors
        else -> Boost12LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Boost12Typography,
        shapes = Boost12Shapes,
        content = content
    )
}
