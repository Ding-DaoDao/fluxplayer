package com.fluxplayer.app.core.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.ThemeColorSource
import com.kyant.backdrop.Backdrop
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

val LocalMediaPreferences = staticCompositionLocalOf { ApplicationPreferences() }

// 滚动内容保留底部留白，视口仍延伸到底栏下方供玻璃采样。
val LocalMediaScrollBottomPadding = staticCompositionLocalOf { 0.dp }

val LocalMediaBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 系统壁纸与种子色共用 Material 配色体系，纯黑模式只覆盖深色背景层。 */
@Composable
fun rememberMediaColorScheme(preferences: ApplicationPreferences, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val base = remember(context, configuration, preferences.themeColorSource, preferences.themeSeedArgb, preferences.themePaletteStyle, dark) {
        if (preferences.themeColorSource == ThemeColorSource.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            dynamicColorScheme(
                seedColor = Color(preferences.themeSeedArgb),
                isDark = dark,
                style = PaletteStyle.valueOf(preferences.themePaletteStyle.algorithm),
            )
        }
    }
    return if (dark && preferences.useHighContrastDarkTheme) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF060606),
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color(0xFF0B0B0B),
            surfaceContainerHighest = Color(0xFF121212),
            surfaceBright = Color(0xFF141414),
            surfaceVariant = Color(0xFF1A1A1A),
            surfaceTint = Color.Black,
        )
    } else {
        base
    }
}

/** 预设缩略卡直接使用相同的主题算法生成预览。 */
fun mediaCustomColorScheme(seed: Int, style: com.fluxplayer.app.core.model.ThemePaletteStyle): ColorScheme = dynamicColorScheme(seedColor = Color(seed), isDark = false, style = PaletteStyle.valueOf(style.algorithm))
