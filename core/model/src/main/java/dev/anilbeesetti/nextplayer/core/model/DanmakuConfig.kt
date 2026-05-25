package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DanmakuConfig(
    val enabled: Boolean = false,
    /** 不透明度 0.0~1.0，默认 1.0（100%） */
    val opacity: Float = 1.0f,
    val fontSize: Int = 18,
    /** 显示模式：0=不显示, 1-8=视频高度1/8, 9=满屏 */
    val displayMode: Int = 5,
    /** 速度倍率，默认 ×1.0 */
    val speed: Float = 1.0f,
    /** 时间轴偏移毫秒（负=提前，正=延后），默认 0 */
    val timeOffsetMs: Int = 0,
    /** 轨道间距（dp），默认 5 */
    val trackSpacingDp: Int = 5,
    /** 底部安全区域高度（px），控制栏区域弹幕不进入 */
    val bottomSafeAreaPx: Int = 100,
    /** 同屏最大活跃弹幕数，超出的自动丢弃 */
    val maxDanmakuCount: Int = 100,
    /** 目标帧率（0=自动跟随屏幕） */
    val targetFps: Int = 0,
) {
    /** 根据 displayMode 计算显示区域比例 0.0~1.0 */
    val displayAreaRatio: Float
        get() = when {
            displayMode <= 0 -> 0f          // 不显示
            displayMode >= 9 -> 1f          // 满屏
            else -> displayMode / 8f        // 1/8 ~ 8/8
        }

    /** 速度倍率对应的档位标签 */
    companion object {
        val SPEED_OPTIONS = listOf(
            2.00f to "×2",
            1.25f to "×1.25",
            1.00f to "×1.0",
            0.83f to "×0.83",
            0.67f to "×0.67",
            0.50f to "×0.5",
            0.41f to "×0.41",
        )
        val OPACITY_OPTIONS = listOf(
            1.00f to "100%",
            0.90f to "90%",
            0.80f to "80%",
            0.70f to "70%",
            0.60f to "60%",
            0.50f to "50%",
            0.40f to "40%",
            0.30f to "30%",
        )
        val DISPLAY_MODE_OPTIONS = listOf(
            0 to "不显示",
            1 to "1",
            2 to "2",
            3 to "3",
            5 to "5",
            8 to "8",
            9 to "满屏",
        )
        val TIME_OFFSET_OPTIONS = listOf(
            -10000 to "-10s",
            -5000 to "-5s",
            -1000 to "-1s",
            0 to "0",
            1000 to "+1s",
            5000 to "+5s",
            10000 to "+10s",
        )
        val TRACK_SPACING_OPTIONS = listOf(
            0 to "0",
            5 to "5",
            10 to "10",
            15 to "15",
            20 to "20",
            30 to "30",
            50 to "50",
        )
        val FPS_OPTIONS = listOf(
            0 to "自动",
            60 to "60Hz",
            90 to "90Hz",
            120 to "120Hz",
            144 to "144Hz",
        )
    }
}
