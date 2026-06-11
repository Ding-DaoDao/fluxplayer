package dev.anilbeesetti.nextplayer.core.common

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频清晰度选项缓存 — 缓存各云盘文件的可用清晰度列表
 */
@Singleton
class VideoQualityCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("video_quality_cache", Context.MODE_PRIVATE)

    data class QualityOption(
        val label: String,       // e.g. "1080P", "4K"
        val url: String,         // 播放地址
        val width: Int = 0,
        val height: Int = 0
    )

    /**
     * 缓存某个文件的清晰度选项
     */
    fun cacheQualityOptions(provider: String, fileId: String, videoName: String, options: List<QualityOption>) {
        val key = "${provider}_$fileId"
        val arr = JSONArray()
        options.forEach { opt ->
            arr.put(JSONObject().apply {
                put("label", opt.label)
                put("url", opt.url)
                put("width", opt.width)
                put("height", opt.height)
            })
        }
        val json = JSONObject().apply {
            put("name", videoName)
            put("options", arr)
        }
        prefs.edit().putString(key, json.toString()).apply()

        // 为每个清晰度 URL 建立反向索引，加速播放器端查找
        if (options.isNotEmpty()) {
            val editor = prefs.edit()
            options.forEach { opt ->
                val urlKey = "url_index_${provider}_${opt.url.hashCode()}"
                editor.putString(urlKey, key)
            }
            editor.apply()
        }
    }

    /**
     * 获取缓存的清晰度选项
     */
    fun getQualityOptions(provider: String, fileId: String): List<QualityOption>? {
        val key = "${provider}_$fileId"
        val json = prefs.getString(key, null) ?: return null
        return parseOptions(json)
    }

    /**
     * 通过播放 URL 查找缓存的清晰度选项（用于播放器端快速恢复）
     */
    fun getQualityOptionsByUrl(provider: String, playUrl: String): List<QualityOption>? {
        // 尝试通过 URL 哈希反向索引查找
        val urlKey = "url_index_${provider}_${playUrl.hashCode()}"
        val cacheKey = prefs.getString(urlKey, null) ?: return null
        val json = prefs.getString(cacheKey, null) ?: return null
        return parseOptions(json)
    }

    private fun parseOptions(jsonStr: String): List<QualityOption>? {
        return try {
            val obj = JSONObject(jsonStr)
            val arr = obj.getJSONArray("options")
            (0 until arr.length()).map { i ->
                val opt = arr.getJSONObject(i)
                QualityOption(
                    label = opt.getString("label"),
                    url = opt.getString("url"),
                    width = opt.optInt("width", 0),
                    height = opt.optInt("height", 0)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
