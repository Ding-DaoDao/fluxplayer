package com.fluxplayer.app.core.tingshu

/** 删除时间控制列表可见性；相应续播与章节进度由仓库同步清除，再次收听会生成新记录。 */
data class ListeningHistoryVisibility(
    val removedAt: Map<String, Long> = emptyMap(),
    val clearedAt: Long = -1L,
) {
    fun contains(key: String, playedAt: Long): Boolean = playedAt > maxOf(clearedAt, removedAt[key] ?: -1L)

    fun remove(key: String, now: Long) = copy(removedAt = removedAt + (key to now))

    fun clear(now: Long) = ListeningHistoryVisibility(clearedAt = now)
}
