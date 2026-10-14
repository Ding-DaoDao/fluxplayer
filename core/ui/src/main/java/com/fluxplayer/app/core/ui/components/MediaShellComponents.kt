@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.fluxplayer.app.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.theme.LocalMediaBackdrop
import com.fluxplayer.app.core.ui.theme.LocalMediaPreferences
import com.fluxplayer.app.core.ui.theme.LocalMediaShell
import com.fluxplayer.app.core.ui.theme.mediaGlass

/** 所有内容页面共用形状变换加载动画，小尺寸按钮使用无底版本。 */
@Composable
fun MediaLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.Transparent,
    strokeCap: StrokeCap = StrokeCap.Round,
) {
    LoadingIndicator(modifier = modifier.size(48.dp), color = color)
}

/** 原横向等待提示改用居中的形状动画，确定进度仍保留原进度条。 */
@Composable
fun MediaLoadingRow(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.Transparent,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        MediaLoadingIndicator(color = color)
    }
}

/** 已知进度仍显示真实进度，采用同一套形状语言。 */
@Composable
fun MediaLoadingIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.Transparent,
    strokeCap: StrokeCap = StrokeCap.Round,
    gapSize: Dp = 0.dp,
) {
    LoadingIndicator(progress = progress, modifier = modifier.size(48.dp), color = color)
}

@Composable
fun MediaPageLoading(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContainedLoadingIndicator(modifier = Modifier.size(64.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 下拉刷新沿用相同的形状变换动画，拖动距离和刷新回调保持原行为。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MediaPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    contentAlignment: Alignment = Alignment.TopStart,
    indicator: @Composable BoxScope.() -> Unit = {
        PullToRefreshDefaults.LoadingIndicator(state = state, isRefreshing = isRefreshing, modifier = Modifier.align(Alignment.TopCenter))
    },
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        contentAlignment = contentAlignment,
        indicator = indicator,
        content = content,
    )
}

/** 花形图标底座与设置、来源入口共用，保持大小和内边距一致。 */
@Composable
fun MediaIconBadge(icon: ImageVector, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(44.dp).mediaGlass(MaterialShapes.Cookie7Sided.toShape()).clip(MaterialShapes.Cookie7Sided.toShape())
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (LocalMediaShell.current && LocalMediaPreferences.current.glassControlsEnabled && LocalMediaBackdrop.current != null) 0.35f else 1f).applyAlpha(enabled)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.applyAlpha(enabled))
    }
}

/** 设置组内用窄间距分隔卡片，播放器窗口继续使用原分隔线。 */
@Composable
fun MediaGroupSpacer(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = MaterialTheme.colorScheme.outlineVariant) {
    if (LocalMediaShell.current) Spacer(modifier.fillMaxWidth().height(2.dp)) else HorizontalDivider(modifier, thickness, color)
}

/** 输入框统一使用圆角填充底，保留密码、键盘和校验行为。 */
@Composable
fun MediaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = RoundedCornerShape(22.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        unfocusedBorderColor = Color.Transparent,
    ),
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
        textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = trailingIcon, prefix = prefix, suffix = suffix, supportingText = supportingText,
        isError = isError, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        interactionSource = interactionSource, shape = shape, colors = colors,
    )
}

/** 输入框统一使用圆角填充底，保留密码、键盘和校验行为。 */
@Composable
fun MediaTextField(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = RoundedCornerShape(22.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        unfocusedBorderColor = Color.Transparent,
    ),
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
        textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = trailingIcon, prefix = prefix, suffix = suffix, supportingText = supportingText,
        isError = isError, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        interactionSource = interactionSource, shape = shape, colors = colors,
    )
}

@Composable
fun MediaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = RoundedCornerShape(22.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier,
        offset = offset, scrollState = scrollState, properties = properties, shape = shape,
        containerColor = containerColor, tonalElevation = tonalElevation, shadowElevation = shadowElevation,
    ) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
fun MediaEmptyState(
    icon: ImageVector,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MediaIconBadge(icon, modifier = Modifier.size(64.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (actionLabel != null && onAction != null) FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** 弹窗统一容器、圆角和颜色，内容与确认操作仍由调用页面提供。 */
@Composable
fun MediaAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(32.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    iconContentColor: Color = MaterialTheme.colorScheme.primary,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(),
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val transition by animateFloatAsState(if (entered) 1f else 0f, spring(dampingRatio = 0.88f, stiffness = 380f), label = "media-dialog-enter")
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            modifier = modifier.heightIn(max = maxHeight).graphicsLayer {
                alpha = transition
                scaleX = 0.94f + transition * 0.06f
                scaleY = scaleX
                translationY = (1f - transition) * 24.dp.toPx()
            },
            shape = shape,
            color = containerColor,
            tonalElevation = tonalElevation,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                icon?.let { content ->
                    CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                        Box(Modifier.align(Alignment.CenterHorizontally)) { content() }
                    }
                }
                title?.let { content ->
                    CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { content() }
                    }
                }
                text?.let { content ->
                    Box(Modifier.weight(1f, fill = false)) {
                        CompositionLocalProvider(LocalContentColor provides textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { content() }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.invoke()
                    Spacer(Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier) {
    val animation = rememberInfiniteTransition(label = "media-skeleton")
    val phase by animation.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "media-skeleton-phase")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.8f)
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(base).drawWithCache {
            onDrawWithContent {
                drawContent()
                val center = size.width * (phase * 3f - 1f)
                drawRect(Brush.linearGradient(listOf(Color.Transparent, highlight, Color.Transparent), Offset(center - size.width, 0f), Offset(center + size.width, size.height)))
            }
        },
    )
}

/** 等待列表时先保留内容占位，避免整页只剩转圈。 */
@Composable
fun MediaListSkeleton(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(20.dp)) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ContainedLoadingIndicator(Modifier.size(48.dp))
                Text(stringResource(R.string.media_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(5) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SkeletonBlock(Modifier.width(88.dp).height(64.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(18.dp))
                        SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(12.dp))
                        SkeletonBlock(Modifier.fillMaxWidth().height(6.dp))
                    }
                }
            }
        }
    }
}
