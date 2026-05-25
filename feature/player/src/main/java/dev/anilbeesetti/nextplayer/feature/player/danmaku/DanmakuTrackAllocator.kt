package dev.anilbeesetti.nextplayer.feature.player.danmaku

import android.util.Log

private const val TAG = "DanmakuTrackAllocator"

/**
 * 弹幕轨道分配器。
 *
 * 所有轨道全部用于滚动弹幕，采用 **时间间隔** 控制：
 * 同一条轨道内的连续弹幕至少间隔 `textWidth × 2 / scrollSpeed` 毫秒，
 * 确保前一条至少移出两个身位后下一条才进入。
 */
class DanmakuTrackAllocator {

    /** 当前轨道数 */
    var trackCount: Int = 0
        private set

    /** 每个轨道的高度（px） */
    var trackHeight: Float = 0f
        private set

    /** 线条高度比例 = 字号 × 此值 */
    var lineHeightRatio: Float = 1.4f

    /** 额外轨道间距（px），用于用户设置的 trackSpacing */
    var trackSpacingPx: Int = 0

    /** 安全区（底部避让高度，px） */
    var bottomMargin: Int = 0

    /** 顶部内边距（px） */
    var topPadding: Int = 0

    /** 每条轨道的下一次可用时间（ms） */
    private val trackNextEntryTime = mutableMapOf<Int, Long>()

    /** 滚动弹幕 round-robin 分配偏移 */
    private var nextTrackOffset: Int = 0

    /**
     * 当视图尺寸或配置改变时调用。
     *
     * @param displayMode 显示模式：0=不显示, 1-8=直接对应轨道数, 9=满屏
     */
    fun reconfigure(
        viewWidth: Float,
        viewHeight: Float,
        fontSize: Float,
        displayMode: Int = 5,
    ) {
        val rawTrackHeight = fontSize * lineHeightRatio
        // 轨道高度 = 文字行高 + 用户设置的间距
        trackHeight = rawTrackHeight + trackSpacingPx
        topPadding = (viewHeight * 0.08f).toInt().coerceAtLeast(30)

        trackCount = when {
            displayMode <= 0 -> 0
            displayMode >= 9 -> {
                // 满屏：按剩余空间算出最大轨道数
                val availableHeight = viewHeight - topPadding - bottomMargin
                (availableHeight / trackHeight).toInt().coerceAtLeast(1)
            }
            else -> {
                // 1~8：直接使用 displayMode 作为轨道数
                displayMode
            }
        }

        Log.d(TAG, "reconfigure: $trackCount tracks x ${trackHeight.toInt()}px " +
                "(raw=${rawTrackHeight.toInt()}+spacing=$trackSpacingPx), " +
                "view=${viewWidth.toInt()}x${viewHeight.toInt()} " +
                "displayMode=$displayMode")
    }

    /**
     * 为一条新的滚动弹幕分配轨道。
     */
    fun allocateScrollTrack(
        danmakuWidth: Float,
        viewWidth: Float,
        speedPxPerSec: Float,
        currentTimeMs: Long,
    ): Int {
        if (trackCount == 0) return -1

        // 最小间隔：弹幕移动 2× textWidth，加轨道偏移防同时解锁
        val baseMs = if (speedPxPerSec > 0f) {
            (danmakuWidth * 2f / speedPxPerSec * 1000f).toLong().coerceIn(50, 5000)
        } else {
            500L
        }

        for (i in 0 until trackCount) {
            val track = (i + nextTrackOffset) % trackCount
            val nextAvail = trackNextEntryTime[track] ?: 0L
            if (currentTimeMs >= nextAvail) {
                val trackOffset = (track * 80L)
                trackNextEntryTime[track] = currentTimeMs + baseMs + trackOffset
                nextTrackOffset = (nextTrackOffset + 1) % trackCount
                return track
            }
        }

        return -1
    }

    /** 清除所有轨道状态 */
    fun clear() {
        trackNextEntryTime.clear()
    }
}
