package dev.anilbeesetti.nextplayer.feature.player.danmaku

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player

private const val TAG = "DanmakuController"

/**
 * 弹幕控制器 —— 监听 [Player] 状态，驱动 [DanmakuView] 与播放同步。
 *
 * ## 职责
 * - 每 100ms 轮询播放进度，将到时的弹幕注入 [DanmakuView]
 * - 处理 seek：清空屏幕并跳转到新位置
 * - 处理暂停/恢复：暂停时停止注入新弹幕（已有弹幕继续移出）
 * - 处理播放结束：清空弹幕
 */
class DanmakuController(
    private val danmakuView: DanmakuView,
    /** 当播放器切换媒体项时回调，isForward 表示是否向前切换 */
    val onMediaItemTransitioned: ((Boolean) -> Unit)? = null,
) : Player.Listener {

    /** 注入的播放器实例 */
    private var player: Player? = null

    /** 轮询 Handler */
    private val pollHandler = Handler(Looper.getMainLooper())

    /** 是否正在轮询 */
    private var isPolling = false

    /** 轮询间隔（ms） */
    private var pollIntervalMs: Long = 100L

    /** 是否已暂停（用户暂停，不是缓冲） */
    private var isPausedByUser = false

    /** 上次已知位置（用于检测 seek） */
    private var lastPositionMs: Long = -1L

    /** seek 去抖 */
    private var pendingSeek: Long? = null
    private val seekDebounceMs: Long = 80L

    /** 弹幕列表（解析后的数据） */
    private var danmakuList: List<Danmaku> = emptyList()

    /** 标记弹幕数据是否已注入视图（防重复 reset） */
    private var danmakuLoaded = false

    /** 上次媒体项索引（用于检测切集） */
    private var previousMediaItemIndex: Int = -1

    // ── 公开 API ──────────────────────────────────────────

    /**
     * 加载弹幕数据并开始同步。
     *
     * 可被 `update` 块重复调用 —— 列表变化时自动重载。
     */
    fun loadDanmaku(player: Player, list: List<Danmaku>) {
        Log.d(TAG, "loadDanmaku: list.size=${list.size} danmakuLoaded=$danmakuLoaded " +
                "currentList=${this.danmakuList.size} sameRef=${this.danmakuList === list}")

        // 1) 更新 Player 监听
        if (this.player !== player) {
            Log.d(TAG, "loadDanmaku: player changed ${this.player} -> ${player}")
            this.player?.removeListener(this)
            this.player = player
            player.addListener(this)
        } else if (!danmakuLoaded) {
            // 首次加载时才加监听（避免重复）
            player.addListener(this)
        }

        // 2) 列表没变且已加载 → 跳过
        if (danmakuLoaded && this.danmakuList === list) {
            Log.d(TAG, "loadDanmaku: same list, skip")
            return
        }

        // 3) 设置新列表
        this.danmakuList = list
        danmakuLoaded = true
        Log.d(TAG, "loadDanmaku: setting ${list.size} items")
        danmakuView.setDanmakuList(list)
        danmakuView.reconfigure()

        // 4) 同步到当前位置
        val pos = player.currentPosition
        Log.d(TAG, "loadDanmaku: seekTo pos=$pos isPlaying=${player.isPlaying}")
        danmakuView.seekTo(pos)
        if (player.isPlaying) {
            startPolling()
        }
    }

    /**
     * 释放控制器，停止所有回调。
     */
    fun release() {
        player?.removeListener(this)
        stopPolling()
        pollHandler.removeCallbacksAndMessages(null)
        player = null
        danmakuLoaded = false
        danmakuView.setDanmakuList(emptyList())
    }

    /** 切换暂停/播放（由外部 UI 触发时调用） */
    fun setPaused(paused: Boolean) {
        isPausedByUser = paused
        if (paused) {
            stopPolling()
        } else {
            player?.let { if (it.isPlaying) startPolling() }
        }
    }

    /** 用户发送一条弹幕时调用 */
    fun sendDanmaku(danmaku: Danmaku) {
        danmakuView.sendDanmakuNow(danmaku)
    }

    /** 更新播放速度（与视频速度联动） */
    fun updatePlaybackSpeed(speed: Float) {
        danmakuView.playbackSpeed = speed
        danmakuView.reconfigure()
    }

    /** 获取当前的播放时间 */
    private fun getPlayerPosition(): Long {
        return player?.currentPosition ?: 0L
    }

    // ── Player.Listener ───────────────────────────────────

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        val currentIndex = player?.currentMediaItemIndex ?: -1
        val isForward = currentIndex > previousMediaItemIndex
        Log.d(TAG, "onMediaItemTransition: reason=$reason, " +
                "prev=$previousMediaItemIndex -> current=$currentIndex (forward=$isForward) — clearing danmaku")

        // 去重：同一媒体项不重复清理
        if (currentIndex == previousMediaItemIndex && previousMediaItemIndex >= 0) {
            Log.d(TAG, "onMediaItemTransition: same mediaItem, skip")
            return
        }
        previousMediaItemIndex = currentIndex

        // 切集时清空所有弹幕状态，防止旧弹幕飘在新集上
        danmakuLoaded = false
        danmakuList = emptyList()
        danmakuView.setDanmakuList(emptyList())
        danmakuView.reconfigure()
        stopPolling()
        // 通知外部切集（传递 isForward）
        onMediaItemTransitioned?.invoke(isForward)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Log.d(TAG, "onIsPlayingChanged: $isPlaying (pausedByUser=$isPausedByUser)")
        if (isPlaying && !isPausedByUser) {
            startPolling()
            danmakuView.resumePlayback()
        } else {
            stopPolling()
            danmakuView.pausePlayback()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            handleSeek(newPosition.positionMs)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                if (player?.isPlaying == true) {
                    startPolling()
                }
            }
            Player.STATE_ENDED -> {
                stopPolling()
                danmakuView.seekTo(Long.MAX_VALUE)
            }
        }
    }

    // ── 内部 ──────────────────────────────────────────────

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        // 立即同步一次
        syncPosition()
        // 开始轮询
        pollHandler.post(pollRunnable)
        Log.d(TAG, "Polling started")
    }

    private fun stopPolling() {
        isPolling = false
        pollHandler.removeCallbacks(pollRunnable)
        Log.d(TAG, "Polling stopped")
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            syncPosition()
            pollHandler.postDelayed(this, pollIntervalMs)
        }
    }

    /** 读取播放器位置并同步到 DanmakuView */
    private fun syncPosition() {
        val pos = getPlayerPosition()
        if (pos < 0) return

        // 检测 seek —— 位置非连续变化
        if (lastPositionMs >= 0 && kotlin.math.abs(pos - lastPositionMs) > 2000) {
            // 发生了跳跃（seek 或广告跳转）
            handleSeek(pos)
            return
        }
        lastPositionMs = pos

        danmakuView.setPlayerTime(pos)
    }

    /** 处理 seek 跳转 */
    private fun handleSeek(positionMs: Long) {
        // 立即标记 lastPositionMs（防 syncPosition 反复检测同一跳转）
        lastPositionMs = positionMs
        // 去抖：短时间内多次 seek 只处理最后一次
        pendingSeek = positionMs
        pollHandler.removeCallbacks(seekRunnable)
        pollHandler.postDelayed(seekRunnable, seekDebounceMs)
    }

    private val seekRunnable = Runnable {
        val pos = pendingSeek ?: return@Runnable
        pendingSeek = null
        Log.d(TAG, "Seek settled: ${pos}ms")
        danmakuView.seekTo(pos)
    }
}
