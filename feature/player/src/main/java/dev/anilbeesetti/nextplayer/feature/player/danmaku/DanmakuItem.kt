package dev.anilbeesetti.nextplayer.feature.player.danmaku

import android.graphics.Color

/**
 * A single danmaku (bullet comment) item to be rendered on screen.
 */
data class DanmakuItem(
    val text: String,
    val timeMs: Long,
    val type: DanmakuType = DanmakuType.SCROLL,
    val color: Int = Color.WHITE,
    val fontSize: Int? = null,
)

enum class DanmakuType {
    /** 滚动弹幕，从右到左 */
    SCROLL,
    /** 顶部弹幕，居中固定 */
    TOP,
    /** 底部弹幕，居中固定 */
    BOTTOM,
}
