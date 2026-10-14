package com.fluxplayer.app.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.theme.FluxRadius
import com.fluxplayer.app.core.ui.theme.FluxSpacing
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.core.ui.theme.LocalMediaPreferences
import com.fluxplayer.app.core.ui.theme.isFlatSurface
import com.fluxplayer.app.core.ui.theme.mediaGlass
import com.fluxplayer.app.core.ui.theme.onAccent
import com.fluxplayer.app.core.ui.theme.sectionDivider
import kotlin.math.roundToInt

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
    val preferences = LocalMediaPreferences.current
    val flat = isFlatSurface()
    val indicatorTarget = if (flat) MaterialTheme.colorScheme.primaryContainer else FluxTheme.colorScheme.primary
    val onIndicatorTarget = if (flat) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        FluxTheme.colorScheme.primary.onAccent()
    }
    val indicator by animateColorAsState(
        targetValue = indicatorTarget,
        animationSpec = tween(if (preferences.navAnimationsEnabled) 240 else 0),
        label = "nav-bar-indicator",
    )
    val selectedIconColor by animateColorAsState(
        targetValue = onIndicatorTarget,
        animationSpec = tween(if (preferences.navAnimationsEnabled) 220 else 0),
        label = "nav-bar-icon",
    )
    val unselectedIconColor by animateColorAsState(
        targetValue = FluxTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(if (preferences.navAnimationsEnabled) 220 else 0),
        label = "nav-bar-icon-idle",
    )
    val selectedLabelColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = tween(if (preferences.navAnimationsEnabled) 220 else 0),
        label = "nav-bar-label",
    )
    val unselectedLabelColor by animateColorAsState(
        targetValue = FluxTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(if (preferences.navAnimationsEnabled) 220 else 0),
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
 * 胶囊悬浮舱：整条是一个大胶囊，选中项使用柔和的强调色底。
 *
 * 图标与标签常驻，入口等宽排列，兼顾触控面积与选中状态辨识。
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
    if (items.isEmpty()) return
    val preferences = LocalMediaPreferences.current
    // 选中态统一使用柔和色底，标签常驻，切换时各入口保持原位。
    val indicatorTarget = MaterialTheme.colorScheme.primaryContainer
    val onIndicatorTarget = MaterialTheme.colorScheme.onPrimaryContainer
    val indicator by animateColorAsState(
        targetValue = indicatorTarget,
        animationSpec = tween(if (preferences.navAnimationsEnabled) 240 else 0),
        label = "dock-indicator",
    )
    val selectedContent by animateColorAsState(
        targetValue = onIndicatorTarget,
        animationSpec = tween(if (preferences.navAnimationsEnabled) 220 else 0),
        label = "dock-content",
    )
    val idleContent by animateColorAsState(
        targetValue = FluxTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        animationSpec = tween(if (preferences.navAnimationsEnabled) 220 else 0),
        label = "dock-content-idle",
    )

    val selectedIndex = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val animatedIndex by animateFloatAsState(selectedIndex.toFloat(), if (preferences.navAnimationsEnabled) spring(dampingRatio = 0.78f, stiffness = 380f) else snap(), label = "dock-position")
    var draggedIndex by remember { mutableStateOf<Float?>(null) }

    @Composable
    fun DockItem(item: NavBarItem, modifier: Modifier = Modifier) {
        val selected = selectedKey == item.key
        val scale by animateFloatAsState(if (selected) 1f else 0.94f, if (preferences.navAnimationsEnabled) spring(dampingRatio = 0.7f, stiffness = 420f) else snap(), label = "dock-icon-scale")
        Column(
            modifier = modifier.clip(FluxRadius.PillShape)
                .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item.key) })
                .heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.icon,
                contentDescription = null,
                tint = if (selected) selectedContent else idleContent,
                modifier = Modifier.size(26.dp).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            )
            Text(
                text = item.label,
                color = if (selected) selectedContent else idleContent,
                style = FluxTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }

    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = shadowElevation,
        border = BorderStroke(1.dp, FluxTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        if (vertical) {
            Column(Modifier.padding(4.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item ->
                    Box(Modifier.background(if (item.key == selectedKey) indicator else Color.Transparent, FluxRadius.PillShape)) {
                        DockItem(item, Modifier.size(width = 64.dp, height = 64.dp))
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(4.dp)) {
                val slotWidth = maxWidth / items.size.coerceAtLeast(1)
                val slotPx = with(LocalDensity.current) { slotWidth.toPx() }
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Box(
                    Modifier.fillMaxWidth().height(56.dp).pointerInput(items, selectedKey, slotPx, rtl, preferences.navGesturesEnabled) {
                        if (preferences.navGesturesEnabled) {
                            detectHorizontalDragGestures(
                                onDragStart = { draggedIndex = selectedIndex.toFloat() },
                                onHorizontalDrag = { change, distance ->
                                    change.consume()
                                    draggedIndex = ((draggedIndex ?: selectedIndex.toFloat()) + distance / slotPx * if (rtl) -1f else 1f)
                                        .coerceIn(0f, (items.size - 1).coerceAtLeast(0).toFloat())
                                },
                                onDragEnd = {
                                    val target = (draggedIndex ?: selectedIndex.toFloat()).roundToInt().coerceIn(0, items.lastIndex)
                                    draggedIndex = null
                                    onSelect(items[target].key)
                                },
                                onDragCancel = { draggedIndex = null },
                            )
                        }
                    },
                ) {
                    Box(
                        Modifier.offset(x = slotWidth * (draggedIndex ?: animatedIndex)).width(slotWidth)
                            .height(56.dp).mediaGlass(FluxRadius.PillShape, enabled = preferences.useLiquidGlass).clip(FluxRadius.PillShape).background(indicator.copy(alpha = if (preferences.useLiquidGlass) 0.28f else 0.75f)),
                    )
                    Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        items.forEach { DockItem(it, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** 贴底矩形玻璃底（液态玻璃降级路径用）：通栏形态但不做胶囊裁切。 */
val NavBarRectangleShape: Shape = RectangleShape
