package com.fluxplayer.app.feature.player.danmaku

/**
 * 运行时的弹幕实例。
 *
 * 位置维护两个机制：
 * - **delta 逐帧移动** ([move])：保证 60fps 平滑动画
 * - **时间戳校正** ([resetPosition])：在 `currentTimeMs` 更新时修正累积误差
 */
class ActiveDanmaku(
    /** 原始弹幕数据 */
    val danmaku: Danmaku,
    /** 分配的轨道索引（仅滚动弹幕） */
    var trackIndex: Int = -1,
    /** 进入时间（播放时间 ms） */
    val startTimeMs: Long,
    /** 文本在 Paint 中测得的宽度（px） */
    val textWidth: Float,
    /** y 坐标（px，相对于视图顶边缘，分配轨道时确定） */
    var y: Float = 0f,
    /** 弹幕类型 */
    val mode: Int = Danmaku.MODE_SCROLL,
    /** 初始 x 偏移（跨轨间距用），默认从 viewWidth 出发 */
    val initialX: Float = Float.MAX_VALUE,
) {
    /** 当前 x 坐标（px，相对于视图左边缘） */
    var x: Float = Float.MAX_VALUE

    /** 是否已完全移出屏幕 */
    var isOffScreen: Boolean = false
        private set

    /** 是否已过期（顶部/底部弹幕用） */
    var isExpired: Boolean = false
        private set

    /** delta 逐帧移动 */
    fun move(deltaMs: Float, speedPxPerSec: Float, viewWidth: Float) {
        if (x == Float.MAX_VALUE) {
            x = if (initialX != Float.MAX_VALUE) initialX else viewWidth
        }
        x -= speedPxPerSec * deltaMs / 1000f
        if (x + textWidth < -20f) isOffScreen = true
    }

    /** 从播放时间戳重新计算位置（seek/校正用） */
    fun resetPosition(currentTimeMs: Long, speedPxPerSec: Float, viewWidth: Float) {
        val startX = if (initialX != Float.MAX_VALUE) initialX else viewWidth
        val elapsedMs = currentTimeMs - startTimeMs
        x = startX - speedPxPerSec * maxOf(elapsedMs, 0L) / 1000f
        if (x + textWidth < -20f) isOffScreen = true
    }

    /** 检查顶部/底部弹幕是否已到过期时间。 */
    fun checkExpiry(currentTimeMs: Long, durationMs: Long): Boolean {
        if (currentTimeMs - startTimeMs > durationMs) {
            isExpired = true
            return false
        }
        return true
    }

    companion object {
        /** 顶部/底部弹幕的默认显示时长（ms） */
        const val FIXED_DURATION_MS = 4000L
    }
}
