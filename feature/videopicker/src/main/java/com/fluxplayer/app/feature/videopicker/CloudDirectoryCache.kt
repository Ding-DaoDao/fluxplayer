package com.fluxplayer.app.feature.videopicker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fluxplayer.app.core.model.WebDavResource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云盘目录磁盘缓存。
 * 在每个 ViewModel 的 loadDirectory 成功回调中写入，
 * 在 tryRestoreSession/autoLogin 时读取并预填，让重启后瞬间展示上次的目录内容。
 */
object CloudDirectoryCache {

    private const val TAG = "CloudDirectoryCache"
    private const val PREFS_NAME = "cloud_dir_cache"

    /**
     * 缓存结构版本。字段集变更时递增，旧版本缓存会被整体丢弃重建。
     *
     * v1 初版：只存 path/name/isDirectory/size/lastModified（**漏存 thumbnailUrl**，
     * 导致从缓存恢复的目录全部没有封面，表现为「有的能加载有的不能」）。
     * v2 补齐 thumbnailUrl / fileCount / folderSize / category / createdAt。
     */
    private const val CACHE_VERSION = 2

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
                    // 封面 URL 必须持久化：缺失会让缓存恢复的目录全部退化为默认图标
                    put("thumbnailUrl", item.thumbnailUrl)
                    put("fileCount", item.fileCount)
                    put("folderSize", item.folderSize)
                    put("category", item.category)
                    put("createdAt", item.createdAt)
                }
            )
        }
        val payload = JSONObject()
            .put("v", CACHE_VERSION)
            .put("items", json)
            .toString()
        ensurePrefs(context).edit().putString(key, payload).apply()
    }

    /**
     * 从磁盘读取缓存的目录列表。
     * 版本不匹配时丢弃旧缓存并返回 null，强制走网络重新加载——
     * 否则会残留「部分条目缺封面字段」的半新半旧状态。
     * @return 缓存的列表，无缓存时返回 null
     */
    fun get(context: Context, provider: String, fileId: String): List<WebDavResource>? {
        val key = "dir_${provider}_${fileId}"
        val raw = ensurePrefs(context).getString(key, null) ?: return null
        return try {
            val envelope = JSONObject(raw)
            val version = envelope.optInt("v", 1)
            if (version != CACHE_VERSION) {
                Log.i(TAG, "缓存版本过期 v$version → v$CACHE_VERSION，丢弃并重建: $key")
                ensurePrefs(context).edit().remove(key).apply()
                return null
            }
            val jsonArray = envelope.getJSONArray("items")
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                WebDavResource(
                    name = obj.optString("name"),
                    path = obj.optString("path"),
                    isDirectory = obj.optBoolean("isDirectory"),
                    size = obj.optLong("size"),
                    lastModified = obj.optString("lastModified"),
                    thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                    fileCount = if (obj.isNull("fileCount")) null else obj.optInt("fileCount"),
                    folderSize = obj.optLong("folderSize"),
                    category = obj.optString("category"),
                    createdAt = obj.optString("createdAt"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取目录缓存失败 $key: ${e.message}")
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
