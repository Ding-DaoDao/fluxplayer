package com.fluxplayer.app.feature.videopicker

import android.content.Context
import android.content.SharedPreferences
import com.fluxplayer.app.core.model.WebDavResource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云盘目录磁盘缓存。
 * 在每个 ViewModel 的 loadDirectory 成功回调中写入，
 * 在 tryRestoreSession/autoLogin 时读取并预填，让重启后瞬间展示上次的目录内容。
 */
object CloudDirectoryCache {

    private const val PREFS_NAME = "cloud_dir_cache"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensurePrefs(context: Context): SharedPreferences {
        return prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
    }

    /**
     * 缓存目录列表到磁盘
     */
    fun put(context: Context, provider: String, fileId: String, items: List<WebDavResource>) {
        val key = "dir_${provider}_${fileId}"
        val json = JSONArray()
        items.forEach { item ->
            json.put(
                JSONObject().apply {
                    put("path", item.path)
                    put("name", item.name)
                    put("isDirectory", item.isDirectory)
                    put("size", item.size)
                    put("lastModified", item.lastModified)
                }
            )
        }
        ensurePrefs(context).edit().putString(key, json.toString()).apply()
    }

    /**
     * 从磁盘读取缓存的目录列表
     * @return 缓存的列表，无缓存时返回 null
     */
    fun get(context: Context, provider: String, fileId: String): List<WebDavResource>? {
        val key = "dir_${provider}_${fileId}"
        val jsonStr = ensurePrefs(context).getString(key, null) ?: return null
        return try {
            val jsonArray = JSONArray(jsonStr)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                WebDavResource(
                    path = obj.optString("path"),
                    name = obj.optString("name"),
                    isDirectory = obj.optBoolean("isDirectory"),
                    size = obj.optLong("size"),
                    lastModified = obj.optString("lastModified")
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 登出时清除缓存 */
    fun clear(context: Context, provider: String) {
        val p = ensurePrefs(context)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith("dir_${provider}_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    /** 清除所有云盘目录缓存（清除历史时使用） */
    fun clearAll(context: Context) {
        val p = ensurePrefs(context)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith("dir_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }
}
