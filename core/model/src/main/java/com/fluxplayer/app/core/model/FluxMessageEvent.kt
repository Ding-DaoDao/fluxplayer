package com.fluxplayer.app.core.model

/**
 * 统一消息事件，用于跨模块传递通知。
 * ViewModel 发射此类事件，UI 层通过 [FluxNotificationHost] 收集并展示。
 */
sealed class FluxMessageEvent {
    abstract val message: String

    /** 操作成功 */
    data class Success(override val message: String) : FluxMessageEvent()

    /** 操作失败/异常 */
    data class Error(
        override val message: String,
        val throwable: Throwable? = null,
    ) : FluxMessageEvent()

    /** 一般信息提示 */
    data class Info(override val message: String) : FluxMessageEvent()
}
