package com.fluxplayer.app.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxplayer.app.core.model.AccentPreset

/**
 * 墨 · 极简主题（借鉴 MoRead「中性灰底 + 强调色」哲学）。
 *
 * 中性灰阶底色：不带任何色相，界面上唯一的彩色来源是强调色。
 * primary 家族由强调色动态派生（见 [withAccent]），secondary / tertiary 保持中性灰，
 * 因此换强调色时无需维护任何第二套色板。
 */

/** 强调色按给定明暗取 ARGB 对应的 [Color]。 */
fun AccentPreset.colorFor(dark: Boolean): Color = Color(if (dark) darkArgb else lightArgb)

private val NeutralLightColors = lightColorScheme(
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF5E5E5E),
    surfaceDim = Color(0xFFE3E3E3),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF1F1F1),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE3E3E3),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFC7C7C7),
    inverseSurface = Color(0xFF2E2E2E),
    inverseOnSurface = Color(0xFFF2F2F2),
    // 中性化的次级家族：避免任何杂色出现在界面上。
    secondary = Color(0xFF5E5E5E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4E4),
    onSecondaryContainer = Color(0xFF272727),
    tertiary = Color(0xFF6B6B6B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCDCDC),
    onTertiaryContainer = Color(0xFF232323),
)

private val NeutralDarkColors = darkColorScheme(
    background = Color(0xFF0E0E0E),
    onBackground = Color(0xFFE4E4E4),
    surface = Color(0xFF151515),
    onSurface = Color(0xFFE4E4E4),
    surfaceVariant = Color(0xFF3C3C3C),
    onSurfaceVariant = Color(0xFFC0C0C0),
    surfaceDim = Color(0xFF151515),
    surfaceBright = Color(0xFF323232),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1D1D1D),
    surfaceContainerHigh = Color(0xFF272727),
    surfaceContainerHighest = Color(0xFF323232),
    outline = Color(0xFF8C8C8C),
    outlineVariant = Color(0xFF414141),
    inverseSurface = Color(0xFFE4E4E4),
    inverseOnSurface = Color(0xFF2B2B2B),
    secondary = Color(0xFFB4B4B4),
    onSecondary = Color(0xFF2A2A2A),
    secondaryContainer = Color(0xFF383838),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary = Color(0xFFA6A6A6),
    onTertiary = Color(0xFF262626),
    tertiaryContainer = Color(0xFF303030),
    onTertiaryContainer = Color(0xFFDADADA),
)

/**
 * 中性配色入口：**中性灰阶底座 + 指定强调色派生的完整 ColorScheme**。
 *
 * 这是全应用唯一的配色实现。中性灰阶意味着 secondary/tertiary 家族被刻意去色，
 * 强调色成为界面上唯一的色相出口 —— 换强调色能立刻在全局看到变化，
 * 而底色始终保持克制。强调色由用户在「设置 › 外观」中选择。
 */
fun neutralColorScheme(accent: Color, dark: Boolean): ColorScheme =
    (if (dark) NeutralDarkColors else NeutralLightColors).withAccent(accent, dark)

/** 形态：全面大圆角，呼应胶囊化语言。 */
val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** 排版：标题用衬线（Serif）半粗，正文保持无衬线 —— 书卷气的来源。 */
val FluxM3Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 52.sp,
        lineHeight = 58.sp,
        letterSpacing = (-0.8).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 42.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.25.sp,
    ),
)

/**
 * 把强调色注入 primary 家族。container 由强调色按低透明度混入底色得到，
 * 这样任意强调色都能得到协调的淡底；onPrimary 按亮度取黑/白以保证对比度。
 */
private fun ColorScheme.withAccent(
    accent: Color,
    dark: Boolean,
): ColorScheme {
    val container = accent
        .copy(alpha = if (dark) 0.22f else 0.16f)
        .compositeOver(if (dark) surfaceContainerHigh else surfaceContainer)
    return copy(
        primary = accent,
        onPrimary = accent.onAccent(),
        primaryContainer = container,
        // 直接用 accent 当 onPrimaryContainer 在夜间会翻车：墨色的夜间强调色是近白
        // (0xFFE8E8E8)，而 container 本身就是这个近白混出来的浅灰 —— 浅上叠浅等于看不见。
        // 改成按 container 的明暗取前景，保证任何强调色下都有足够对比。
        onPrimaryContainer = readableOn(background = container, preferred = accent),
        inversePrimary = accent,
    )
}

/**
 * 强调色上的前景色。取近黑/近白里对比更高的那个 —— 固定亮度阈值会在中间调上翻车：
 * 「朱」的夜间色 0xFFD9755A 亮度 0.28 会被判成「深色」而配白字，实测对比只有 2.9，
 * 而近黑能到 6.0。直接比对比度就不会有这种边界问题。
 */
fun Color.onAccent(): Color {
    val onDark = Color(0xFFF7F7F7)
    val onLight = Color(0xFF111111)
    return if (contrastRatio(onDark, this) >= contrastRatio(onLight, this)) onDark else onLight
}

/** WCAG 相对对比度。[Color.luminance] 已是相对亮度，直接套公式。 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val lighter = maxOf(a.luminance(), b.luminance())
    val darker = minOf(a.luminance(), b.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** WCAG AA 正文对比度下限。 */
internal const val MIN_CONTENT_CONTRAST = 4.5f

/**
 * 在 [background] 上给出可读的 [preferred]：对比够就直接用原色，不够就朝可读方向推。
 * 这样彩色强调尽量保住色相，只在真会糊掉时才退到中性前景。
 */
internal fun readableOn(background: Color, preferred: Color): Color {
    if (contrastRatio(preferred, background) >= MIN_CONTENT_CONTRAST) return preferred
    val target = if (background.luminance() > MAX_LIGHT_LUMINANCE) {
        Color(0xFF111111)
    } else {
        Color(0xFFF2F2F2)
    }
    // 先把原色朝目标混掉一半，保住一点色相；仍不达标才用纯中性色。
    val blended = preferred.copy(alpha = 0.55f).compositeOver(target)
    return if (contrastRatio(blended, background) >= MIN_CONTENT_CONTRAST) blended else target
}

/**
 * 用户自选的颜色不保证在当前底色上可读 —— 深色底上太暗、浅色底上太亮的都往回拉，
 * 保证强调色始终能从背景里跳出来。为后续自定义强调色预留。
 */
internal fun adaptCustomAccent(color: Color, dark: Boolean): Color {
    val luminance = color.luminance()
    return when {
        dark && luminance < MIN_DARK_LUMINANCE -> color.adjustTowards(Color.White, MIN_DARK_LUMINANCE)
        !dark && luminance > MAX_LIGHT_LUMINANCE -> color.adjustTowards(Color.Black, MAX_LIGHT_LUMINANCE)
        else -> color
    }
}

private fun Color.adjustTowards(bound: Color, target: Float): Color {
    var low = 0f
    var high = 1f
    var result = this
    repeat(ADJUST_ITERATIONS) {
        val mid = (low + high) / 2f
        result = Color(
            red = red + (bound.red - red) * mid,
            green = green + (bound.green - green) * mid,
            blue = blue + (bound.blue - blue) * mid,
            alpha = alpha,
        )
        if (result.luminance() < target) low = mid else high = mid
    }
    return result
}

private const val MIN_DARK_LUMINANCE = 0.35f
private const val MAX_LIGHT_LUMINANCE = 0.45f
private const val ADJUST_ITERATIONS = 12
