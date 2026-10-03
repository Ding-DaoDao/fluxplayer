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
import com.fluxplayer.app.core.model.AccentPreset
import com.fluxplayer.app.core.model.SurfaceStyle

/**
 * 应用主题入口。
 *
 * 配色只有一条路径：**中性灰阶底座 + 用户选定的强调色派生**（见 [neutralColorScheme]）。
 * 中性灰阶意味着 secondary/tertiary 家族被刻意去色，强调色是界面上唯一的色相出口。
 *
 * 历史上还有两条分支，已随主题统一移除：
 * - Material tonal（materialkolor 从 seed/壁纸动态生成多色相配色）
 * - Miuix 组件引擎（与 Material 并行的第二套组件外观）
 */
@Composable
fun NextPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrastDarkTheme: Boolean = false,
    accentPreset: AccentPreset = AccentPreset.Default,
    surfaceStyle: SurfaceStyle = SurfaceStyle.Default,
    content: @Composable () -> Unit,
) {
    val colorScheme = resolveColorScheme(
        darkTheme = darkTheme,
        highContrastDarkTheme = highContrastDarkTheme,
        accentPreset = accentPreset,
    )
    val styleFlags = remember(darkTheme, surfaceStyle) {
        FluxStyleFlags(isDark = darkTheme, surfaceStyle = surfaceStyle)
    }
    CompositionLocalProvider(LocalFluxStyle provides styleFlags) {
        MaterialThemeWrapper(
            colorScheme = colorScheme,
            typography = FluxM3Typography,
            shapes = FluxShapes,
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
