package com.fluxplayer.app.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun MiuixThemeWrapper(
    isDark: Boolean,
    dynamicColor: Boolean,
    m3ColorScheme: ColorScheme,
    content: @Composable () -> Unit,
) {
    // Miuix 使用内置默认色，保持其独立的 MIUI 视觉体系（不接入种子色/动态色）
    // 使其与 MD3 引擎在配色上有明显差异
    val colorSchemeMode = if (isDark) ColorSchemeMode.Dark else ColorSchemeMode.Light

    val controller = remember(colorSchemeMode, isDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            isDark = isDark,
        )
    }

    MiuixTheme(controller = controller) {
        val mc = MiuixTheme.colorScheme
        val fluxColorScheme = remember(mc) {
            FluxColorScheme(
                primary = mc.primary,
                onPrimary = mc.onPrimary,
                primaryContainer = mc.primaryContainer,
                onPrimaryContainer = mc.onPrimaryContainer,
                secondary = mc.secondary,
                onSecondary = mc.onSecondary,
                secondaryContainer = mc.secondaryContainer,
                onSecondaryContainer = mc.onSecondaryContainer,
                tertiary = mc.tertiaryContainer,
                onTertiary = mc.onTertiaryContainer,
                tertiaryContainer = mc.tertiaryContainer,
                onTertiaryContainer = mc.onTertiaryContainer,
                error = mc.error,
                onError = mc.onError,
                errorContainer = mc.errorContainer,
                onErrorContainer = mc.onErrorContainer,
                background = mc.background,
                onBackground = mc.onSurface,
                surface = mc.surface,
                onSurface = mc.onSurface,
                surfaceVariant = mc.surfaceVariant,
                onSurfaceVariant = mc.onSurfaceVariantSummary,
                outline = mc.outline,
                outlineVariant = mc.dividerLine,
                scrim = mc.windowDimming,
                inverseSurface = mc.onSurface,
                inverseOnSurface = mc.surface,
                inversePrimary = mc.primaryVariant,
                surfaceDim = mc.background,
                surfaceBright = mc.surface,
                surfaceContainerLowest = mc.background,
                surfaceContainerLow = mc.surfaceContainer,
                surfaceContainer = mc.surfaceContainer,
                surfaceContainerHigh = mc.surfaceContainerHigh,
                surfaceContainerHighest = mc.surfaceContainerHighest,
            )
        }

        val ms = MiuixTheme.textStyles
        val fluxTypography = remember(ms) {
            FluxTypography(
                displayLarge = ms.title1,
                displayMedium = ms.title2,
                displaySmall = ms.title3,
                headlineLarge = ms.title1,
                headlineMedium = ms.title2,
                headlineSmall = ms.title3,
                titleLarge = ms.title4,
                titleMedium = ms.headline2,
                titleSmall = ms.subtitle,
                bodyLarge = ms.paragraph,
                bodyMedium = ms.body1,
                bodySmall = ms.body2.copy(fontSize = 12.sp),
                labelLarge = ms.footnote1.copy(fontSize = 14.sp),
                labelMedium = ms.footnote1,
                labelSmall = ms.footnote2,
            )
        }

        // 同步 Miuix 颜色到 MaterialTheme，确保所有使用 MaterialTheme.colorScheme.* 的代码也拿到 Miuix 颜色
        val m3FromMiuix = remember(mc, isDark) {
            val builder: () -> ColorScheme = {
                if (isDark) {
                    darkColorScheme(
                        primary = mc.primary,
                        onPrimary = mc.onPrimary,
                        primaryContainer = mc.primaryContainer,
                        onPrimaryContainer = mc.onPrimaryContainer,
                        secondary = mc.secondary,
                        onSecondary = mc.onSecondary,
                        secondaryContainer = mc.secondaryContainer,
                        onSecondaryContainer = mc.onSecondaryContainer,
                        tertiary = mc.tertiaryContainer,
                        onTertiary = mc.onTertiaryContainer,
                        tertiaryContainer = mc.tertiaryContainer,
                        onTertiaryContainer = mc.onTertiaryContainer,
                        error = mc.error,
                        onError = mc.onError,
                        errorContainer = mc.errorContainer,
                        onErrorContainer = mc.onErrorContainer,
                        background = mc.background,
                        onBackground = mc.onSurface,
                        surface = mc.surface,
                        onSurface = mc.onSurface,
                        surfaceVariant = mc.surfaceVariant,
                        onSurfaceVariant = mc.onSurfaceVariantSummary,
                        outline = mc.outline,
                        outlineVariant = mc.dividerLine,
                        scrim = mc.windowDimming,
                        inverseSurface = mc.onSurface,
                        inverseOnSurface = mc.surface,
                        inversePrimary = mc.primaryVariant,
                        surfaceDim = mc.background,
                        surfaceBright = mc.surface,
                        surfaceContainerLowest = mc.background,
                        surfaceContainerLow = mc.surfaceContainer,
                        surfaceContainer = mc.surfaceContainer,
                        surfaceContainerHigh = mc.surfaceContainerHigh,
                        surfaceContainerHighest = mc.surfaceContainerHighest,
                    )
                } else {
                    lightColorScheme(
                        primary = mc.primary,
                        onPrimary = mc.onPrimary,
                        primaryContainer = mc.primaryContainer,
                        onPrimaryContainer = mc.onPrimaryContainer,
                        secondary = mc.secondary,
                        onSecondary = mc.onSecondary,
                        secondaryContainer = mc.secondaryContainer,
                        onSecondaryContainer = mc.onSecondaryContainer,
                        tertiary = mc.tertiaryContainer,
                        onTertiary = mc.onTertiaryContainer,
                        tertiaryContainer = mc.tertiaryContainer,
                        onTertiaryContainer = mc.onTertiaryContainer,
                        error = mc.error,
                        onError = mc.onError,
                        errorContainer = mc.errorContainer,
                        onErrorContainer = mc.onErrorContainer,
                        background = mc.background,
                        onBackground = mc.onSurface,
                        surface = mc.surface,
                        onSurface = mc.onSurface,
                        surfaceVariant = mc.surfaceVariant,
                        onSurfaceVariant = mc.onSurfaceVariantSummary,
                        outline = mc.outline,
                        outlineVariant = mc.dividerLine,
                        scrim = mc.windowDimming,
                        inverseSurface = mc.onSurface,
                        inverseOnSurface = mc.surface,
                        inversePrimary = mc.primaryVariant,
                        surfaceDim = mc.background,
                        surfaceBright = mc.surface,
                        surfaceContainerLowest = mc.background,
                        surfaceContainerLow = mc.surfaceContainer,
                        surfaceContainer = mc.surfaceContainer,
                        surfaceContainerHigh = mc.surfaceContainerHigh,
                        surfaceContainerHighest = mc.surfaceContainerHighest,
                    )
                }
            }
            builder()
        }

        CompositionLocalProvider(
            LocalFluxColorScheme provides fluxColorScheme,
            LocalFluxTypography provides fluxTypography,
        ) {
            MaterialTheme(
                colorScheme = m3FromMiuix,
                typography = Typography,
                content = content,
            )
        }
    }
}
