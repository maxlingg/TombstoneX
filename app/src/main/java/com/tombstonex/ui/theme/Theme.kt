package com.tombstonex.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------
 *  冰蓝色系（Ice Blue）调色板 —— 代表「冻结」概念
 *  基于 Material Theme Builder 生成的 MD3 配色方案
 * ------------------------------------------------------------------ */

// ---- 浅色配色 ----
private val IceBlueLightPrimary = Color(0xFF0061A4)
private val IceBlueLightOnPrimary = Color(0xFFFFFFFF)
private val IceBlueLightPrimaryContainer = Color(0xFFD1E4FF)
private val IceBlueLightOnPrimaryContainer = Color(0xFF001D36)

private val IceBlueLightSecondary = Color(0xFF535F70)
private val IceBlueLightOnSecondary = Color(0xFFFFFFFF)
private val IceBlueLightSecondaryContainer = Color(0xFFD7E3F7)
private val IceBlueLightOnSecondaryContainer = Color(0xFF101C2B)

private val IceBlueLightTertiary = Color(0xFF6B5778)
private val IceBlueLightOnTertiary = Color(0xFFFFFFFF)
private val IceBlueLightTertiaryContainer = Color(0xFFF2DAFF)
private val IceBlueLightOnTertiaryContainer = Color(0xFF251431)

private val IceBlueLightError = Color(0xFFBA1A1A)
private val IceBlueLightOnError = Color(0xFFFFFFFF)
private val IceBlueLightErrorContainer = Color(0xFFFFDAD6)
private val IceBlueLightOnErrorContainer = Color(0xFF410002)

private val IceBlueLightBackground = Color(0xFFFDFCFF)
private val IceBlueLightOnBackground = Color(0xFF1A1C1E)
private val IceBlueLightSurface = Color(0xFFFDFCFF)
private val IceBlueLightOnSurface = Color(0xFF1A1C1E)
private val IceBlueLightSurfaceVariant = Color(0xFFDFE2EB)
private val IceBlueLightOnSurfaceVariant = Color(0xFF43474E)
private val IceBlueLightOutline = Color(0xFF73777F)
private val IceBlueLightOutlineVariant = Color(0xFFC3C7CF)
private val IceBlueLightSurfaceContainer = Color(0xFFEEF0F4)
private val IceBlueLightSurfaceContainerHigh = Color(0xFFE8EAF0)
private val IceBlueLightInverseSurface = Color(0xFF2F3033)
private val IceBlueLightInverseOnSurface = Color(0xFFF1F0F3)
private val IceBlueLightInversePrimary = Color(0xFF9ECAFF)

// ---- 深色配色（深色模式为主） ----
private val IceBlueDarkPrimary = Color(0xFF9ECAFF)
private val IceBlueDarkOnPrimary = Color(0xFF003258)
private val IceBlueDarkPrimaryContainer = Color(0xFF00497D)
private val IceBlueDarkOnPrimaryContainer = Color(0xFFD1E4FF)

private val IceBlueDarkSecondary = Color(0xFFBBC7DB)
private val IceBlueDarkOnSecondary = Color(0xFF253140)
private val IceBlueDarkSecondaryContainer = Color(0xFF3B4858)
private val IceBlueDarkOnSecondaryContainer = Color(0xFFD7E3F7)

private val IceBlueDarkTertiary = Color(0xFFD6BEE4)
private val IceBlueDarkOnTertiary = Color(0xFF3B2948)
private val IceBlueDarkTertiaryContainer = Color(0xFF52405F)
private val IceBlueDarkOnTertiaryContainer = Color(0xFFF2DAFF)

private val IceBlueDarkError = Color(0xFFFFB4AB)
private val IceBlueDarkOnError = Color(0xFF690005)
private val IceBlueDarkErrorContainer = Color(0xFF93000A)
private val IceBlueDarkOnErrorContainer = Color(0xFFFFDAD6)

private val IceBlueDarkBackground = Color(0xFF101418)
private val IceBlueDarkOnBackground = Color(0xFFE2E2E5)
private val IceBlueDarkSurface = Color(0xFF131619)
private val IceBlueDarkOnSurface = Color(0xFFE2E2E5)
private val IceBlueDarkSurfaceVariant = Color(0xFF43474E)
private val IceBlueDarkOnSurfaceVariant = Color(0xFFC3C7CF)
private val IceBlueDarkOutline = Color(0xFF8D9199)
private val IceBlueDarkOutlineVariant = Color(0xFF43474E)
private val IceBlueDarkSurfaceContainer = Color(0xFF1F2226)
private val IceBlueDarkSurfaceContainerHigh = Color(0xFF2A2D31)
private val IceBlueDarkInverseSurface = Color(0xFFE2E2E5)
private val IceBlueDarkInverseOnSurface = Color(0xFF2F3033)
private val IceBlueDarkInversePrimary = Color(0xFF0061A4)

/**
 * 浅色配色方案（冰蓝色系）
 */
val IceBlueLightColorScheme = lightColorScheme(
    primary = IceBlueLightPrimary,
    onPrimary = IceBlueLightOnPrimary,
    primaryContainer = IceBlueLightPrimaryContainer,
    onPrimaryContainer = IceBlueLightOnPrimaryContainer,
    secondary = IceBlueLightSecondary,
    onSecondary = IceBlueLightOnSecondary,
    secondaryContainer = IceBlueLightSecondaryContainer,
    onSecondaryContainer = IceBlueLightOnSecondaryContainer,
    tertiary = IceBlueLightTertiary,
    onTertiary = IceBlueLightOnTertiary,
    tertiaryContainer = IceBlueLightTertiaryContainer,
    onTertiaryContainer = IceBlueLightOnTertiaryContainer,
    error = IceBlueLightError,
    onError = IceBlueLightOnError,
    errorContainer = IceBlueLightErrorContainer,
    onErrorContainer = IceBlueLightOnErrorContainer,
    background = IceBlueLightBackground,
    onBackground = IceBlueLightOnBackground,
    surface = IceBlueLightSurface,
    onSurface = IceBlueLightOnSurface,
    surfaceVariant = IceBlueLightSurfaceVariant,
    onSurfaceVariant = IceBlueLightOnSurfaceVariant,
    outline = IceBlueLightOutline,
    outlineVariant = IceBlueLightOutlineVariant,
    surfaceContainer = IceBlueLightSurfaceContainer,
    surfaceContainerHigh = IceBlueLightSurfaceContainerHigh,
    inverseSurface = IceBlueLightInverseSurface,
    inverseOnSurface = IceBlueLightInverseOnSurface,
    inversePrimary = IceBlueLightInversePrimary,
)

/**
 * 深色配色方案（冰蓝色系，深色为主）
 */
val IceBlueDarkColorScheme = darkColorScheme(
    primary = IceBlueDarkPrimary,
    onPrimary = IceBlueDarkOnPrimary,
    primaryContainer = IceBlueDarkPrimaryContainer,
    onPrimaryContainer = IceBlueDarkOnPrimaryContainer,
    secondary = IceBlueDarkSecondary,
    onSecondary = IceBlueDarkOnSecondary,
    secondaryContainer = IceBlueDarkSecondaryContainer,
    onSecondaryContainer = IceBlueDarkOnSecondaryContainer,
    tertiary = IceBlueDarkTertiary,
    onTertiary = IceBlueDarkOnTertiary,
    tertiaryContainer = IceBlueDarkTertiaryContainer,
    onTertiaryContainer = IceBlueDarkOnTertiaryContainer,
    error = IceBlueDarkError,
    onError = IceBlueDarkOnError,
    errorContainer = IceBlueDarkErrorContainer,
    onErrorContainer = IceBlueDarkOnErrorContainer,
    background = IceBlueDarkBackground,
    onBackground = IceBlueDarkOnBackground,
    surface = IceBlueDarkSurface,
    onSurface = IceBlueDarkOnSurface,
    surfaceVariant = IceBlueDarkSurfaceVariant,
    onSurfaceVariant = IceBlueDarkOnSurfaceVariant,
    outline = IceBlueDarkOutline,
    outlineVariant = IceBlueDarkOutlineVariant,
    surfaceContainer = IceBlueDarkSurfaceContainer,
    surfaceContainerHigh = IceBlueDarkSurfaceContainerHigh,
    inverseSurface = IceBlueDarkInverseSurface,
    inverseOnSurface = IceBlueDarkInverseOnSurface,
    inversePrimary = IceBlueDarkInversePrimary,
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
 * TombstoneX 主题
 *
 * @param darkTheme 是否使用深色模式，默认跟随系统
 * @param dynamicColor 是否启用 Material You 动态取色（Android 12+），默认关闭以使用品牌冰蓝色主题
 * @param content Compose 内容
 */
@Composable
fun TombstoneXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Android 12+ 支持 Material You 动态取色
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> IceBlueDarkColorScheme
        else -> IceBlueLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TombstoneXTypography,
        shapes = TombstoneXShapes,
        content = content,
    )
}
