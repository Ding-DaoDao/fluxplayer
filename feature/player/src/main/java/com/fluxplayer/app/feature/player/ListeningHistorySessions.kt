package com.fluxplayer.app.feature.player

/** 主线程同步重置匹配的播放会话，清除进度前先停止旧会话的周期保存。 */
object ListeningHistorySessions {
    private data class Session(val key: () -> String?, val reset: () -> Unit)
    private val sessions = mutableMapOf<Any, Session>()

    fun register(owner: Any, key: () -> String?, reset: () -> Unit) {
        sessions[owner] = Session(key, reset)
    }

    fun unregister(owner: Any) {
        sessions.remove(owner)
    }

    fun reset(key: String? = null) {
        sessions.values.toList().forEach { session ->
            val active = session.key()
            if (active != null && (key == null || key == active)) session.reset()
        }
    }
}
