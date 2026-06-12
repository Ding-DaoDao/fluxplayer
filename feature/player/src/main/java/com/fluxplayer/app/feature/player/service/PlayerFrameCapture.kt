package com.fluxplayer.app.feature.player.service

import java.util.concurrent.ConcurrentHashMap

object PlayerFrameCapture {
    private val map = ConcurrentHashMap<String, String>()

    fun put(uriString: String, filePath: String) {
        map[uriString] = filePath
    }

    fun take(uriString: String): String? = map.remove(uriString)
}
