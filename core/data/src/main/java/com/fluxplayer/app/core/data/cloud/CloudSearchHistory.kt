package com.fluxplayer.app.core.data.cloud

import android.content.Context
import com.fluxplayer.app.core.data.security.getSecret
import com.fluxplayer.app.core.data.security.putSecret
import org.json.JSONArray
import org.json.JSONObject

/** 历史加密保存；每个账号空间最多十二条，最多保留八十个会话。 */
class CloudSearchHistory(private val load: () -> String?, private val save: (String) -> Unit) {
    private fun data(): LinkedHashMap<String, List<String>> {
        val rows = runCatching { JSONArray(load().orEmpty()) }.getOrElse { JSONArray() }
        return linkedMapOf<String, List<String>>().apply {
            for (index in maxOf(0, rows.length() - 80) until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val key = row.optString("key").takeIf { it.isNotBlank() } ?: continue
                val terms = row.optJSONArray("terms") ?: continue
                put(key, (0 until terms.length()).mapNotNull { terms.opt(it) as? String }.filter { it.isNotBlank() && it.length <= 120 }.take(12))
            }
        }
    }
    private fun persist(data: Map<String, List<String>>) = save(JSONArray(data.map { (key, terms) -> JSONObject().put("key", key).put("terms", JSONArray(terms)) }).toString())
    fun read(key: String): List<String> = synchronized(lock) { data()[key].orEmpty() }
    fun remember(key: String, query: String) {
        val term = query.trim()
        if (term.isEmpty() || term.length > 120) return
        synchronized(lock) {
            val data = data()
            val values = listOf(term) + data[key].orEmpty().filterNot { it.equals(term, true) }
            data.remove(key)
            while (data.size >= 80) data.remove(data.keys.first())
            data[key] = values.take(12)
            persist(data)
        }
    }
    fun remove(key: String, term: String) = synchronized(lock) {
        val data = data()
        data[key] = data[key].orEmpty().filterNot { it == term }
        persist(data)
    }
    fun clear(key: String) = synchronized(lock) {
        val data = data()
        data.remove(key)
        persist(data)
    }
    companion object {
        private val lock = Any()
        fun from(context: Context): CloudSearchHistory {
            val prefs = context.getSharedPreferences("cloud_search_history", Context.MODE_PRIVATE)
            return CloudSearchHistory({ prefs.getSecret("cloud_search_history", "history") }, { prefs.edit().putSecret("cloud_search_history", "history", it).apply() })
        }
    }
}
