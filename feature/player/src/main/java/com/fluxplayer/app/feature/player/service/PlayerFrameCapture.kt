package com.fluxplayer.app.feature.player.service

import java.util.concurrent.ConcurrentHashMap

/**
 * 播放器帧截图的临时共享通道：media 侧 put，播放器侧 take。
 * take() 之外没有消费路径，用容量上限防止异常路径下无限累积。
 */
object PlayerFrameCapture {
    private const val MAX_ENTRIES = 50

    private val map = ConcurrentHashMap<String, String>()

    fun put(uriString: String, filePath: String) {
        if (map.size >= MAX_ENTRIES) {
            map.clear()
        }
        map[uriString] = filePath
    }

    fun take(uriString: String): String? = map.remove(uriString)
}
