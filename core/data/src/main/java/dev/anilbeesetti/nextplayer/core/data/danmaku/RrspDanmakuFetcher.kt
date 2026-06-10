package dev.anilbeesetti.nextplayer.core.data.danmaku

import android.util.Log
import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class RrspDanmakuFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .build()
) : PlatformDanmakuFetcher {

    override val name: String = "人人弹幕"
    override val sourceId: String = "rrsp"

    override fun match(url: String): Boolean {
        return url.contains("rrmj.plus") || url.contains("rrsp.tv") || url.contains("rrmj.tv")
    }

    override suspend fun search(keyword: String): List<AnimeMatch> {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://api.rrmj.plus/m-station/search/drama?keywords=$encoded&size=10&order=match&search_after=&isExecuteVipActivity=true"
            Log.d(TAG, "search: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.rrmj.plus/")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val errorBody = response.body?.string() ?: ""
                Log.w(TAG, "search HTTP $code: ${errorBody.take(200)}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val dramaList = json.optJSONObject("data")?.optJSONArray("searchDramaList")
                ?: json.optJSONArray("data")
                ?: return emptyList()

            val results = mutableListOf<AnimeMatch>()
            for (i in 0 until dramaList.length()) {
                val item = dramaList.optJSONObject(i) ?: continue
                val title = item.optString("title", "")
                val id = item.optString("id", "").ifEmpty { item.optInt("id", 0).toString() }
                if (title.isNotBlank() && id.isNotBlank()) {
                    results.add(
                        AnimeMatch(
                            animeId = id.hashCode(),
                            title = title,
                            type = "rrsp",
                            url = id
                        )
                    )
                }
            }
            Log.d(TAG, "search parsed ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e)
            emptyList()
        }
    }

    override suspend fun getEpisodes(anime: AnimeMatch): List<EpisodeInfo> {
        val dramaId = anime.url ?: return emptyList()
        Log.d(TAG, "getEpisodes: dramaId=$dramaId")

        return try {
            val url = "https://api.rrmj.plus/m-station/drama/page?hsdrOpen=0&isAgeLimit=0&dramaId=$dramaId&quality=UHD4K&hevcOpen=0"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.rrmj.plus/")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val errorBody = response.body?.string() ?: ""
                Log.w(TAG, "getEpisodes HTTP $code: ${errorBody.take(200)}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val episodeList = json.optJSONObject("data")?.optJSONArray("episodeList")
                ?: json.optJSONArray("episodeList")
                ?: return emptyList()

            val episodes = mutableListOf<EpisodeInfo>()
            for (i in 0 until episodeList.length()) {
                val item = episodeList.optJSONObject(i) ?: continue
                val episodeNo = item.optString("episodeNo", "").ifEmpty {
                    item.optInt("episodeNo", 0).toString()
                }
                val sid = item.optString("sid", "")
                if (episodeNo.isNotBlank()) {
                    val epTitle = item.optString("title", "").ifBlank { null } ?: episodeNo
                    episodes.add(
                        EpisodeInfo(
                            episodeId = sid.hashCode(),
                            animeId = anime.animeId,
                            title = epTitle,
                            episodeNumber = episodes.size + 1,
                            url = sid
                        )
                    )
                }
            }
            Log.d(TAG, "getEpisodes: total ${episodes.size} episodes")
            episodes
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodes failed for dramaId=$dramaId", e)
            emptyList()
        }
    }

    override suspend fun fetchDanmaku(url: String): InputStream? {
        Log.d(TAG, "fetchDanmaku called for sid=$url")
        return try {
            val endpoints = listOf(
                "https://api.rrmj.plus/m-station/danmu/list?episodeId=$url",
                "https://api.rrmj.plus/m-station/danmu/$url"
            )

            for (endpoint in endpoints) {
                try {
                    val request = Request.Builder()
                        .url(endpoint)
                        .header("User-Agent", PC_UA)
                        .header("Referer", "https://www.rrmj.plus/")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) continue

                    val body = response.body?.string() ?: continue
                    if (body.isBlank()) continue

                    val trimmed = body.trimStart()
                    if (trimmed.startsWith("<?xml") || trimmed.startsWith("<i>")) {
                        return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
                    }

                    try {
                        val json = JSONObject(body)
                        val danmakuArr = json.optJSONArray("danmaku")
                            ?: json.optJSONArray("data")
                            ?: json.optJSONArray("list")
                            ?: continue

                        val xml = convertDanmakuJsonToXml(danmakuArr)
                        return ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
                    } catch (e: Exception) {
                        // JSON parse failed, try next endpoint
                    }
                } catch (e: Exception) {
                    // Request failed, try next endpoint
                }
            }

            Log.w(TAG, "fetchDanmaku: no API endpoint worked for sid=$url")
            null
        } catch (e: Exception) {
            Log.e(TAG, "fetchDanmaku failed for sid=$url", e)
            null
        }
    }

    private fun convertDanmakuJsonToXml(arr: JSONArray): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<i>\n")
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val timepoint = item.optDouble("time", -1.0).takeIf { it >= 0.0 }
                ?: item.optDouble("progress", -1.0).takeIf { it >= 0.0 }
                ?: continue
            val content = item.optString("content", "").ifEmpty {
                item.optString("text", "").ifEmpty {
                    item.optString("message", "")
                }
            }
            if (content.isBlank() || containsInvalidChars(content)) continue
            val color = item.optInt("color", 0xFFFFFF)
            sb.append("  <d p=\"$timepoint,1,25,$color,0\">")
            sb.append(escapeXml(content))
            sb.append("</d>\n")
        }
        sb.append("</i>")
        return sb.toString()
    }

    private fun containsInvalidChars(s: String): Boolean {
        for (c in s) {
            if (c == '<' || c == '>' || c == '&' || c == ' ' || c == '\b') {
                return true
            }
        }
        return false
    }

    private fun escapeXml(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    companion object {
        private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TAG = "RrspDanmakuFetcher"
    }
}
