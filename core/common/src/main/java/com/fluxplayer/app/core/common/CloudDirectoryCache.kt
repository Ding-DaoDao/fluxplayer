package com.fluxplayer.app.core.common

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云盘目录缓存 — 缓存已浏览的目录列表，加速下次访问
 */
@Singleton
class CloudDirectoryCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("cloud_dir_cache", Context.MODE_PRIVATE)

    data class CachedEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: String,
        val thumbnailUrl: String? = null
    )

    fun getCachedDirectory(provider: String, folderId: String): List<CachedEntry>? {
        val key = "${provider}_$folderId"
        val json = prefs.getString(key, null) ?: return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CachedEntry(
                    name = obj.getString("name"),
                    path = obj.getString("path"),
                    isDirectory = obj.getBoolean("isDir"),
                    size = obj.getLong("size"),
                    lastModified = obj.optString("lastModified", ""),
                    thumbnailUrl = obj.optString("thumb", null)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun cacheDirectory(provider: String, folderId: String, entries: List<CachedEntry>) {
        val key = "${provider}_$folderId"
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(JSONObject().apply {
                put("name", entry.name)
                put("path", entry.path)
                put("isDir", entry.isDirectory)
                put("size", entry.size)
                put("lastModified", entry.lastModified)
                entry.thumbnailUrl?.let { put("thumb", it) }
            })
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- 当前路径持久化 ----

    fun saveCurrentPath(provider: String, path: String) {
        prefs.edit().putString("${provider}_current_path", path).apply()
    }

    fun getCurrentPath(provider: String): String? {
        return prefs.getString("${provider}_current_path", null)
    }
}
