package com.fluxplayer.app.core.tingshu

/** 删除记录只影响最近收听列表，再次收听后自动恢复，续播进度保持不变。 */
data class ListeningHistoryVisibility(
    val removedAt: Map<String, Long> = emptyMap(),
    val clearedAt: Long = -1L,
) {
    fun contains(key: String, playedAt: Long): Boolean = playedAt > maxOf(clearedAt, removedAt[key] ?: -1L)

    fun remove(key: String, now: Long) = copy(removedAt = removedAt + (key to now))

    fun clear(now: Long) = ListeningHistoryVisibility(clearedAt = now)
}
