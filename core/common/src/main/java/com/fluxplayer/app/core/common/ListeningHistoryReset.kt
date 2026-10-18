package com.fluxplayer.app.core.common

import android.content.Context

/** 删除时间同时用于屏蔽已排队的旧会话保存，避免清除后又写回旧进度。 */
object ListeningHistoryReset {
    fun cutoff(context: Context, key: String): Long {
        val prefs = context.getSharedPreferences("listening_home", Context.MODE_PRIVATE)
        return maxOf(prefs.getLong("historyClearedAt", -1), prefs.getLong("removed.$key", -1))
    }

    fun record(context: Context, key: String?, now: Long) {
        val prefs = context.getSharedPreferences("listening_home", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (key == null) {
            editor.putLong("historyClearedAt", now)
            prefs.all.keys.filter { it.startsWith("removed.") }.forEach(editor::remove)
        } else {
            editor.putLong("removed.$key", now)
        }
        check(editor.commit()) { "清除收听记录失败" }
    }

    fun accepts(playedAt: Long?, cutoff: Long): Boolean = cutoff < 0 || playedAt != null && playedAt > cutoff
}
