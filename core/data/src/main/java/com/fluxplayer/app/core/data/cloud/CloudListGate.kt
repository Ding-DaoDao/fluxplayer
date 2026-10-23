package com.fluxplayer.app.core.data.cloud

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 正常浏览和搜索共用分页队列，避免两个目录同时翻页触发服务端限流。 */
internal class CloudListGate(private var intervalMs: Long, private val afterCompletion: Boolean = false) {
    private val mutex = Mutex()
    private var nextAt = 0L
    suspend fun <T> run(read: suspend () -> T): T = mutex.withLock {
        val remaining = nextAt - System.nanoTime() / 1_000_000
        if (remaining > 0) delay(remaining)
        nextAt = System.nanoTime() / 1_000_000 + intervalMs
        try {
            read()
        } catch (error: Exception) {
            if (listOf("429", "频繁", "TooManyRequests").any { error.message.orEmpty().contains(it, true) }) {
                intervalMs = maxOf(intervalMs, 1000)
                nextAt = System.nanoTime() / 1_000_000 + 2000
            }
            throw error
        } finally {
            if (afterCompletion) nextAt = maxOf(nextAt, System.nanoTime() / 1_000_000 + intervalMs)
        }
    }
}
