package com.tombstonex.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------
 *  Frost Terminal 暗色主题 (M3 Dark)
 * ------------------------------------------------------------------ */

// ---- Frost Terminal 暗色主题 (M3 Dark) ----
private val FrostBg = Color(0xFF121212)
private val FrostSurface = Color(0xFF1C1B1F)
private val FrostSurface2 = Color(0xFF242329)
private val FrostSurface3 = Color(0xFF2B2930)
private val FrostSurface4 = Color(0xFF333138)
private val FrostSurface5 = Color(0xFF3B3942)

private val FrostPrimary = Color(0xFF00E5FF)
private val FrostOnPrimary = Color(0xFF003543)
private val FrostPrimaryContainer = Color(0x1F00E5FF) // 12% opacity
private val FrostOnPrimaryContainer = Color(0xFFD1E4FF)

private val FrostSecondary = Color(0xFFFFB347)
private val FrostOnSecondary = Color(0xFF3D2600)
private val FrostSecondaryContainer = Color(0x1FFFB347) // 12% opacity
private val FrostOnSecondaryContainer = Color(0xFFFFDEB8)

private val FrostTertiary = Color(0xFFCAC4D0)
private val FrostOnTertiary = Color(0xFF2B2930)
private val FrostTertiaryContainer = Color(0x14CAC4D0) // 8% opacity
private val FrostOnTertiaryContainer = Color(0xFFE6E1E5)

private val FrostError = Color(0xFFFF453A)
private val FrostOnError = Color(0xFF690005)
private val FrostErrorContainer = Color(0x1FFF453A) // 12% opacity
private val FrostOnErrorContainer = Color(0xFFFFDAD6)

private val FrostBackground = Color(0xFF121212)
private val FrostOnBackground = Color(0xFFE6E1E5)
private val FrostSurfaceColor = Color(0xFF1C1B1F)
private val FrostOnSurface = Color(0xFFE6E1E5)
private val FrostSurfaceVariant = Color(0xFF49454F)
private val FrostOnSurfaceVariant = Color(0xFFCAC4D0)
private val FrostOutline = Color(0xFF938F99)
private val FrostOutlineVariant = Color(0xFF49454F)
private val FrostSurfaceContainer = Color(0xFF242329)
private val FrostSurfaceContainerHigh = Color(0xFF2B2930)
private val FrostInverseSurface = Color(0xFFE6E1E5)
private val FrostInverseOnSurface = Color(0xFF1C1B1F)
private val FrostInversePrimary = Color(0xFF00E5FF)

/**
 * Frost Terminal 暗色配色方案
 */
val FrostDarkColorScheme = darkColorScheme(
    primary = FrostPrimary,
    onPrimary = FrostOnPrimary,
    primaryContainer = FrostPrimaryContainer,
    onPrimaryContainer = FrostOnPrimaryContainer,
    secondary = FrostSecondary,
    onSecondary = FrostOnSecondary,
    secondaryContainer = FrostSecondaryContainer,
    onSecondaryContainer = FrostOnSecondaryContainer,
    tertiary = FrostTertiary,
    onTertiary = FrostOnTertiary,
    tertiaryContainer = FrostTertiaryContainer,
    onTertiaryContainer = FrostOnTertiaryContainer,
    error = FrostError,
    onError = FrostOnError,
    errorContainer = FrostErrorContainer,
    onErrorContainer = FrostOnErrorContainer,
    background = FrostBackground,
    onBackground = FrostOnBackground,
    surface = FrostSurfaceColor,
    onSurface = FrostOnSurface,
    surfaceVariant = FrostSurfaceVariant,
    onSurfaceVariant = FrostOnSurfaceVariant,
    outline = FrostOutline,
    outlineVariant = FrostOutlineVariant,
    surfaceContainer = FrostSurfaceContainer,
    surfaceContainerHigh = FrostSurfaceContainerHigh,
    inverseSurface = FrostInverseSurface,
    inverseOnSurface = FrostInverseOnSurface,
    inversePrimary = FrostInversePrimary,
)

/* ------------------------------------------------------------------
 *  Typography 定制
 * ------------------------------------------------------------------ */
val TombstoneXTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/* ------------------------------------------------------------------
 *  Shapes 定制
 * ------------------------------------------------------------------ */
val TombstoneXShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * TombstoneX 主题（暗色专用）
 */
@Composable
fun TombstoneXTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = FrostDarkColorScheme,
        typography = TombstoneXTypography,
        shapes = TombstoneXShapes,
        content = content,
    )
}