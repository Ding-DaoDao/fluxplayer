package com.fluxplayer.app.core.data.danmaku

import android.util.Base64
import android.util.Log
import com.fluxplayer.app.core.model.AnimeMatch
import com.fluxplayer.app.core.model.EpisodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class YoukuDanmakuFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : PlatformDanmakuFetcher {

    override val name: String = "优酷弹幕"
    override val sourceId: String = "youku"

    override fun match(url: String): Boolean {
        return url.contains("youku.com")
    }

    override suspend fun search(keyword: String): List<AnimeMatch> = withContext(Dispatchers.IO) {
        searchImpl(keyword)
    }

    /** 原实现：内部为阻塞网络调用，必须在 IO 线程执行 */
    private suspend fun searchImpl(keyword: String): List<AnimeMatch> {
        Log.d(TAG, "search: $keyword")
        val results = tryJsonSearch(keyword)
        if (results.isNotEmpty()) {
            Log.d(TAG, "json search returned ${results.size} results")
            return results
        }
        Log.d(TAG, "json search empty, trying html search")
        return tryHtmlSearch(keyword)
    }

    private suspend fun tryJsonSearch(keyword: String): List<AnimeMatch> {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val urls = listOf(
                "https://search.youku.com/api/search?appScene=search&keyword=$encoded&appCaller=pc",
                "https://search.youku.com/api/search?appScene=search&keyword=$encoded&appCaller=h5",
                "https://so.youku.com/search_video?keyword=$encoded&type=1&json=1"
            )
            for (url in urls) {
                try {
                    Log.d(TAG, "tryJsonSearch: $url")
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", PC_UA)
                        .header("Referer", "https://www.youku.com/")
                        .get()
                        .build()
                    val resp = client.newCall(req).execute()
                    Log.d(TAG, "tryJsonSearch: HTTP ${resp.code}")
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) {
                            Log.d(TAG, "tryJsonSearch: body=${body.take(300)}")
                            try {
                                val json = JSONObject(body)
                                val youkuResults = parseYoukuJsonSearch(json)
                                if (youkuResults.isNotEmpty()) return youkuResults
                            } catch (e: Exception) {
                                Log.d(TAG, "tryJsonSearch: parse failed: ${e.message}")
                            }
                        }
                    } else {
                        Log.d(TAG, "tryJsonSearch: HTTP ${resp.code} FAILED")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "tryJsonSearch: network error: ${e.message}")
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.d(TAG, "tryJsonSearch: outer error: ${e.message}")
            emptyList()
        }
    }

    private fun parseYoukuJsonSearch(json: JSONObject): List<AnimeMatch> {
        val results = mutableListOf<AnimeMatch>()

        val serisesList = json.optJSONArray("serisesList")
            ?: json.optJSONObject("data")?.optJSONArray("serisesList")
            ?: json.optJSONArray("data")
            ?: return results

        for (i in 0 until serisesList.length()) {
            val item = serisesList.optJSONObject(i) ?: continue
            val showId = item.optString("showId", "").ifBlank {
                item.optString("id", "").ifBlank {
                    item.optString("albumId", "")
                }
            }
            val title = item.optString("title", "").ifBlank {
                item.optString("name", "").ifBlank {
                    item.optString("showName", "")
                }
            }
            if (showId.isBlank() || title.isBlank()) continue

            val url = "https://v.youku.com/v_show/id_$showId.html"
            results.add(
                AnimeMatch(
                    animeId = url.hashCode(),
                    title = Regex("<[^>]+>").replace(title, "") + "⭐来源：youku",
                    type = "youku",
                    url = url
                )
            )
        }
        return results
    }

    private suspend fun tryHtmlSearch(keyword: String): List<AnimeMatch> {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val cookie = getYoukuCookie()
            Log.d(TAG, "tryHtmlSearch: cookie=${cookie.take(50)}...")

            val searchUrls = listOf(
                "https://search.youku.com/search_video?keyword=$encoded",
                "https://so.youku.com/search_video?keyword=$encoded",
                "https://m.youku.com/search_video?keyword=$encoded"
            )
            for (searchUrl in searchUrls) {
                try {
                    val htmlReq = Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", PC_UA)
                        .header("Referer", "https://www.youku.com/")
                        .header("Cookie", cookie)
                        .get()
                        .build()
                    val htmlResp = client.newCall(htmlReq).execute()
                    Log.d(TAG, "tryHtmlSearch: $searchUrl HTTP ${htmlResp.code}")
                    if (htmlResp.isSuccessful) {
                        val html = htmlResp.body?.string()
                        if (html != null) {
                            Log.d(TAG, "tryHtmlSearch: $searchUrl len=${html.length}")
                            val jsonResults = extractJsonFromHtml(html)
                            if (jsonResults.isNotEmpty()) return jsonResults
                            val cardResults = parseSearchCards(html)
                            if (cardResults.isNotEmpty()) return cardResults
                        }
                    } else {
                        Log.d(TAG, "tryHtmlSearch: HTTP ${htmlResp.code} FAILED")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "tryHtmlSearch: network error: ${e.message}")
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.d(TAG, "tryHtmlSearch: outer error: ${e.message}")
            emptyList()
        }
    }

    private fun getYoukuCookie(): String {
        return try {
            val req = Request.Builder()
                .url("https://m.youku.com/")
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.youku.com/")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            resp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractJsonFromHtml(html: String): List<AnimeMatch> {
        val results = mutableListOf<AnimeMatch>()
        val jsonPatterns = listOf(
            Regex("""window\.__INITIAL_STATE__\s*=\s*(\{[\s\S]+?\});"""),
            Regex("""pageData\s*=\s*(\{[\s\S]+?\});"""),
            Regex(""""serisesList"\s*:\s*(\[[\s\S]+?\])""")
        )
        for (pattern in jsonPatterns) {
            val match = pattern.find(html) ?: continue
            try {
                val jsonStr = match.groupValues[1]
                val json = JSONObject("{\"data\":$jsonStr}")
                val serisesList = json.optJSONObject("data")?.optJSONArray("serisesList")
                    ?: json.optJSONArray("data")
                    ?: continue

                for (i in 0 until serisesList.length()) {
                    val item = serisesList.optJSONObject(i) ?: continue
                    val showId = item.optString("showId", "").ifBlank {
                        item.optString("id", "")
                    }
                    val title = item.optString("title", "").ifBlank {
                        item.optString("showName", "")
                    }
                    if (showId.isBlank() || title.isBlank()) continue

                    results.add(
                        AnimeMatch(
                            animeId = results.hashCode(),
                            title = Regex("<[^>]+>").replace(title, "") + "⭐来源：youku",
                            type = "youku",
                            url = "https://v.youku.com/v_show/id_$showId.html"
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        return results
    }

    private fun parseSearchCards(html: String): List<AnimeMatch> {
        val results = mutableListOf<AnimeMatch>()
        val cardRegex = Regex("""<div[^>]*class="[^"]*\bh5-show-card-wrapper\b[^"]*"[^>]*>""")
        val cardSections = cardRegex.split(html)

        for (cardHtml in cardSections) {
            if (cardHtml.isBlank()) continue

            val fromMatch = Regex("""class="[^"]*\bshow-sourcename\b[^"]*"[^>]*>([^<]*)""").find(cardHtml)
            val from = fromMatch?.groupValues?.get(1)?.trim() ?: ""
            if (from.isNotBlank()) continue

            val titleMatch = Regex("""class="[^"]*\bshow-name\b[^"]*"[^>]*>([^<]*)""").find(cardHtml)
            val title = titleMatch?.groupValues?.get(1)?.trim() ?: ""
            if (title.isBlank()) continue

            val hrefMatch = Regex("""class="[^"]*\bh5-show-card\b[^"]*"[^>]*href\s*=\s*"([^"]+)""").find(cardHtml)
            val href = hrefMatch?.groupValues?.get(1)?.trim() ?: continue

            val fullUrl = if (href.startsWith("http")) href else "https://www.youku.com$href"
            results.add(
                AnimeMatch(
                    animeId = fullUrl.hashCode(),
                    title = Regex("<[^>]+>").replace(title, "") + "⭐来源：youku",
                    type = "youku",
                    url = fullUrl
                )
            )
        }
        return results
    }

    override suspend fun getEpisodes(anime: AnimeMatch): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        getEpisodesImpl(anime)
    }

    /** 原实现：内部为阻塞网络调用，必须在 IO 线程执行 */
    private suspend fun getEpisodesImpl(anime: AnimeMatch): List<EpisodeInfo> {
        val url = anime.url ?: run { Log.w(TAG, "getEpisodes: anime.url is null"); return emptyList() }
        val idMatch = Regex("""id_([^\.?\?]+)""").find(url)
        val currentId = idMatch?.groupValues?.get(1) ?: run {
            Log.w(TAG, "getEpisodes: cannot extract currentId from url=$url")
            return emptyList()
        }
        Log.d(TAG, "========== getEpisodes START: currentId=$currentId url=$url ==========")

        return try {
            val episodes = mutableListOf<EpisodeInfo>()
            val seenVids = mutableSetOf<String>()

            // Try h5 API first
            val h5Url = "https://search.youku.com/api/search?appScene=show_episode&showIds=$currentId&appCaller=h5"
            Log.d(TAG, "getEpisodes: h5 GET $h5Url")
            val h5Resp = client.newCall(
                Request.Builder()
                    .url(h5Url)
                    .header("User-Agent", PC_UA)
                    .header("Referer", "https://www.youku.com/")
                    .get()
                    .build()
            ).execute()
            Log.d(TAG, "getEpisodes: h5 HTTP ${h5Resp.code}")
            if (h5Resp.isSuccessful) {
                val h5Body = h5Resp.body?.string() ?: ""
                Log.d(TAG, "getEpisodes: h5 body len=${h5Body.length}, sample=${h5Body.take(200)}")
                try {
                    addYoukuEpisodes(JSONObject(h5Body), episodes, seenVids)
                    Log.d(TAG, "getEpisodes: h5 added, now ${episodes.size} episodes")
                } catch (e: Exception) {
                    Log.w(TAG, "getEpisodes: h5 parse failed: ${e.message}")
                }
            }

            // Try pc API
            val pcUrl = "https://search.youku.com/api/search?appScene=show_episode&showIds=$currentId&appCaller=pc"
            Log.d(TAG, "getEpisodes: pc GET $pcUrl")
            val pcResp = client.newCall(
                Request.Builder()
                    .url(pcUrl)
                    .header("User-Agent", PC_UA)
                    .header("Referer", "https://www.youku.com/")
                    .get()
                    .build()
            ).execute()
            Log.d(TAG, "getEpisodes: pc HTTP ${pcResp.code}")
            if (pcResp.isSuccessful) {
                val pcBody = pcResp.body?.string() ?: ""
                Log.d(TAG, "getEpisodes: pc body len=${pcBody.length}, sample=${pcBody.take(200)}")
                val pcJson = JSONObject(pcBody)
                val pcSeries = pcJson.optJSONArray("serisesList") ?: JSONArray()
                Log.d(TAG, "getEpisodes: pc serisesList length=${pcSeries.length()}")

                for (i in 0 until pcSeries.length()) {
                    val item = pcSeries.optJSONObject(i) ?: continue
                    val videoId = item.optString("videoId", "")
                    if (videoId.isBlank() || seenVids.contains(videoId)) continue

                    val iconCorner = item.optJSONObject("iconCorner")
                    var tagText = ""
                    if (iconCorner != null) {
                        tagText = iconCorner.optString("tagText", "")
                    }

                    if (Regex("前|提前|超前|SVIP|svip|Svip").containsMatchIn(tagText)) {
                        seenVids.add(videoId)
                        val orderStage = item.optString("orderStage", "")
                        val showVideoStage = item.optString("showVideoStage", "")
                        val title = item.optString("title", "")
                        val epUrl = "https://v.youku.com/v_show/id_$videoId"
                        val displayTitle = if (orderStage.isNotBlank()) {
                            "${orderStage}_${showVideoStage}\t$title"
                        } else {
                            title
                        }
                        episodes.add(
                            EpisodeInfo(
                                episodeId = videoId.hashCode(),
                                animeId = currentId.hashCode(),
                                title = displayTitle,
                                episodeNumber = episodes.size + 1,
                                url = epUrl
                            )
                        )
                    }
                }
                Log.d(TAG, "getEpisodes: pc added, now ${episodes.size} episodes")
            }

            Log.d(TAG, "getEpisodes: total ${episodes.size} episodes")
            episodes
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodes: EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }.also {
            Log.d(TAG, "========== getEpisodes END: ${it.size} episodes ==========")
        }
    }

    private fun addYoukuEpisodes(json: JSONObject, result: MutableList<EpisodeInfo>, seenVids: MutableSet<String>) {
        val seriesList = json.optJSONArray("serisesList") ?: return
        for (i in 0 until seriesList.length()) {
            val item = seriesList.optJSONObject(i) ?: continue
            val videoId = item.optString("videoId", "")
            if (videoId.isBlank() || seenVids.contains(videoId)) continue

            val iconCorner = item.optJSONObject("iconCorner")
            var tagText = ""
            if (iconCorner != null) {
                tagText = iconCorner.optString("tagText", "")
            }

            if (Regex("预|预告|花絮").containsMatchIn(tagText)) continue

            seenVids.add(videoId)
            val orderStage = item.optString("orderStage", "")
            val showVideoStage = item.optString("showVideoStage", "")
            val title = item.optString("title", "")
            val epUrl = "https://v.youku.com/v_show/id_$videoId"
            val displayTitle = if (orderStage.isNotBlank()) {
                "${orderStage}_${showVideoStage}\t$title"
            } else {
                title
            }
            result.add(
                EpisodeInfo(
                    episodeId = videoId.hashCode(),
                    animeId = 0,
                    title = displayTitle,
                    episodeNumber = result.size + 1,
                    url = epUrl
                )
            )
        }
    }

    override suspend fun fetchDanmaku(url: String): InputStream? = withContext(Dispatchers.IO) {
        fetchDanmakuImpl(url)
    }

    /** 原实现：内部为阻塞网络调用，必须在 IO 线程执行 */
    private suspend fun fetchDanmakuImpl(url: String): InputStream? {
        Log.d(TAG, "fetchDanmaku called for $url")
        return try {
            val pathSegments = url.split("/")
            val lastSeg = pathSegments.lastOrNull() ?: return null
            val videoId = lastSeg.removeSuffix(".html").removePrefix("id_")
            if (videoId.isBlank()) {
                Log.w(TAG, "cannot extract videoId from $url")
                return null
            }
            Log.d(TAG, "fetchDanmaku: videoId=$videoId")

            Log.d(TAG, "fetchDanmaku: getting cna")
            val cna = getCna()
            Log.d(TAG, "fetchDanmaku: cna=$cna")

            Log.d(TAG, "fetchDanmaku: getting tk_enc")
            val tkEnc = getTkEnc()
            if (tkEnc == null) {
                Log.w(TAG, "cannot get tk_enc")
                return null
            }
            Log.d(TAG, "fetchDanmaku: tk_enc=${tkEnc["_m_h5_tk"]?.take(10)}...")

            Log.d(TAG, "fetchDanmaku: getting duration for $videoId")
            val duration = getVideoDuration(videoId)
            if (duration <= 0) {
                Log.w(TAG, "cannot get duration for video=$videoId")
                return null
            }
            Log.d(TAG, "fetchDanmaku: duration=$duration")

            val maxMat = (duration / 60).toInt() + 1
            Log.d(TAG, "fetchDanmaku: maxMat=$maxMat")

            val cookies = "_m_h5_tk=${tkEnc["_m_h5_tk"]};_m_h5_tk_enc=${tkEnc["_m_h5_tk_enc"]};"
            val xmlBuilder = StringBuilder()
            xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<i>\n")

            var hasData = false

            for (mat in 0 until maxMat) {
                try {
                    val now = System.currentTimeMillis()
                    val msg = JSONObject().apply {
                        put("ctime", now)
                        put("ctype", 10004)
                        put("cver", "v1.0")
                        put("guid", cna)
                        put("mat", mat)
                        put("mcount", 1)
                        put("pid", 0)
                        put("sver", "3.1.0")
                        put("type", 1)
                        put("vid", videoId)
                    }
                    val msgStr = msg.toString()
                    val msgB64 = Base64.encodeToString(msgStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    msg.put("msg", msgB64)
                    msg.put("sign", ykMsgSign(msgB64))
                    val dataStr = msg.toString()

                    val t = System.currentTimeMillis().toString()
                    val tk = tkEnc["_m_h5_tk"] ?: ""
                    val tkPart = if (tk.length > 32) tk.substring(0, 32) else tk
                    val sign = ykTSign(tkPart, t, APP_KEY, dataStr)

                    Log.d(TAG, "fetchDanmaku: mat=$mat t=$t sign=${sign.take(8)}...")

                    val apiUrl = "https://acs.youku.com/h5/mopen.youku.danmu.list/1.0/?jsv=2.5.6&appKey=24679788&t=$t&sign=$sign&api=mopen.youku.danmu.list&v=1.0&type=originaljson&dataType=jsonp&timeout=20000&jsonpIncPrefix=utility"
                    val req = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", PC_UA)
                        .header("Referer", "https://v.youku.com")
                        .header("Cookie", cookies)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .post(("data=$dataStr").toRequestBody(null))
                        .build()

                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val respBody = resp.body?.string()
                        if (respBody != null) {
                            val resultJson = JSONObject(respBody)
                            val dataObj = resultJson.optJSONObject("data")
                            if (dataObj != null) {
                                val danmuInfo = dataObj.optString("result", "")
                                if (danmuInfo != null) {
                                    val danmuResult = JSONObject(danmuInfo)
                                    if (danmuResult.optString("code", "") != "-1") {
                                        val danmuData = JSONObject(danmuResult.optString("data", "{}"))
                                        val danmuArray = danmuData.optJSONArray("result")
                                        if (danmuArray != null) {
                                            for (i in 0 until danmuArray.length()) {
                                                val dm = danmuArray.optJSONObject(i) ?: continue
                                                val line = formatYoukuBarrage(dm)
                                                if (line != null) {
                                                    xmlBuilder.append(line)
                                                    hasData = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            xmlBuilder.append("</i>")
            Log.d(TAG, "fetchDanmaku: hasData=$hasData")
            if (!hasData) {
                Log.w(TAG, "no danmaku data")
                return null
            }

            ByteArrayInputStream(xmlBuilder.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "fetchDanmaku failed for $url", e)
            null
        }
    }

    private fun formatYoukuBarrage(item: JSONObject): String? {
        val playat = item.optLong("playat", -1L)
        if (playat < 0) return null

        val timepoint = playat / 1000.0
        val content = item.optString("content", "")
        if (content.isBlank() || containsInvalidChars(content)) return null

        var color = 0xFFFFFF
        try {
            val propertisStr = item.optString("propertis", "{}")
            val propertis = JSONObject(propertisStr)
            if (!propertis.isNull("color")) {
                val rawColor = propertis.opt("color")
                if (rawColor is Number) {
                    color = rawColor.toInt()
                } else if (rawColor is String) {
                    val match = Regex("""(\d+)""").find(rawColor)
                    if (match != null) {
                        color = match.groupValues[1].toInt()
                    }
                }
            }
        } catch (_: Exception) {
        }

        return "  <d p=\"$timepoint,1,25,$color,0\">${escapeXml(content)}</d>\n"
    }

    private fun getCna(): String {
        return try {
            val req = Request.Builder()
                .url("https://log.mmstat.com/eg.js")
                .header("User-Agent", PC_UA)
                .get()
                .build()
            val resp = client.newCall(req).execute()
            for (cookie in resp.headers("Set-Cookie")) {
                val trimmed = cookie.split(";").firstOrNull()?.trim() ?: ""
                if (trimmed.startsWith("cna=")) {
                    return trimmed.removePrefix("cna=")
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun getTkEnc(): Map<String, String>? {
        return try {
            val req = Request.Builder()
                .url("https://acs.youku.com/h5/mtop.com.youku.aplatform.weakget/1.0/?jsv=2.5.1&appKey=24679788")
                .header("Content-Type", "application/json")
                .header("User-Agent", PC_UA)
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val result = mutableMapOf<String, String>()
            for (cookie in resp.headers("Set-Cookie")) {
                val trimmed = cookie.split(";").firstOrNull()?.trim() ?: ""
                when {
                    trimmed.startsWith("_m_h5_tk=") ->
                        result["_m_h5_tk"] = trimmed.removePrefix("_m_h5_tk=")
                    trimmed.startsWith("_m_h5_tk_enc=") ->
                        result["_m_h5_tk_enc"] = trimmed.removePrefix("_m_h5_tk_enc=")
                }
            }
            if (!result.containsKey("_m_h5_tk") || !result.containsKey("_m_h5_tk_enc")) null
            else result
        } catch (_: Exception) {
            null
        }
    }

    private fun getVideoDuration(videoId: String): Long {
        return try {
            val apiUrl = "https://openapi.youku.com/v2/videos/show.json?client_id=53e6cc67237fc59a&video_id=$videoId&package=com.huawei.hwvplayer.youku&ext=show"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", PC_UA)
                .get()
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string()
                if (body != null) {
                    JSONObject(body).optLong("duration", 0L)
                } else 0L
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun ykMsgSign(msg: String): String {
        return md5(msg + DANMAKU_SECRET)
    }

    private fun ykTSign(token: String, t: String, appkey: String, data: String): String {
        return md5("$token&$t&$appkey&$data")
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun containsInvalidChars(s: String): Boolean {
        for (c in s) {
            if (c == '<' || c == '>' || c == '&' || c == ' ' || c == '\b') return true
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
        private const val APP_KEY = "24679788"
        private const val DANMAKU_SECRET = "MkmC9SoIw6xCkSKHhJ7b5D2r51kBiREr"
        private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TAG = "YoukuDanmakuFetcher"
    }
}
