package com.fluxplayer.app.core.data.quark

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 原画取址去重只在同一登录会话和文件内生效，失败结果不缓存。 */
internal class QuarkOriginalPlaybackCache(private val now: () -> Long = System::currentTimeMillis) {
    private class Entry {
        val mutex = Mutex()
        var url: String? = null
        var at: Long = 0
    }
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    private fun entry(key: String): Entry {
        while (entries.size >= 128 && key !in entries) {
            val oldest = entries.entries.firstOrNull { !it.value.mutex.isLocked } ?: break
            entries.remove(oldest.key)
        }
        return entries.getOrPut(key) { Entry() }
    }

    suspend fun resolve(owner: String, fid: String, load: suspend () -> String): String {
        val entry = entry("$owner:$fid")
        return entry.mutex.withLock {
            val cached = entry.url
            if (cached != null && now() - entry.at in 0 until 60_000) return@withLock cached
            load().also {
                entry.url = it
                entry.at = now()
            }
        }
    }
}
