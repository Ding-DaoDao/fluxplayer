package com.fluxplayer.app.core.data.danmaku

import android.util.Base64
import android.util.Log
import com.fluxplayer.app.core.model.AnimeMatch
import com.fluxplayer.app.core.model.DanmakuSource
import com.fluxplayer.app.core.model.EpisodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 弹幕 API 客户端 —— 兼容弹弹 play 开放平台 / 自建 API。
 *
 * 支持的端点：
 * - GET {baseUrl}/api/v2/search/anime?keyword=xxx      → 搜索动漫
 * - GET {baseUrl}/api/v2/bangumi/{animeId}              → 获取剧集列表
 * - GET {baseUrl}/api/v2/comment/{episodeId}            → 下载 Bilibili XML 弹幕
 *
 * 认证：弹弹 play 官方 API v2 需要签名验证（X-AppId + X-Timestamp + X-Signature）。
 * 当 source.appId 不为空时自动添加认证头。
 */
class DanmakuApiClient(
    private val client: OkHttpClient = DEFAULT_CLIENT,
) {

    companion object {
        private const val TAG = "DanmakuApiClient"

        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 搜索动漫。
     *
     * @param source 弹幕源配置
     * @param keyword 关键词
     * @return 匹配的动漫列表，失败返回空列表
     */
    suspend fun searchAnime(source: DanmakuSource, keyword: String): List<AnimeMatch> = withContext(Dispatchers.IO) {
        searchAnimeImpl(source, keyword)
    }

    /** 原实现：内部为阻塞网络调用，必须在 IO 线程执行 */
    private suspend fun searchAnimeImpl(source: DanmakuSource, keyword: String): List<AnimeMatch> {
        return try {
            val apiPath = "/api/v2/search/anime"
            val url = buildUrl(source, apiPath, mapOf("keyword" to keyword))
            val request = buildRequest(source, apiPath, url).get().build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "searchAnime HTTP ${response.code} for keyword=$keyword")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            parseSearchResult(body)
        } catch (e: Exception) {
            Log.e(TAG, "searchAnime failed for keyword=$keyword", e)
            emptyList()
        }
    }

    /**
     * 获取动漫详情（包含剧集列表）。
     *
     * @param source 弹幕源配置
     * @param animeId 动漫 ID
     * @return 剧集列表，失败返回空列表
     */
    suspend fun getEpisodes(source: DanmakuSource, animeId: Int): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        getEpisodesImpl(source, animeId)
    }

    /** 原实现：内部为阻塞网络调用，必须在 IO 线程执行 */
    private suspend fun getEpisodesImpl(source: DanmakuSource, animeId: Int): List<EpisodeInfo> {
        return try {
            val apiPath = "/api/v2/bangumi/$animeId"
            val url = buildUrl(source, apiPath)
            val request = buildRequest(source, apiPath, url).get().build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "getEpisodes HTTP ${response.code} for animeId=$animeId")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            parseEpisodes(body, animeId)
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodes failed for animeId=$animeId", e)
            emptyList()
        }
    }

    /**
     * 下载弹幕文件（Bilibili XML 格式）。
     *
     * @param source 弹幕源配置
     * @param episodeId 剧集 ID
     * @return 弹幕 XML 输入流，失败返回 null
     */
    suspend fun downloadDanmaku(source: DanmakuSource, episodeId: Int): InputStream? = withContext(Dispatchers.IO) {
        downloadDanmakuImpl(source, episodeId)
    }

    /** 原实现：内部为阻塞网络调用，必须在 IO 线程执行 */
    private suspend fun downloadDanmakuImpl(source: DanmakuSource, episodeId: Int): InputStream? {
        return try {
            val apiPath = "/api/v2/comment/$episodeId"
            val url = buildUrl(source, apiPath, mapOf("withRelated" to "true"))
            val request = buildRequest(source, apiPath, url).get().build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "downloadDanmaku HTTP ${response.code} for episodeId=$episodeId")
                return null
            }

            // 检查 Content-Type：XML 直接返回流；JSON 需先解析再转 XML
            val contentType = response.header("Content-Type", "") ?: ""
            Log.d(TAG, "downloadDanmaku: episodeId=$episodeId contentType=$contentType")

            return if (contentType.contains("xml", ignoreCase = true) ||
                contentType.contains("text", ignoreCase = true)
            ) {
                val body = response.body?.string()
                Log.d(TAG, "XML response body[:500]: ${body?.take(500)}")
                body?.byteInputStream(Charsets.UTF_8)
            } else {
                // JSON 响应 — 尝试将其转为 Bilibili XML 格式
                val bodyStr = response.body?.string()
                Log.d(TAG, "JSON response body[:500]: ${bodyStr?.take(500)}")
                if (bodyStr != null && bodyStr.trimStart().startsWith("{")) {
                    val xmlStream = convertJsonToXmlStream(bodyStr)
                    val xmlText = xmlStream.bufferedReader().readText()
                    xmlStream.close()
                    Log.d(TAG, "Converted XML[:500]:\n${xmlText.take(500)}")
                    xmlText.byteInputStream(Charsets.UTF_8)
                } else {
                    // 已经是 XML 文本
                    bodyStr?.byteInputStream(Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadDanmaku failed for episodeId=$episodeId", e)
            null
        }
    }

    // ── 内部方法 ──────────────────────────────────────────

    /**
     * 构造 HTTP 请求（含认证头）。
     *
     * 当 source.appId 不为空时，使用签名验证模式添加请求头：
     * - X-AppId: source.appId
     * - X-Timestamp: 当前 Unix 时间戳（秒）
     * - X-Signature: base64(sha256(appId + timestamp + apiPath + token))
     */
    private fun buildRequest(
        source: DanmakuSource,
        apiPath: String,
        url: String,
    ): Request.Builder {
        val builder = Request.Builder().url(url)

        if (source.appId.isNotBlank() && source.token.isNotBlank()) {
            val timestamp = System.currentTimeMillis() / 1000
            val signature = generateSignature(
                source.appId,
                timestamp,
                apiPath,
                source.token,
            )
            builder.addHeader("X-AppId", source.appId)
            builder.addHeader("X-Timestamp", timestamp.toString())
            builder.addHeader("X-Signature", signature)
        }

        return builder
    }

    /**
     * 生成签名。
     *
     * 算法：base64(sha256(AppId + Timestamp + Path + AppSecret))
     */
    private fun generateSignature(
        appId: String,
        timestamp: Long,
        path: String,
        appSecret: String,
    ): String {
        val data = "$appId$timestamp$path$appSecret"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * 构造完整 URL。
     *
     * 对于弹弹 play 官方：{baseUrl}/api/v2/{path}
     * 对于自建 API（如 danmu-api）：{baseUrl}/api/v2/{path}，
     * 其中 baseUrl 可能已包含 token（如 http://host:9321/87654321）
     */
    private fun buildUrl(
        source: DanmakuSource,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        val base = source.baseUrl.trimEnd('/')
        val query = if (params.isNotEmpty()) {
            params.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
        } else ""

        return if (query.isNotEmpty()) "$base$path?$query" else "$base$path"
    }

    /** 解析搜索返回的 JSON */
    private fun parseSearchResult(jsonStr: String): List<AnimeMatch> {
        val root = JSONObject(jsonStr)
        if (!root.optBoolean("success", true)) {
            Log.w(TAG, "search API returned error: ${root.optString("errorMessage")}")
            return emptyList()
        }

        val animesArray = root.optJSONArray("animes") ?: return emptyList()
        val results = mutableListOf<AnimeMatch>()

        for (i in 0 until animesArray.length()) {
            val obj = animesArray.optJSONObject(i) ?: continue
            results.add(
                AnimeMatch(
                    animeId = obj.optInt("animeId", 0),
                    title = obj.optString("animeTitle", ""),
                    type = obj.optString("type", ""),
                    summary = obj.optString("typeDescription", ""),
                    episodeCount = obj.optInt("episodeCount", 0),
                ),
            )
        }

        return results
    }

    /** 解析剧集列表 JSON */
    private fun parseEpisodes(jsonStr: String, animeId: Int): List<EpisodeInfo> {
        val root = JSONObject(jsonStr)

        // 兼容两种结构：
        // 1. {"bangumi": {"animeId":..., "episodes": [...]}}
        // 2. {"episodes": [...]}
        val bangumiObj = root.optJSONObject("bangumi")
        val episodesArray = bangumiObj?.optJSONArray("episodes")
            ?: root.optJSONArray("episodes")
            ?: return emptyList()

        val results = mutableListOf<EpisodeInfo>()

        for (i in 0 until episodesArray.length()) {
            val obj = episodesArray.optJSONObject(i) ?: continue

            val epTitle = obj.optString("episodeTitle", "")
            val epNumber = obj.optInt("episodeNumber", 0)
            // episodeId 可能是 Int 或 String
            val epId = obj.optInt("episodeId", 0).takeIf { it != 0 }
                ?: obj.optString("episodeId", "").toIntOrNull()
                ?: continue

            results.add(
                EpisodeInfo(
                    episodeId = epId,
                    animeId = animeId,
                    title = epTitle,
                    episodeNumber = epNumber,
                ),
            )
        }

        return results
    }

    /**
     * 将 JSON 格式的弹幕响应转为 Bilibili XML 流。
     *
     * 部分自建 API 可能返回 JSON 格式如 {"comments": [...], "danmaku": [...]}
     */
    private fun convertJsonToXmlStream(jsonStr: String): InputStream {
        val root = JSONObject(jsonStr)
        Log.d(TAG, "convertJsonToXmlStream: root keys=${root.names()?.toString()}")

        // 找出包含弹幕数据的数组
        val commentsArray = root.optJSONArray("comments")
            ?: root.optJSONArray("danmaku")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("list")
            ?: let {
                val data = root.opt("data")
                if (data is JSONArray) {
                    Log.d(TAG, "Found data array with ${data.length()} items")
                    data
                } else {
                    // 也检查 data.comments
                    val dataObj = root.optJSONObject("data")
                    dataObj?.optJSONArray("comments")
                        ?: dataObj?.optJSONArray("danmaku")
                        ?: dataObj?.optJSONArray("items")
                }
            }

        Log.d(TAG, "convertJsonToXmlStream: commentsArray=${commentsArray?.length()} items")

        val xml = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<i>")
            if (commentsArray != null) {
                for (i in 0 until commentsArray.length()) {
                    val item = commentsArray.optJSONObject(i) ?: continue
                    val line = formatCommentAsXml(item)
                    appendLine(line)
                }
            }
            appendLine("</i>")
        }

        return xml.byteInputStream(Charsets.UTF_8)
    }

    /**
     * 将 JSON 弹幕对象格式化为 Bilibili XML <d> 元素。
     *
     * 支持多种字段映射：
     * | B站 p 字段 | JSON 字段                           |
     * |-----------|-------------------------------------|
     * | time      | time/p/start/progress/timeOffset    |
     * | mode      | mode/type                           |
     * | fontSize  | fontSize/size/font_size             |
     * | color     | color/c                             |
     */
    private fun formatCommentAsXml(item: JSONObject): String {
        // 弹弹 play API 返回的 p 字段是 Bilibili 格式 "time,mode,color,pool"
        val time = parsePFieldTime(item.optString("p", ""))
            ?: item.optDouble("progress", -1.0).takeIf { it >= 0 }
            ?: item.optDouble("time", -1.0).takeIf { it >= 0 }
            ?: item.optDouble("start", 0.0)
        val mode = item.optInt("mode", 1).takeIf { it in 1..6 }
            ?: item.optInt("type", 1)
        val fontSize = item.optInt("fontSize", -1).takeIf { it > 0 }
            ?: item.optInt("size", 25)
        val color = item.optInt("color", 0xFFFFFF)
        val text = item.optString("text", "")
            .ifEmpty { item.optString("content", "") }
            .ifEmpty { item.optString("m", "") }

        if (time < 0.01 && text.length > 1) {
            Log.d(TAG, "  first comment: time=$time text=${text.take(20)}")
        }

        return buildString {
            append("  <d p=\"")
            append(time)
            append(',')
            append(mode)
            append(',')
            append(fontSize)
            append(',')
            append(color)
            append(",0,0,0,0\">")
            append(escapeXml(text))
            append("</d>")
        }
    }

    /**
     * 解析 Bilibili 格式的 p 属性字符串，提取时间（秒）。
     *
     * p 格式： "time,mode,color,pool,uid,rowId"
     * 示例： "1.00,1,16777215,0"
     *
     * @return 时间（秒），失败返回 null
     */
    private fun parsePFieldTime(pAttr: String): Double? {
        if (pAttr.isBlank()) return null
        val first = pAttr.split(",").firstOrNull()?.trim() ?: return null
        return first.toDoubleOrNull()
    }

    private fun escapeXml(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
