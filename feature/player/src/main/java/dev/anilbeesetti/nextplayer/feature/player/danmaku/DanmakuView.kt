package dev.anilbeesetti.nextplayer.feature.player.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max

private const val TAG = "DanmakuView"

/**
 * 自定义弹幕渲染 SurfaceView。
 *
 * 参考 B站 DanmakuFlameMaster 设计：
 * - **deltaMs 逐帧移动**：保证 60fps 平滑动画
 * - **时间戳校正** ([resetPosition])：在 [setPlayerTime] 时修正累积误差
 * - **跨轨偏移** ([initialX])：同轨道连续弹幕保持 [textWidth] 间距
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : SurfaceView(context, attrs, defStyle),
    SurfaceHolder.Callback,
    Choreographer.FrameCallback {

    // ── 配置 ──────────────────────────────────────────────
    /** 滚动速度（px/s） */
    var scrollSpeedPxPerSec: Float = 200f
        private set

    /** 滚动弹幕横穿屏幕的期望秒数 */
    var crossDurationSec: Float = 8f

    /** 速度倍率（来自用户设置） */
    var speedMultiplier: Float = 1f

    /** 播放速度倍率（与视频播放速度联动） */
    var playbackSpeed: Float = 1.0f

    /** 底部安全区（px） */
    var bottomMargin: Int = 0

    /** 同屏最大活跃弹幕数 */
    var maxActiveDanmaku: Int = 100

    /** 弹幕不透明度 0..1 */
    var danmakuOpacity: Float = 0.8f

    /** 显示密度，用于缩放字号 */
    var displayDensity: Float = 1f

    /** 弹幕密度 0.0~1.0，用于控制弹幕显示比例 */
    var danmakuDensity: Float = 1.0f

    /** 屏蔽关键词列表 */
    var blockKeywords: List<String> = emptyList()

    /** 显示模式：0=不显示, 1-8=轨道数, 9=满屏 */
    var displayMode: Int = 5

    /** 时间轴偏移（ms），负=提前，正=延后 */
    var timeOffsetMs: Long = 0L

    /** 目标帧率（0=自动跟随屏幕） */
    var targetFps: Int = 0
    private var lastFrameIntervalNs: Long = 0L
    private var nextFrameTimeNs: Long = 0L

    /** 轨道间距（px，已乘 density） */
    var trackSpacingPx: Int = 0

    /** 基准字号（px），用于轨道高度 */
    var baseFontSizePx: Float = 36f

    /** 是否暂停 */
    @Volatile
    var isPaused: Boolean = false

    // ── 数据 ──────────────────────────────────────────────
    private var allDanmaku: List<Danmaku> = emptyList()
    private var nextEmitIndex: Int = 0
    private val activeDanmaku = mutableListOf<ActiveDanmaku>()
    private var currentTimeMs: Long = 0L
    private var lastFrameTimeNs: Long = 0L
    @Volatile
    private var isRunning: Boolean = false
    private var isSurfaceValid: Boolean = false

    private val trackAllocator = DanmakuTrackAllocator()
    private val paintCache = HashMap<Float, Paint>()
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private var frameCount = 0
    private var fpsTimer = 0L
    private var scrollEmittedThisFrame = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 初始化 ─────────────────────────────────────────────
    init {
        holder.addCallback(this)
        setZOrderOnTop(true)
        holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)
    }

    // ── 公开 API ──────────────────────────────────────────

    fun setDanmakuList(list: List<Danmaku>) {
        allDanmaku = list.sortedBy { it.timeMs }
        nextEmitIndex = 0
        activeDanmaku.clear()
        trackAllocator.clear()
        Log.d(TAG, "setDanmakuList: ${list.size} items, first=${list.firstOrNull()?.let { "${it.timeMs}ms '${it.text.take(20)}'" }}")
    }

    /** 设置当前播放时间。位置由 move() 逐帧维护，不在此校正。 */
    fun setPlayerTime(timeMs: Long) {
        currentTimeMs = timeMs
    }

    fun seekTo(timeMs: Long) {
        currentTimeMs = timeMs
        activeDanmaku.clear()
        trackAllocator.clear()
        nextEmitIndex = allDanmaku.indexOfFirst { it.timeMs >= timeMs }.coerceAtLeast(0)
        Log.d(TAG, "seekTo: $timeMs ms, nextEmit=$nextEmitIndex/${allDanmaku.size}")
    }

    fun pausePlayback() {
        isPaused = true
    }

    fun resumePlayback() {
        isPaused = false
    }

    fun sendDanmakuNow(danmaku: Danmaku) {
        val textWidth = measureTextWidth(danmaku.text, danmaku.fontSize)
        val viewW = width.toFloat()

        if (danmaku.mode != Danmaku.MODE_SCROLL) return

        val track = trackAllocator.allocateScrollTrack(
            textWidth, viewW, scrollSpeedPxPerSec, currentTimeMs, pendingDanmaku = danmaku,
        )
        if (track < 0) return
        val active = ActiveDanmaku(
            danmaku = danmaku,
            y = trackAllocator.topPadding + track * trackAllocator.trackHeight +
                    trackAllocator.trackHeight * 0.2f,
            trackIndex = track,
            startTimeMs = currentTimeMs,
            textWidth = textWidth,
            mode = Danmaku.MODE_SCROLL,
        ).also { it.x = viewW }
        activeDanmaku.add(active)
    }

    // ── SurfaceHolder.Callback ────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceValid = true
        reconfigure()
        startRenderLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        reconfigure()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceValid = false
        stopRenderLoop()
    }

    // ── Choreographer.FrameCallback ───────────────────────

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning || !isSurfaceValid) return

        // 帧率限制：跳过过早的帧，达到目标帧率
        if (targetFps > 0 && frameTimeNanos < nextFrameTimeNs) {
            if (isRunning) {
                Choreographer.getInstance().postFrameCallback(this)
            }
            return
        }
        if (targetFps > 0) {
            val intervalNs = 1_000_000_000L / targetFps
            lastFrameIntervalNs = intervalNs
            nextFrameTimeNs = frameTimeNanos + intervalNs
        }

        try {
            val deltaMs = if (lastFrameTimeNs == 0L) {
                16f
            } else {
                ((frameTimeNanos - lastFrameTimeNs) / 1_000_000).toFloat()
            }.coerceIn(1f, 100f)
            lastFrameTimeNs = frameTimeNanos

            // 暂停时不移动弹幕，但仍绘制（弹幕停在当前位置）
            if (!isPaused) {
                update(deltaMs)
            }
            drawFrame()
        } catch (e: Exception) {
            Log.e(TAG, "doFrame error", e)
        }

        if (isRunning) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ── 核心循环 ──────────────────────────────────────────

    private fun update(deltaMs: Float) {
        val viewW = width.toFloat().coerceAtLeast(1f)

        // 暂停时不移动弹幕
        if (isPaused) return

        // 1) 逐帧移动活跃弹幕 + 回收出屏弹幕
        val iter = activeDanmaku.listIterator()
        while (iter.hasNext()) {
            val ad = iter.next()
            ad.move(deltaMs, scrollSpeedPxPerSec * playbackSpeed, viewW)
            if (ad.isOffScreen) {
                iter.remove()
            }
        }

        // 2) 同步移动 trackAllocator（基于时间差的像素跟踪）
        trackAllocator.moveTrackItems(scrollSpeedPxPerSec * playbackSpeed, deltaMs, currentTimeMs)

        // 3) 发放新弹幕
        emitNewDanmaku()

        // 4) 从 pending 队列补充（最多 3 条/帧）
        drainPendingDanmaku(viewW)

        // 5) FPS 统计
        frameCount++
        logRenderStats()
        if (fpsTimer == 0L) {
            fpsTimer = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - fpsTimer > 5000) {
            Log.d(TAG, "FPS: ${frameCount / 5}  active=${activeDanmaku.size}")
            frameCount = 0
            fpsTimer = System.currentTimeMillis()
        }
    }

    private companion object {
        const val MAX_SCROLL_EMIT_PER_FRAME = 5
    }

    /** 发放当前时间点应该显示的弹幕 */
    private fun emitNewDanmaku() {
        if (nextEmitIndex >= allDanmaku.size) {
            if (allDanmaku.isNotEmpty() && frameCount % 60 == 0) {
                Log.w(TAG, "emitNewDanmaku: exhausted nextEmit=$nextEmitIndex " +
                        "total=${allDanmaku.size} time=${currentTimeMs}ms")
            }
            return
        }

        val viewW = width.toFloat().coerceAtLeast(1f)
        // 应用时间轴偏移后的播放时间
        val adjustedTimeMs = currentTimeMs + timeOffsetMs

        scrollEmittedThisFrame = 0

        while (nextEmitIndex < allDanmaku.size) {
            val danmaku = allDanmaku[nextEmitIndex]
            if (danmaku.timeMs > adjustedTimeMs + 1000) break
            nextEmitIndex++

            // 非滚动弹幕跳过
            if (danmaku.mode != Danmaku.MODE_SCROLL) continue
            // 超过 30s 的弹幕跳过
            if (adjustedTimeMs - danmaku.timeMs > 30_000) continue

            // 密度过滤：根据 danmakuDensity 使用 hash 方式随机丢弃部分弹幕
            if (danmakuDensity < 1.0f) {
                val hash = (danmaku.text.hashCode() and 0xFFFF) / 65535f
                if (hash > danmakuDensity) continue
            }

            // 屏蔽词过滤
            if (blockKeywords.isNotEmpty()) {
                val blocked = blockKeywords.any { keyword ->
                    danmaku.text.contains(keyword, ignoreCase = true)
                }
                if (blocked) continue
            }

            try {
                val textWidth = measureTextWidth(danmaku.text, danmaku.fontSize)

                val scrollTrack = trackAllocator.allocateScrollTrack(
                    textWidth, viewW,
                    scrollSpeedPxPerSec * playbackSpeed,
                    adjustedTimeMs,
                    pendingDanmaku = danmaku,
                )
                if (scrollTrack < 0) continue

                // 同轨道同一时间点只显示一条弹幕
                val dup = activeDanmaku.any {
                    it.trackIndex == scrollTrack &&
                    kotlin.math.abs(it.startTimeMs - danmaku.timeMs) < 100
                }
                if (dup) continue

                // 容量和帧上限检查
                if (activeDanmaku.size >= maxActiveDanmaku) continue
                if (scrollEmittedThisFrame >= MAX_SCROLL_EMIT_PER_FRAME) continue
                scrollEmittedThisFrame++

                val active = ActiveDanmaku(
                    danmaku = danmaku,
                    y = trackAllocator.topPadding + scrollTrack * trackAllocator.trackHeight +
                            trackAllocator.trackHeight * 0.2f,
                    trackIndex = scrollTrack,
                    startTimeMs = danmaku.timeMs,
                    textWidth = textWidth,
                    mode = Danmaku.MODE_SCROLL,
                ).also { it.x = viewW }
                activeDanmaku.add(active)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to emit danmaku #$nextEmitIndex", e)
            }
        }
    }

    /**
     * 从 trackAllocator 的 pending 队列中取出弹幕补充到屏幕。
     * 每帧最多 3 条，防止帧率下降。
     */
    private fun drainPendingDanmaku(viewW: Float) {
        if (!trackAllocator.hasPending() || activeDanmaku.size >= maxActiveDanmaku) return

        val adjustedTimeMs = currentTimeMs + timeOffsetMs
        var drained = 0

        while (drained < 3 && activeDanmaku.size < maxActiveDanmaku) {
            val result = trackAllocator.tryAllocatePending(
                scrollSpeedPxPerSec * playbackSpeed,
                adjustedTimeMs,
                viewW,
            ) ?: break

            val (track, pending) = result
            val active = ActiveDanmaku(
                danmaku = pending.danmaku,
                trackIndex = track,
                startTimeMs = adjustedTimeMs,
                textWidth = pending.textWidth,
                mode = Danmaku.MODE_SCROLL,
                y = trackAllocator.topPadding + track * trackAllocator.trackHeight +
                        trackAllocator.trackHeight * 0.2f,
            ).also { it.x = viewW }
            activeDanmaku.add(active)
            drained++
        }
    }

    // ── 绘制 ──────────────────────────────────────────────

    private fun drawFrame() {
        val holder = holder ?: return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas == null) return
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            for (ad in activeDanmaku) {
                drawDanmaku(canvas, ad)
            }
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame error", e)
        } finally {
            if (canvas != null) {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) { }
            }
        }
    }

    private fun drawDanmaku(canvas: Canvas, ad: ActiveDanmaku) {
        val paint = getOrCreatePaint(ad.danmaku.fontSize, ad.danmaku.color, danmakuOpacity)
        canvas.drawText(ad.danmaku.text, ad.x, ad.y, paint)
    }

    /** 按 B站 规则缩放字号：raw × (density - 0.5) */
    private fun scaleFontSize(rawSize: Float): Float =
        rawSize * max(displayDensity - 0.5f, 1.0f)

    private fun getOrCreatePaint(rawFontSize: Float, color: Int, opacity: Float): Paint {
        val scaledSize = scaleFontSize(rawFontSize)
        var paint = paintCache[scaledSize]
        if (paint == null) {
            paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isAntiAlias = true
                isDither = true
                textSize = scaledSize
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(3f, 1.5f, 1.5f, Color.BLACK)
            }
            paintCache[scaledSize] = paint
        }
        paint.color = color
        paint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        return paint
    }

    private fun measureTextWidth(text: String, rawFontSize: Float): Float {
        measurePaint.textSize = scaleFontSize(rawFontSize)
        return measurePaint.measureText(text)
    }

    // ── 生命周期 ──────────────────────────────────────────

    private fun startRenderLoop() {
        if (isRunning) return
        isRunning = true
        lastFrameTimeNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
        Log.d(TAG, "Render loop started (allDanmaku=${allDanmaku.size} items)")
    }

    private fun logRenderStats() {
        if (frameCount % 300 == 0) { // ~每5秒
            Log.d(TAG, "Render stats: frame=$frameCount active=${activeDanmaku.size} " +
                    "nextEmit=$nextEmitIndex/${allDanmaku.size} currentTime=${currentTimeMs}ms")
        }
    }

    private fun stopRenderLoop() {
        isRunning = false
        Log.d(TAG, "Render loop stopped")
    }

    fun reconfigure() {
        val viewW = max(width, 1).toFloat()
        val viewH = max(height, 1).toFloat()
        scrollSpeedPxPerSec = (viewW / crossDurationSec) * speedMultiplier * playbackSpeed
        trackAllocator.bottomMargin = bottomMargin
        trackAllocator.trackSpacingPx = trackSpacingPx
        trackAllocator.reconfigure(viewW, viewH, baseFontSizePx, displayMode)
    }
}
