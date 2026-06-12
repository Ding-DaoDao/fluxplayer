package com.fluxplayer.app.core.common

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云盘缓存键注册表 — 管理"解析后URL"到"原始云盘URI"的映射
 * 确保 ExoPlayer 缓存能正确识别云盘文件的原始来源
 */
@Singleton
class CloudAwareCacheKeyRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CacheKeyRegistry"
        private const val PREFS_NAME = "cloud_cache_key_registry"
        private const val KEY_MAP = "resolved_to_original"
    }

    private val resolvedToOriginal = ConcurrentHashMap<String, String>()

    init {
        loadFromDisk()
    }

    /**
     * 注册映射：resolvedUri → originalCloudUri
     */
    fun register(resolvedUri: String, originalCloudUri: String) {
        resolvedToOriginal[resolvedUri] = originalCloudUri
        saveToDisk()
    }

    /**
     * 查询原始云盘 URI
     */
    fun getOriginalUri(resolvedUri: String): String? {
        return resolvedToOriginal[resolvedUri]
    }

    /**
     * 移除映射
     */
    fun unregister(resolvedUri: String) {
        resolvedToOriginal.remove(resolvedUri)
        saveToDisk()
    }

    /**
     * 清空所有映射
     */
    fun clear() {
        resolvedToOriginal.clear()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun loadFromDisk() {
        try {
            val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MAP, null) ?: return
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                resolvedToOriginal[key] = obj.getString(key)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cache key registry", e)
        }
    }

    private fun saveToDisk() {
        try {
            val obj = JSONObject()
            resolvedToOriginal.forEach { (key, value) -> obj.put(key, value) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_MAP, obj.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cache key registry", e)
        }
    }
}
