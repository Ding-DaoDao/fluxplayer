package com.fluxplayer.app.feature.player.danmaku

import android.util.Log

private const val TAG = "DanmakuTrackAllocator"

/**
 * 弹幕轨道分配器 —— 像素位置跟踪版。
 *
 * 每条轨道维护当前弹幕的像素位置和最后移动时间，
 * 当弹幕 x 坐标移出屏幕后轨道释放；否则新的弹幕进入统一 Pending 队列，
 * 等轨道释放后通过 [tryAllocatePending] 自动补充。
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

    // ── 像素跟踪数组（固定 64 槽位）──────────────────

    /** 每条轨道上当前弹幕的宽度（-1f 表示空闲） */
    private val trackItemWidth = FloatArray(64) { -1f }

    /** 每条轨道上当前弹幕的 x 坐标 */
    private val trackItemX = FloatArray(64)

    /** 每条轨道上当前弹幕的最后移动时间（ms） */
    private val trackItemLastMoveTime = LongArray(64)

    /** 统一 Pending 队列（最多 50 条） */
    private val pendingQueue = ArrayDeque<PendingDanmaku>()

    /** 上次分配轨道的偏移（round-robin） */
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
        trackHeight = rawTrackHeight + trackSpacingPx
        topPadding = (viewHeight * 0.08f).toInt().coerceAtLeast(30)

        trackCount = when {
            displayMode <= 0 -> 0
            displayMode >= 9 -> {
                val availableHeight = viewHeight - topPadding - bottomMargin
                (availableHeight / trackHeight).toInt().coerceAtLeast(1)
            }
            else -> displayMode
        }

        Log.d(TAG, "reconfigure: $trackCount tracks x ${trackHeight.toInt()}px " +
                "(raw=${rawTrackHeight.toInt()}+spacing=$trackSpacingPx), " +
                "view=${viewWidth.toInt()}x${viewHeight.toInt()} " +
                "displayMode=$displayMode")
    }

    /**
     * 为一条新的滚动弹幕分配轨道。
     * 如果所有轨道都被占用，将弹幕放入统一 pending 队列（最多 50 条）。
     *
     * @param danmakuWidth 弹幕文本宽度
     * @param viewWidth 视图宽度
     * @param speedPxPerSec 滚动速度
     * @param currentTimeMs 当前时间
     * @param pendingDanmaku 弹幕数据引用（用于 Pending 队列恢复）
     * @return 分配的轨道索引，-1 表示已入 pending 队列
     */
    fun allocateScrollTrack(
        danmakuWidth: Float,
        viewWidth: Float,
        speedPxPerSec: Float,
        currentTimeMs: Long,
        pendingDanmaku: Danmaku? = null,
    ): Int {
        if (trackCount == 0) return -1

        val minGap = danmakuWidth * 0.2f

        // 尝试找一个空闲轨道或已有弹幕右边缘 + 新弹幕 + minGap 不超出屏幕的轨道
        for (i in 0 until trackCount) {
            val track = (i + nextTrackOffset) % trackCount

            if (trackItemWidth[track] < 0f) {
                // 轨道空闲
                occupyTrack(track, danmakuWidth, viewWidth, currentTimeMs)
                nextTrackOffset = (nextTrackOffset + 1) % trackCount
                return track
            }

            // 轨道有弹幕，检查右边缘是否已经留出足够空间
            val prevRightEdge = trackItemX[track] + trackItemWidth[track]
            if (prevRightEdge + danmakuWidth + minGap <= viewWidth) {
                occupyTrack(track, danmakuWidth, viewWidth, currentTimeMs)
                nextTrackOffset = (nextTrackOffset + 1) % trackCount
                return track
            }
        }

        // 所有轨道都忙，放入统一 pending 队列
        if (pendingQueue.size < 50 && pendingDanmaku != null) {
            pendingQueue.addLast(PendingDanmaku(pendingDanmaku, danmakuWidth, currentTimeMs))
        }

        return -1
    }

    /**
     * 移动轨道上的弹幕项，基于实际时间差（而非帧 delta）。
     */
    fun moveTrackItems(speedPxPerSec: Float, deltaMs: Float, currentTimeMs: Long) {
        for (track in 0 until trackCount) {
            if (trackItemWidth[track] >= 0f) {
                val elapsedMs = if (trackItemLastMoveTime[track] > 0) {
                    (currentTimeMs - trackItemLastMoveTime[track]).toFloat().coerceIn(0f, 100f)
                } else {
                    deltaMs
                }
                trackItemX[track] -= speedPxPerSec * elapsedMs / 1000f
                trackItemLastMoveTime[track] = currentTimeMs
            }
        }
    }

    /** 是否有 pending 弹幕等待分配 */
    fun hasPending(): Boolean = pendingQueue.isNotEmpty()

    /**
     * 尝试从 pending 队列分配一条弹幕到空闲轨道。
     *
     * @return Pair(轨道索引, PendingDanmaku)，如果没有可分配的返回 null
     */
    fun tryAllocatePending(
        speedPxPerSec: Float,
        currentTimeMs: Long,
        viewWidth: Float,
    ): Pair<Int, PendingDanmaku>? {
        if (pendingQueue.isEmpty() || trackCount == 0) return null

        val pending = pendingQueue.first()
        val minGap = pending.textWidth * 0.2f

        for (i in 0 until trackCount) {
            val track = (i + nextTrackOffset) % trackCount

            if (trackItemWidth[track] < 0f) {
                // 轨道空闲
                trackItemWidth[track] = pending.textWidth
                trackItemX[track] = viewWidth
                trackItemLastMoveTime[track] = currentTimeMs
                nextTrackOffset = (nextTrackOffset + 1) % trackCount
                pendingQueue.removeFirst()
                return Pair(track, pending)
            }

            // 轨道有弹幕，检查右边缘
            val prevRightEdge = trackItemX[track] + trackItemWidth[track]
            if (pending.textWidth + prevRightEdge + minGap <= viewWidth) {
                trackItemWidth[track] = pending.textWidth
                trackItemX[track] = viewWidth
                trackItemLastMoveTime[track] = currentTimeMs
                nextTrackOffset = (nextTrackOffset + 1) % trackCount
                pendingQueue.removeFirst()
                return Pair(track, pending)
            }
        }
        return null
    }

    /** 清除所有轨道状态 */
    fun clear() {
        trackItemWidth.fill(-1f)
        trackItemX.fill(0f)
        trackItemLastMoveTime.fill(0L)
        pendingQueue.clear()
        nextTrackOffset = 0
    }

    // ── 内部方法 ──────────────────────────────────────

    private fun occupyTrack(track: Int, width: Float, viewWidth: Float, timeMs: Long) {
        trackItemWidth[track] = width
        trackItemX[track] = viewWidth
        trackItemLastMoveTime[track] = timeMs
    }

    /** Pending 弹幕条目（包含弹幕数据引用，用于恢复 ActiveDanmaku） */
    data class PendingDanmaku(
        val danmaku: Danmaku,
        val textWidth: Float,
        val timeMs: Long,
    )
}
