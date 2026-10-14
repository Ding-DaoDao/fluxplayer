package com.fluxplayer.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.fluxplayer.app.core.model.AccentPreset
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.SurfaceStyle

/** 主应用页面采用统一媒体外观，播放器独立窗口保留原有控件布局。 */
val LocalMediaShell = staticCompositionLocalOf { false }

/**
 * 应用主题入口。
 *
 * 主应用界面使用系统壁纸或用户种子色生成 Material 配色。
 * 播放器独立窗口保留原有中性配色，不改变其控件布局。
 */
@Composable
fun NextPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrastDarkTheme: Boolean = false,
    accentPreset: AccentPreset = AccentPreset.Default,
    surfaceStyle: SurfaceStyle = SurfaceStyle.Default,
    mediaShell: Boolean = false,
    preferences: ApplicationPreferences? = null,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (mediaShell && preferences != null) {
        rememberMediaColorScheme(preferences, darkTheme)
    } else {
        resolveColorScheme(
            darkTheme = darkTheme,
            highContrastDarkTheme = highContrastDarkTheme,
            accentPreset = accentPreset,
        )
    }
    val styleFlags = remember(darkTheme, surfaceStyle) {
        FluxStyleFlags(isDark = darkTheme, surfaceStyle = surfaceStyle)
    }
    CompositionLocalProvider(
        LocalFluxStyle provides styleFlags,
        LocalMediaShell provides mediaShell,
        LocalMediaPreferences provides (preferences ?: ApplicationPreferences()),
    ) {
        MaterialThemeWrapper(
            colorScheme = colorScheme,
            typography = if (mediaShell) AVBoxTypography else FluxM3Typography,
            shapes = if (mediaShell) Shapes() else FluxShapes,
            content = content,
        )
    }
}

/**
 * 解析当前配色。
 *
 * [highContrastDarkTheme] 是 AMOLED 偏好：夜间把 surface 家族压到纯黑省电，
 * 但**不降级强调色** —— 纯黑底 + 强调色正是这套方案对比度最高的组合。
 */
@Composable
private fun resolveColorScheme(
    darkTheme: Boolean,
    highContrastDarkTheme: Boolean,
    accentPreset: AccentPreset,
): ColorScheme {
    val scheme = neutralColorScheme(
        accent = accentPreset.colorFor(darkTheme),
        dark = darkTheme,
    )
    return if (darkTheme && highContrastDarkTheme) {
        scheme.copy(
            background = backgroundPureBlack,
            surface = surfacePureBlack,
            surfaceDim = surfaceDimPureBlack,
            surfaceBright = surfaceBrightPureBlack,
            surfaceContainerLowest = surfaceContainerLowestPureBlack,
            surfaceContainerLow = surfaceContainerLowPureBlack,
            surfaceContainer = surfaceContainerPureBlack,
            surfaceContainerHigh = surfaceContainerHighPureBlack,
            surfaceContainerHighest = surfaceContainerHighestPureBlack,
        )
    } else {
        scheme
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialThemeWrapper(
    colorScheme: ColorScheme,
    typography: Typography,
    shapes: Shapes,
    content: @Composable () -> Unit,
) {
    val fluxColorScheme = colorScheme.toFluxColorScheme()
    val fluxTypography = typography.toFluxTypography()

    CompositionLocalProvider(
        LocalFluxColorScheme provides fluxColorScheme,
        LocalFluxTypography provides fluxTypography,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
