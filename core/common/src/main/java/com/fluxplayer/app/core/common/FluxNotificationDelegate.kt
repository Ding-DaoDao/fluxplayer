package com.fluxplayer.app.core.common

import com.fluxplayer.app.core.model.FluxMessageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 通知事件委托，用于 ViewModel 发射统一消息事件。
 *
 * 用法：
 * ```
 * class MyViewModel : ViewModel() {
 *     val notifier = FluxNotificationDelegate(viewModelScope)
 *     val messageEvents = notifier.events
 *
 *     fun doSomething() {
 *         notifier.success("操作成功")
 *         notifier.error("操作失败: ${e.message}")
 *     }
 * }
 * ```
 */
class FluxNotificationDelegate(private val scope: CoroutineScope) {
    private val _events = MutableSharedFlow<FluxMessageEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<FluxMessageEvent> = _events.asSharedFlow()

    /** 发射成功通知 */
    fun success(msg: String) {
        scope.launch { _events.emit(FluxMessageEvent.Success(msg)) }
    }

    /** 发射错误通知 */
    fun error(msg: String) {
        scope.launch { _events.emit(FluxMessageEvent.Error(msg)) }
    }

    /** 发射信息通知 */
    fun info(msg: String) {
        scope.launch { _events.emit(FluxMessageEvent.Info(msg)) }
    }
}
