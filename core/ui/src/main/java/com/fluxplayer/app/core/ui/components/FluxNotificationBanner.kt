package com.fluxplayer.app.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.model.FluxMessageEvent
import kotlinx.coroutines.delay

// ──────────────────────────────────────────────
// 状态持有者
// ──────────────────────────────────────────────

/**
 * 通知状态持有者，在 Screen 层通过 [remember] 创建，
 * 传递给 [FluxNotificationHost] 和 ViewModel。
 *
 * 用法：
 * ```
 * val notificationState = remember { FluxNotificationState() }
 * FluxNotificationHost(state = notificationState) {
 *     // ... screen content
 * }
 * // ViewModel 端通过 notificationState.show(event) 发送通知
 * ```
 */
class FluxNotificationState {
    var currentEvent by mutableStateOf<FluxMessageEvent?>(null)
        private set

    private var lastShowTimestamp = 0L

    /** 展示一条通知 */
    fun show(event: FluxMessageEvent) {
        // 如果当前有一条未消除的 Error，先消除再展示新的
        // 但对于同类型/同名事件，时间戳变化能触发 recomposition
        currentEvent = event
        lastShowTimestamp = System.currentTimeMillis()
    }

    /** 手动消除当前通知 */
    fun dismiss() {
        currentEvent = null
    }

    /** 时间戳，用于 LaunchedEffect 的 key 以触发重新收集 */
    internal val showTimestamp: Long get() = lastShowTimestamp
}

// ──────────────────────────────────────────────
// Host 容器
// ──────────────────────────────────────────────

/**
 * 通知容器，包裹页面内容并在底部浮层展示通知。
 *
 * @param state 通知状态，通常由 [remember] 创建
 * @param onRetry 错误通知的"重试"回调，传 null 则不显示重试按钮
 * @param content 页面主内容
 */
@Composable
fun FluxNotificationHost(
    state: FluxNotificationState,
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        AnimatedVisibility(
            visible = state.currentEvent != null,
            enter = fadeIn(animationSpec = tween(300)),
            exit = ExitTransition.None,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        ) {
            state.currentEvent?.let { event ->
                FluxNotificationBannerContent(
                    event = event,
                    onDismiss = { state.dismiss() },
                    onRetry = {
                        state.dismiss()
                        onRetry?.invoke()
                    },
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// 独立横幅（不含内容包裹，用于已有 Box 容器）
// ──────────────────────────────────────────────

/**
 * 独立通知横幅，适用于已有 Box 容器的页面（如 [CloudBrowserPanel]）。
 * 调用方需自行在 BoxScope 内放置，通过 modifier 控制位置。
 *
 * @param event 当前通知事件，null 时自动隐藏
 * @param onDismiss 关闭回调
 * @param onRetry 错误重试回调
 * @param modifier 位置控制 modifier
 */
@Composable
fun FluxNotificationBanner(
    event: FluxMessageEvent?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = event != null,
        enter = fadeIn(animationSpec = tween(300)),
        exit = ExitTransition.None,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 96.dp),
    ) {
        event?.let {
            FluxNotificationBannerContent(
                event = it,
                onDismiss = onDismiss,
                onRetry = onRetry?.let { retry ->
                    {
                        onDismiss()
                        retry()
                    }
                },
            )
        }
    }
}

// ──────────────────────────────────────────────
// 横幅内容（私有）
// ──────────────────────────────────────────────

@Composable
private fun FluxNotificationBannerContent(
    event: FluxMessageEvent,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    val isPersistent = event is FluxMessageEvent.Error
    if (!isPersistent) {
        LaunchedEffect(event) {
            delay(3000)
            onDismiss()
        }
    }

    // 居中容器
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 6.dp,
            tonalElevation = 2.dp,
            modifier = Modifier
                .widthIn(max = 420.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (event) {
                        is FluxMessageEvent.Success -> Icons.Rounded.Check
                        is FluxMessageEvent.Error -> Icons.Rounded.PriorityHigh
                        is FluxMessageEvent.Info -> Icons.Rounded.Info
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isPersistent && onRetry != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onRetry) {
                        Text("重试", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (isPersistent) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
