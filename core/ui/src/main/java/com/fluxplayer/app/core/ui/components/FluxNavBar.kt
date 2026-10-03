package com.fluxplayer.app.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.fluxplayer.app.core.ui.theme.FluxRadius
import com.fluxplayer.app.core.ui.theme.FluxSpacing
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.core.ui.theme.isFlatSurface
import com.fluxplayer.app.core.ui.theme.onAccent
import com.fluxplayer.app.core.ui.theme.sectionDivider

/** 底栏目的地。[icon] 未选中、[selectedIcon] 选中，两态图标形状不同才能一眼看出选中。 */
data class NavBarItem(
    val key: Int,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * 通栏导航条：贴底通宽、图标在上标签在下、选中态是一枚 64×32 指示胶囊。
 *
 * 与 [CapsuleDockBar] 是同一组目的地、同一套选中色，只是布局与贴边方式不同。
 * 指示胶囊、字重 Bold/Medium 切换、图标双态这三点让"选中当前位置"不依赖颜色单一通道。
 */
@Composable
fun FullWidthNavBar(
    items: List<NavBarItem>,
    selectedKey: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = FluxTheme.colorScheme.surfaceContainer,
    contentColor: Color = FluxTheme.colorScheme.onSurface,
    shadowElevation: Dp = 0.dp,
) {
    val flat = isFlatSurface()
    val indicatorTarget = if (flat) MaterialTheme.colorScheme.primaryContainer else FluxTheme.colorScheme.primary
    val onIndicatorTarget = if (flat) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        FluxTheme.colorScheme.primary.onAccent()
    }
    val indicator by animateColorAsState(
        targetValue = indicatorTarget,
        animationSpec = tween(240),
        label = "nav-bar-indicator",
    )
    val selectedIconColor by animateColorAsState(
        targetValue = onIndicatorTarget,
        animationSpec = tween(220),
        label = "nav-bar-icon",
    )
    val unselectedIconColor by animateColorAsState(
        targetValue = FluxTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "nav-bar-icon-idle",
    )
    val selectedLabelColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = tween(220),
        label = "nav-bar-label",
    )
    val unselectedLabelColor by animateColorAsState(
        targetValue = FluxTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "nav-bar-label-idle",
    )

    Column(modifier = modifier.fillMaxWidth().shadow(shadowElevation, RectangleShape)) {
        // 扁平质感下卡面与画布已有色差，发丝线隐去；玻璃质感才靠它分界
        if (!flat) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(sectionDivider()),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .heightIn(min = 80.dp)
                .padding(vertical = FluxSpacing.s),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = selectedKey == item.key
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(FluxRadius.PillShape)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(item.key) },
                        )
                        .heightIn(min = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // 指示胶囊固定 64×32：横向占位不变，切换时标签不会横向抖动
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 32.dp)
                            .clip(FluxRadius.PillShape)
                            .background(if (selected) indicator else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
                            contentDescription = null,
                            tint = if (selected) selectedIconColor else unselectedIconColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        text = item.label,
                        style = FluxTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) selectedLabelColor else unselectedLabelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = FluxSpacing.xs),
                    )
                }
            }
        }
    }
}

/**
 * 胶囊悬浮舱：整条是一个大胶囊，选中项**展开显示文字**，未选中只显示图标。
 *
 * 比通栏省横向空间 —— 四个 tab 全展开也放得下，适合 tab 数量多或需要预留宽度余量的场景。
 */
@Composable
fun CapsuleDockBar(
    items: List<NavBarItem>,
    selectedKey: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    containerColor: Color = FluxTheme.colorScheme.surfaceContainer,
    contentColor: Color = FluxTheme.colorScheme.onSurface,
    shape: Shape = FluxRadius.PillShape,
    shadowElevation: Dp = 0.dp,
) {
    // 扁平下用 primaryContainer 淡底，玻璃下用实色 primary。
    // 前景必须由指示色**算**出来（onAccent），不能直接复用容器文字色 ——
    // primary 是近黑，配 onSurface 同样是近黑，选中项会完全读不出来。
    val flat = isFlatSurface()
    val indicatorTarget = if (flat) MaterialTheme.colorScheme.primaryContainer else FluxTheme.colorScheme.primary
    val onIndicatorTarget = if (flat) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        FluxTheme.colorScheme.primary.onAccent()
    }
    val indicator by animateColorAsState(
        targetValue = indicatorTarget,
        animationSpec = tween(240),
        label = "dock-indicator",
    )
    val selectedContent by animateColorAsState(
        targetValue = onIndicatorTarget,
        animationSpec = tween(220),
        label = "dock-content",
    )
    val idleContent by animateColorAsState(
        targetValue = FluxTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "dock-content-idle",
    )

    val buttons: @Composable () -> Unit = {
        items.forEach { item ->
            val selected = selectedKey == item.key
            Surface(
                onClick = { onSelect(item.key) },
                shape = FluxRadius.PillShape,
                color = if (selected) indicator else Color.Transparent,
                contentColor = if (selected) selectedContent else idleContent,
            ) {
                if (vertical) {
                    Column(
                        modifier = Modifier.size(width = 56.dp, height = if (selected) 64.dp else 56.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = item.label,
                            style = FluxTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            // 选中项多出文字，用 animateContentSize 让胶囊宽度平滑长出来
                            .animateContentSize(animationSpec = tween(240))
                            .padding(
                                start = if (selected) 16.dp else 11.dp,
                                end = if (selected) 18.dp else 11.dp,
                                top = 11.dp,
                                bottom = 11.dp,
                            ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(22.dp),
                        )
                        if (selected) {
                            Text(
                                text = item.label,
                                style = FluxTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = shadowElevation,
    ) {
        if (vertical) {
            Column(
                modifier = Modifier.padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { buttons() }
        } else {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { buttons() }
        }
    }
}

/** 贴底矩形玻璃底（液态玻璃降级路径用）：通栏形态但不做胶囊裁切。 */
val NavBarRectangleShape: Shape = RectangleShape
