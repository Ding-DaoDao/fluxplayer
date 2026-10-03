package com.fluxplayer.app.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.model.NavStyle
import com.fluxplayer.app.core.model.SurfaceStyle

/**
 * 间距刻度。
 *
 * 散落的 13/17/18/21dp 一律向这几档收敛 —— 「差不多的间距」比「明显不同的间距」更伤观感，
 * 因为眼睛看得出差异却找不到规律。新增间距请先用这里的档位，不够再考虑加档。
 */
object FluxSpacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 28.dp
}

/**
 * 圆角刻度。
 *
 * **控件越小圆角越小** —— 小胶囊用大圆角会把内容区吃掉，22dp 的圆钮配 999 胶囊会显得滑稽。
 * 选档规则：图标级 < 字段级 < 行级 < 卡级 < 弹层。
 */
object FluxRadius {
    val icon: Dp = 8.dp
    val field: Dp = 14.dp
    val row: Dp = 16.dp
    val card: Dp = 20.dp
    val sheet: Dp = 28.dp
    val pill: Dp = 999.dp

    val IconShape = RoundedCornerShape(icon)
    val FieldShape = RoundedCornerShape(field)
    val RowShape = RoundedCornerShape(row)
    val CardShape = RoundedCornerShape(card)
    val SheetShape = RoundedCornerShape(sheet)
    val PillShape = RoundedCornerShape(pill)
}

/** 分组之间的纵向留白。层级靠它建立，不靠给每张卡加发光边。 */
object FluxLayout {
    /** 页面左右留白。全 App 只有这一个值，二级页与主页共用。 */
    val PageGutter: Dp = FluxSpacing.xl

    /** 分组之间的纵向留白。 */
    val SectionGap: Dp = 22.dp

    /** 设置行的最小高度：单行 56、带副标题时自然撑高。 */
    val RowMinHeight: Dp = 56.dp

    /** 行首圆角色底图标的边长。 */
    val IconTile: Dp = 34.dp

    /** 行首图标内的图形边长。 */
    val IconGlyph: Dp = 19.dp

    /** 组内分隔线缩进：让过图标列，使图标成为一条连续视觉轴。 */
    val RowDividerInset: Dp = 62.dp

    /** 书籍封面标准比例（接近真实开本 1:1.45）。 */
    const val CoverAspectRatio: Float = 0.69f

    /** 听书封面比例（略窄，配合播放页竖向布局）。 */
    const val AudioCoverAspectRatio: Float = 0.72f
}

/** 形状与密度：标准刻度或 MD3 Expressive 式的大圆角、高控件。 */
enum class ShapeStyle(val label: String) {
    STANDARD("标准"),
    EXPRESSIVE("舒展"),
    ;

    companion object {
        val Default = STANDARD
    }
}

/** 分组卡的底色：素面卡靠不透明底 + 发丝线 + 组间大留白建立层级。 */
@Composable
@ReadOnlyComposable
fun sectionCardColor(): Color = if (isDarkTheme()) {
    FluxTheme.colorScheme.surfaceContainerLow
} else {
    FluxTheme.colorScheme.surface
}

/**
 * 发丝线：卡片描边与组内分隔线共用，淡到只在需要时才看得见。
 * 扁平质感下卡面与画布的色差本身就是边界，描边一律隐去（分隔线仍保留）。
 */
@Composable
@ReadOnlyComposable
fun sectionHairline(): Color = if (isFlatSurface()) {
    Color.Transparent
} else {
    FluxTheme.colorScheme.outlineVariant.copy(alpha = if (isDarkTheme()) 0.45f else 0.55f)
}

/** 组内分隔线：玻璃质感与描边同色；扁平质感下描边没了，分隔线仍要淡淡一条。 */
@Composable
@ReadOnlyComposable
fun sectionDivider(): Color =
    FluxTheme.colorScheme.outlineVariant.copy(alpha = if (isDarkTheme()) 0.45f else 0.55f)

/** 表单输入区的填充底：比卡面再深/浅一档，不描边也划得出边界。 */
@Composable
@ReadOnlyComposable
fun fieldContainerColor(): Color = if (isFlatSurface()) {
    FluxTheme.colorScheme.surfaceContainerHigh
} else {
    FluxTheme.colorScheme.surfaceContainer
}

/** 当前明暗。等价于 `isSystemInDarkTheme()`，但尊重应用内的主题选择。 */
@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = LocalFluxStyle.current.isDark

/**
 * 明暗与质感维度的承载者。
 *
 * 由 `NextPlayerTheme` 一处 provide，其余地方只读。
 */
@Immutable
data class FluxStyleFlags(
    val isDark: Boolean = false,
    val surfaceStyle: SurfaceStyle = SurfaceStyle.Default,
    val navStyle: NavStyle = NavStyle.Default,
    val shapeStyle: ShapeStyle = ShapeStyle.Default,
)

val LocalFluxStyle = staticCompositionLocalOf { FluxStyleFlags() }

@Composable
@ReadOnlyComposable
fun isFlatSurface(): Boolean = LocalFluxStyle.current.surfaceStyle == SurfaceStyle.FLAT
