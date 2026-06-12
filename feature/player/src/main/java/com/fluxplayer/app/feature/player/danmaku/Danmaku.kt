package com.fluxplayer.app.feature.player.danmaku

import android.graphics.Color

/**
 * A single danmaku (bullet comment) to be rendered on the video.
 *
 * @property timeMs 显示时间（相对于视频开始，毫秒）
 * @property text 弹幕文本
 * @property mode 弹幕类型：1=滚动(R2L), 4=底部固定, 5=顶部固定
 * @property fontSize 字号（像素，渲染时可能根据密度缩放）
 * @property color ARGB 颜色值
 * @property index 排序/唯一标识
 */
data class Danmaku(
    val timeMs: Long,
    val text: String,
    val mode: Int = 1,           // 1=滚动, 4=底部, 5=顶部
    val fontSize: Float = 25f,    // B站默认字号
    val color: Int = Color.WHITE,
    val index: Int = 0,
) {
    companion object {
        const val MODE_SCROLL = 1
        const val MODE_BOTTOM = 4
        const val MODE_TOP = 5
    }
}
