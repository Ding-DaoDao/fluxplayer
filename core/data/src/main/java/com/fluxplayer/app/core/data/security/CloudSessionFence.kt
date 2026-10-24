package com.fluxplayer.app.core.data.security

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/** 同一网盘的登录、退出和后台续期共用版本栅栏，旧请求不能写回新会话。 */
object CloudSessionFence {
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private class Entry {
        var revision = sequence.incrementAndGet()
        val refresh = Mutex()
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    private fun entry(provider: String): Entry {
        val base = provider.removeSuffix("-web")
        val key = "$provider@${com.fluxplayer.app.core.common.CloudAccountIdentity.active(base)}"
        return entries.computeIfAbsent(key) { Entry() }
    }

    fun current(provider: String): Long = entry(provider).let { synchronized(it) { it.revision } }

    fun advance(provider: String): Long = entry(provider).let { synchronized(it) { sequence.incrementAndGet().also { revision -> it.revision = revision } } }

    fun checkCurrent(provider: String, expected: Long) {
        check(current(provider) == expected) { "网盘账号已退出或发生变化，请重新操作" }
    }

    fun commit(provider: String, expected: Long, action: () -> Unit): Boolean = entry(provider).let {
        synchronized(it) {
            if (it.revision != expected) return@synchronized false
            action()
            true
        }
    }

    fun refreshMutex(provider: String): Mutex = entry(provider).refresh
}
