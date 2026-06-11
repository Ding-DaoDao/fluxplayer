package dev.anilbeesetti.nextplayer.core.data.danmaku

import android.util.Log
import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.math.roundToInt

class QiyiDanmakuFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .build()
) : PlatformDanmakuFetcher {

    override val name: String = "爱奇艺弹幕"
    override val sourceId: String = "qiyi"

    override fun match(url: String): Boolean {
        return url.contains("iqiyi.com") || url.contains("qiyi.com")
    }

    override suspend fun search(keyword: String): List<AnimeMatch> {
        Log.d(TAG, "========== search START: keyword=$keyword ==========")
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://pcw-api.iqiyi.com/strategy/pcw/data/soBaseCardLeftSide?pageNum=1&key=$encoded"
            Log.d(TAG, "search: GET $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.iqiyi.com/")
                .get()
                .build()
            val response = client.newCall(request).execute()
            Log.d(TAG, "search: HTTP ${response.code}")
            if (!response.isSuccessful) {
                Log.w(TAG, "search: HTTP ${response.code} FAILED")
                return emptyList()
            }

            val body = response.body?.string() ?: ""
            if (body.isBlank()) { Log.w(TAG, "search: empty body"); return emptyList() }
            Log.d(TAG, "search: body len=${body.length}")
            val json = JSONObject(body)
            val formatData = json.optJSONObject("data")
                ?.optJSONObject("formatData")
            if (formatData == null) {
                Log.w(TAG, "search: no data.formatData, keys=${json.keys().asSequence().toList()}")
                return emptyList()
            }

            val results = mutableListOf<AnimeMatch>()

            val list = formatData.optJSONArray("list") ?: JSONArray()
            Log.d(TAG, "search: list length=${list.length()}")
            for (i in 0 until list.length()) {
                val rs = list.optJSONObject(i) ?: continue
                val videoDocType = rs.optInt("videoDocType", 0)
                val siteName = rs.optString("siteName", "")
                if (videoDocType != 1) { Log.d(TAG, "search: skip list[$i] videoDocType=$videoDocType"); continue }
                if (!siteName.contains("奇")) { Log.d(TAG, "search: skip list[$i] siteName=$siteName"); continue }

                val title = rs.optString("g_title", "")
                val link = rs.optString("g_main_link", "")
                if (title.isBlank() || link.isBlank()) continue

                results.add(
                    AnimeMatch(
                        animeId = link.hashCode(),
                        title = Regex("<[^>]+>").replace(title, "") + "⭐来源：$siteName",
                        type = "iqiyi",
                        url = link
                    )
                )
            }
            Log.d(TAG, "search: from list: ${results.size} results")

            val intentList = formatData.optJSONArray("intentList")
            if (intentList != null) {
                Log.d(TAG, "search: intentList length=${intentList.length()}")
                for (i in 0 until intentList.length()) {
                    val rs = intentList.optJSONObject(i) ?: continue
                    val title = rs.optString("g_title", "")
                    val link = rs.optString("g_main_link", "")
                    val meta = rs.optString("g_meta", "")
                    if (title.isBlank() || link.isBlank()) continue
                    results.add(
                        AnimeMatch(
                            animeId = link.hashCode(),
                            title = Regex("<[^>]+>").replace(title, "") + "⭐$meta",
                            type = "iqiyi",
                            url = link
                        )
                    )
                }
                Log.d(TAG, "search: after intentList: ${results.size} results")
            }

            results
        } catch (e: Exception) {
            Log.e(TAG, "search: EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }.also {
            Log.d(TAG, "========== search END: ${it.size} results ==========")
        }
    }

    override suspend fun getEpisodes(anime: AnimeMatch): List<EpisodeInfo> {
        val url = anime.url ?: run { Log.w(TAG, "getEpisodes: anime.url is null"); return emptyList() }
        val mobileUrl = url.replace("www.", "m.")
        Log.d(TAG, "========== getEpisodes START: url=$url mobileUrl=$mobileUrl ==========")

        try {
            // Step 1: Fetch mobile HTML (MUST use mobile UA to get SSR page with JSON data)
            val htmlReq = Request.Builder()
                .url(mobileUrl)
                .header("User-Agent", MOBILE_UA)
                .header("Referer", "https://www.iqiyi.com/")
                .get()
                .build()
            val htmlResp = client.newCall(htmlReq).execute()
            if (!htmlResp.isSuccessful) {
                Log.w(TAG, "getEpisodes HTTP ${htmlResp.code}")
                return emptyList()
            }
            val html = htmlResp.body?.string() ?: return emptyList()

            Log.d(TAG, "getEpisodes: html len=${html.length}")

            // Step 2: Extract albumInfo and videoInfo from mobile HTML
            var videoName = ""
            var albumQipuId = ""
            var channelName = ""

            val albumInfoRaw = extractJsonField(html, "albumInfo", "albumListInfo")
            val albumInfoJson = albumInfoRaw as? JSONObject
            if (albumInfoJson != null) {
                Log.d(TAG, "getEpisodes: found albumInfo")
                videoName = albumInfoJson.optString("albumName", "")
            }

            // videoType is now BEFORE videoInfo in iQiyi SSR HTML, so extract videoInfo by brace matching
            val videoInfoJson = extractBracedJson(html, "videoInfo")
            if (videoInfoJson != null) {
                Log.d(TAG, "getEpisodes: found videoInfo")
                if (videoName.isBlank()) {
                    videoName = videoInfoJson.optString("videoName", "")
                }
                channelName = videoInfoJson.optString("channelName", "")
                albumQipuId = videoInfoJson.optString("albumQipuId", "").ifBlank { null }
                    ?: videoInfoJson.optString("albumId", "").ifBlank { null }
                    ?: ""
            }

            // Step 3: Try extractQipuIdFromHtml if still blank
            if (albumQipuId.isBlank()) {
                albumQipuId = extractQipuIdFromHtml(html)
            }

            // Step 4: If still blank, try desktop HTML
            if (albumQipuId.isBlank()) {
                Log.w(TAG, "getEpisodes: cannot find albumQipuId, html sample=${html.take(1000)}")
                val desktopResp = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", PC_UA)
                        .header("Referer", "https://www.iqiyi.com/")
                        .get()
                        .build()
                ).execute()
                if (desktopResp.isSuccessful) {
                    val desktopHtml = desktopResp.body?.string()
                    if (desktopHtml != null) {
                        val desktopVideoInfoRaw = extractJsonField(desktopHtml, "videoInfo", "videoType")
                        val desktopVideoInfo = desktopVideoInfoRaw as? JSONObject
                        if (desktopVideoInfo != null) {
                            if (videoName.isBlank()) {
                                videoName = desktopVideoInfo.optString("videoName", "")
                            }
                            channelName = desktopVideoInfo.optString("channelName", "")
                            albumQipuId = desktopVideoInfo.optString("albumQipuId", "").ifBlank { null }
                                ?: desktopVideoInfo.optString("albumId", "").ifBlank { null }
                                ?: ""
                        }
                        if (albumQipuId.isBlank()) {
                            albumQipuId = extractQipuIdFromHtml(desktopHtml)
                        }
                    }
                }
            }

            // Step 5: If still blank, try accelerator API
            if (albumQipuId.isBlank()) {
                try {
                    val accReq = Request.Builder()
                        .url("https://mesh.if.iqiyi.com/player/lw/lwplay/accelerator.js?apiVer=3")
                        .header("User-Agent", PC_UA)
                        .header("Referer", mobileUrl)
                        .get()
                        .build()
                    val accResp = client.newCall(accReq).execute()
                    if (accResp.isSuccessful) {
                        val accBody = accResp.body?.string()
                        if (accBody != null) {
                            val accJson = extractQiyiProphetData(accBody)
                            if (accJson != null) {
                                val aObj = accJson.optJSONObject("ao")
                                if (aObj != null) {
                                    val dataObj = aObj.optJSONObject("data")
                                    if (dataObj != null) {
                                        val sr = dataObj.optJSONObject("showResponse")
                                        val vi = sr?.optJSONObject("videoInfo")

                                        val ids = mutableListOf<String>()

                                        if (vi != null) {
                                            val id = vi.optString("albumId", "")
                                            if (id.isNotBlank()) ids.add(id)
                                        }

                                        if (sr != null) {
                                            val pingbackParam = sr.optJSONObject("pingbackParam")
                                            if (pingbackParam != null) {
                                                val id = pingbackParam.optString("b", "")
                                                if (id.isNotBlank()) ids.add(id)
                                            }
                                        }

                                        val dataObj2 = accJson.optJSONObject("data")
                                        if (dataObj2 != null) {
                                            val albums = dataObj2.optJSONArray("albums")
                                            if (albums != null) {
                                                val firstAlbum = albums.optJSONObject(0)
                                                if (firstAlbum != null) {
                                                    val id = firstAlbum.optString("albumId", "")
                                                    if (id.isNotBlank()) ids.add(id)
                                                }
                                            }
                                        }

                                        for (id in ids.distinct()) {
                                            if (videoName.isBlank()) {
                                                videoName = vi?.optString("albumName", "") ?: ""
                                            }
                                            channelName = vi?.optString("channelName", "") ?: ""

                                            val testEpisodes = fetchPagedEpisodes(id)
                                            if (testEpisodes.isNotEmpty()) {
                                                albumQipuId = id
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // swallow, will check albumQipuId below
                }
            }

            // If still blank and this is a single video page (v_xxx.html), try video info API
            if (albumQipuId.isBlank()) {
                val isSingleVideo = url.contains("/v_")
                if (isSingleVideo) {
                    val tvid = extractTvidFromUrl(url)
                    if (tvid != null) {
                        Log.d(TAG, "getEpisodes: v_ URL detected, tvid=$tvid, trying video info API")
                        albumQipuId = resolveAlbumIdFromTvid(tvid)
                        if (albumQipuId.isNotBlank()) {
                            Log.d(TAG, "getEpisodes: resolved albumQipuId=$albumQipuId from tvid=$tvid")
                        }
                    }
                }
                if (isSingleVideo && albumQipuId.isBlank()) {
                    Log.d(TAG, "getEpisodes: single video URL (v_), returning 1 episode")
                    val title = if (videoName.isBlank()) "正片" else videoName
                    return listOf(EpisodeInfo(url.hashCode(), anime.animeId, title, 1, url))
                }
                if (albumQipuId.isBlank()) {
                    Log.w(TAG, "still cannot find albumQipuId")
                    return emptyList()
                }
            }

            Log.d(TAG, "getEpisodes: albumQipuId=$albumQipuId channelName=$channelName videoName=$videoName")

            // Step 6: Movie handling
            if (channelName == "电影") {
                if (!videoName.contains("正片")) {
                    val result = mutableListOf<EpisodeInfo>()

                    val summaryRaw = extractJsonField(html, "summary", "count")
                    val summaryJson = summaryRaw as? JSONArray

                    if (summaryJson != null && summaryJson.length() > 0) {
                        for (j in 0 until summaryJson.length()) {
                            val yearObj = summaryJson.optJSONObject(j) ?: continue
                            val year = yearObj.optString("year", "")
                            if (year.isBlank()) continue

                            try {
                                val svUrl = "https://pcw-api.iqiyi.com/album/source/svlistinfo?cid=6&sourceid=$albumQipuId&timelist=$year&callback=window.Q.__callbacks__.cbp5bps9"
                                val svResp = client.newCall(
                                    Request.Builder()
                                        .url(svUrl)
                                        .header("User-Agent", PC_UA)
                                        .header("Referer", "https://www.iqiyi.com/")
                                        .get()
                                        .build()
                                ).execute()
                                if (svResp.isSuccessful) {
                                    val svBody = svResp.body?.string()
                                    if (svBody != null) {
                                        val svJson = extractJsonFromJsonp(svBody)
                                        if (svJson != null) {
                                            val yearData = svJson.optJSONObject("data")?.optJSONArray(year)
                                            if (yearData != null) {
                                                addQiyiEpisodes(yearData, result)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // continue with next year
                            }
                        }
                    } else {
                        // No summary data - try paged episodes
                        val pagedEpisodes = fetchPagedEpisodes(albumQipuId)
                        result.addAll(pagedEpisodes)
                    }

                    Log.d(TAG, "getEpisodes: total ${result.size} episodes")

                    if (result.isEmpty()) {
                        val title = if (videoName.isBlank()) "正片" else videoName
                        return listOf(EpisodeInfo(url.hashCode(), anime.animeId, title, 1, url))
                    }
                    return result
                }
            }

            // Step 7: Non-movie content — try to fetch episode list if we have albumQipuId
            val title = if (videoName.isBlank()) "正片" else videoName

            // If we have an albumQipuId, try to fetch the full episode list
            if (albumQipuId.isNotBlank()) {
                Log.d(TAG, "getEpisodes: non-movie, fetching paged episodes for albumQipuId=$albumQipuId")
                val pagedEpisodes = fetchPagedEpisodes(albumQipuId)
                if (pagedEpisodes.isNotEmpty()) {
                    Log.d(TAG, "getEpisodes: fetched ${pagedEpisodes.size} episodes from album")
                    return pagedEpisodes
                }
                Log.w(TAG, "getEpisodes: paged episodes empty, falling back to single episode")
            }

            return listOf(EpisodeInfo(url.hashCode(), anime.animeId, title, 1, url))
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodes: EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
            return emptyList()
        }
        // unreachable — all branches return above
    }

    override suspend fun fetchDanmaku(url: String): InputStream? {
        Log.d(TAG, "fetchDanmaku called for $url")
        try {
            // Fetch the page HTML
            val htmlReq = Request.Builder()
                .url(url)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.iqiyi.com/")
                .get()
                .build()
            val htmlResp = client.newCall(htmlReq).execute()
            if (!htmlResp.isSuccessful) return null
            val html = htmlResp.body?.string() ?: return null

            // Extract videoInfo from HTML
            val videoInfoRaw = try {
                extractJsonField(html, "videoInfo", "videoType")
            } catch (e: Exception) {
                null
            }
            val videoInfo = videoInfoRaw as? JSONObject ?: throw Exception("no videoInfo")

            val duration = parseDuration(videoInfo.optString("duration", "0"))
            val albumId = videoInfo.optString("albumId", "")
            var tvid = videoInfo.optLong("tvid", 0).toString()
            val categoryId = videoInfo.optString("cid", "")

            if (tvid == "0" || tvid.isBlank()) {
                throw Exception("tvid=0")
            }

            return fetchDanmakuSegments(url, tvid, albumId, categoryId, duration)
        } catch (e: Exception) {
            // Accelerator fallback
            Log.d(TAG, "fetchDanmaku: trying accelerator fallback")
            try {
                val accReq = Request.Builder()
                    .url("https://mesh.if.iqiyi.com/player/lw/lwplay/accelerator.js?apiVer=3")
                    .header("User-Agent", PC_UA)
                    .header("Referer", url)
                    .get()
                    .build()
                val accResp = client.newCall(accReq).execute()
                if (!accResp.isSuccessful) return null
                val accBody = accResp.body?.string() ?: return null
                Log.d(TAG, "accelerator response body: $accBody")

                val accJson = extractQiyiProphetData(accBody) ?: return null

                var tvid = accJson.optString("tvid", "")
                if (tvid.isBlank() || tvid == "0") {
                    tvid = accJson.optLong("tvId", 0).toString()
                }

                var albumId = ""
                var categoryId = ""
                var duration = 0L

                // Try to extract from prophet data (new format)
                val aObj = accJson.optJSONObject("ao")
                if (aObj != null) {
                    val vi = aObj.optJSONObject("data")
                        ?.optJSONObject("showResponse")
                        ?.optJSONObject("videoInfo")
                    if (vi != null) {
                        albumId = vi.optString("albumId", "")
                        if (tvid.isBlank() || tvid == "0") {
                            tvid = vi.optLong("tvId", 0).toString()
                        }
                        categoryId = vi.optString("cid", "")
                        duration = vi.optLong("videoDuration", 0)
                    }
                }

                // Try old data format for remaining missing fields
                if (albumId.isBlank() || tvid.isBlank() || tvid == "0" || duration == 0L) {
                    val oldData = accJson.optJSONObject("data")
                    if (oldData != null) {
                        if (albumId.isBlank()) {
                            val albums = oldData.optJSONArray("albums")
                            albumId = albums?.optJSONObject(0)?.optString("albumId", "")
                                ?: oldData.optString("albumId", "")
                        }
                        if (tvid.isBlank() || tvid == "0") {
                            tvid = oldData.optString("tvid", "")
                            if (tvid.isBlank() || tvid == "0") {
                                val videos = oldData.optJSONArray("videos")
                                tvid = (videos?.optJSONObject(0)?.optLong("tvId", 0) ?: 0).toString()
                            }
                        }
                        if (categoryId.isBlank()) {
                            categoryId = oldData.optString("categoryId", "")
                        }
                        if (duration == 0L) {
                            val videos = oldData.optJSONArray("videos")
                            duration = videos?.optJSONObject(0)?.optLong("durationSeconds", 0) ?: 0
                        }
                    }
                }

                if (tvid.isBlank() || tvid == "0") return null

                return fetchDanmakuSegments(url, tvid, albumId, categoryId, duration)
            } catch (e2: Exception) {
                Log.e(TAG, "fetchDanmaku failed for $url", e)
                return null
            }
        }
    }

    private suspend fun fetchDanmakuSegments(
        url: String,
        tvid: String,
        albumId: String,
        categoryId: String,
        duration: Long
    ): InputStream? {
        if (tvid.length < 4) return null

        val prefix1 = tvid.substring(tvid.length - 4, tvid.length - 2)
        val prefix2 = tvid.substring(tvid.length - 2)
        val params = "rn=0.0123456789123456&business=danmu&is_iqiyi=true&is_video_page=true&tvid=$tvid&albumid=$albumId&categoryid=$categoryId&qypid=01010021010000000000"

        val pageCount = if (duration > 0) maxOf(1, (duration / 300.0).roundToInt()) else 1

        val xmlBuilder = StringBuilder()
        xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<i>\n")

        // 并发下载所有分页弹幕（参考海阔视界 batchExecute 多线程模式）
        val pageResults = coroutineScope {
            (1..pageCount).map { page ->
                async(Dispatchers.IO) {
                    fetchDanmakuPage(url, tvid, prefix1, prefix2, params, page)
                }
            }.awaitAll()
        }

        var hasData = false
        for (pageXml in pageResults) {
            if (pageXml != null) {
                xmlBuilder.append(pageXml)
                hasData = true
            }
        }

        xmlBuilder.append("</i>")
        if (!hasData) return null

        val bytes = xmlBuilder.toString().toByteArray(Charsets.UTF_8)
        return ByteArrayInputStream(bytes)
    }

    /**
     * 下载单个分页的弹幕数据并格式化为 XML 片段。
     * 每个分页覆盖约 5 分钟时长（300 秒）。
     */
    private fun fetchDanmakuPage(
        referer: String,
        tvid: String,
        prefix1: String,
        prefix2: String,
        params: String,
        page: Int
    ): String? {
        return try {
            val segUrl = "https://cmts.iqiyi.com/bullet/$prefix1/$prefix2/${tvid}_300_$page.z?$params"
            val segReq = Request.Builder()
                .url(segUrl)
                .header("User-Agent", PC_UA)
                .header("Referer", referer)
                .get()
                .build()
            val segResp = client.newCall(segReq).execute()
            if (!segResp.isSuccessful) return null
            val segBytes = segResp.body?.bytes() ?: return null
            if (segBytes.isEmpty()) return null

            val decompressed = zlibDecompress(segBytes) ?: return null
            val contents = extractXmlTags(decompressed, "content")
            val showTimes = extractXmlTags(decompressed, "showTime")
            val colors = extractXmlTags(decompressed, "color")

            val pageBuilder = StringBuilder()
            for (j in contents.indices) {
                val dmContent = contents.getOrNull(j) ?: continue
                if (dmContent.isBlank()) continue
                if (containsInvalidChars(dmContent)) continue

                val showTime = showTimes.getOrNull(j)
                val timepoint = showTime?.toDoubleOrNull() ?: 0.0

                val hexColor = colors.getOrNull(j) ?: "FFFFFF"
                val color = try {
                    hexColor.toInt(16)
                } catch (e: Exception) {
                    16777215
                }

                pageBuilder.append("  <d p=\"$timepoint,1,25,$color,0\">")
                pageBuilder.append(escapeXml(dmContent))
                pageBuilder.append("</d>\n")
            }

            if (pageBuilder.isEmpty()) null else pageBuilder.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonField(html: String, fieldName: String, nextField: String): Any? {
        val pattern = Regex("\"$fieldName\"([\\s\\S]+?)(?=,\"$nextField\")")
        val match = pattern.find(html) ?: return null
        // Strip leading ":" from captured group (the pattern captures `:{"..."}`)
        var jsonStr = match.groupValues[1].trim().removePrefix(":")
        return try {
            when {
                jsonStr.startsWith("{") -> JSONObject(jsonStr)
                jsonStr.startsWith("[") -> JSONArray(jsonStr)
                else -> jsonStr
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract a JSON object by finding `"fieldName":{...}` and matching braces.
     * Used when the next field anchor is unreliable (e.g., videoType before videoInfo).
     */
    private fun extractBracedJson(html: String, fieldName: String): JSONObject? {
        val keyPattern = "\"$fieldName\":"
        val startIdx = html.indexOf(keyPattern)
        if (startIdx < 0) return null
        val braceStart = html.indexOf('{', startIdx + keyPattern.length)
        if (braceStart < 0) return null

        var depth = 0
        var inString = false
        var escape = false
        for (i in braceStart until html.length) {
            val c = html[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                when (c) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return try {
                                JSONObject(html.substring(braceStart, i + 1))
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractJsonFromJsonp(body: String): JSONObject? {
        val jsonStart = body.indexOf("{\"code\"")
        if (jsonStart >= 0) {
            val catchIdx = body.indexOf("catch(e)")
            val searchEnd = if (catchIdx > jsonStart) catchIdx else body.length

            var depth = 0
            var found = false
            var endPos = -1
            for (i in jsonStart until searchEnd) {
                when (body[i]) {
                    '{' -> { depth++; found = true }
                    '}' -> {
                        depth--
                        if (found && depth == 0) {
                            endPos = i
                            break
                        }
                    }
                }
            }
            if (found && endPos >= 0) {
                try {
                    return JSONObject(body.substring(jsonStart, endPos + 1))
                } catch (e: Exception) {}
            }
        }

        // Fallback: try to find any JSON object
        val plain = Regex("\\{[\\s\\S]+\\}").find(body)
        if (plain != null) {
            try {
                return JSONObject(plain.value)
            } catch (e: Exception) {}
        }
        return null
    }

    private fun extractQiyiProphetData(body: String): JSONObject? {
        return extractQiyiProphetData(body, body.indexOf("window.QiyiPlayerProphetData = "))
    }

    private fun extractQiyiProphetData(body: String, jsonStart: Int): JSONObject? {
        if (jsonStart < 0) return null
        val markerLen = "window.QiyiPlayerProphetData = ".length
        val braceStart = body.indexOf("{", jsonStart + markerLen)
        if (braceStart < 0) return null

        var braceEnd = braceStart
        var depth = 1
        while (depth > 0 && braceEnd < body.length - 1) {
            braceEnd++
            when (body[braceEnd]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        if (depth != 0) return null

        return try {
            JSONObject(body.substring(braceStart, braceEnd + 1))
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchPagedEpisodes(albumId: String): List<EpisodeInfo> {
        val episodes = mutableListOf<EpisodeInfo>()
        var page = 1
        var totalPage = 2
        while (totalPage - page >= 0) {
            val avUrl = "https://pcw-api.iqiyi.com/albums/album/avlistinfo?aid=$albumId&size=200&page=$page&callback=window.Q.__callbacks__.cbu0ch7s"
            try {
                val avResp = client.newCall(
                    Request.Builder()
                        .url(avUrl)
                        .header("User-Agent", PC_UA)
                        .header("Referer", "https://www.iqiyi.com/")
                        .get()
                        .build()
                ).execute()
                if (!avResp.isSuccessful) break
                val avBody = avResp.body?.string() ?: break
                val avJson = extractJsonFromJsonp(avBody) ?: break
                val avData = avJson.optJSONObject("data") ?: break
                val epsodelist = avData.optJSONArray("epsodelist") ?: break
                addQiyiEpisodes(epsodelist, episodes)
                page++
                totalPage = avData.optInt("page", 0)
            } catch (e: Exception) {
                break
            }
        }
        return episodes
    }

    private fun extractQipuIdFromHtml(html: String): String {
        val patterns = listOf(
            Regex("\"albumQipuId\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"albumQipuId\"\\s*:\\s*'([^']+)'"),
            Regex("\"albumId\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"aid\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"albumId\"\\s*:\\s*'([^']+)'"),
            Regex("\"qipuId\"\\s*:\\s*\"([^\"]+)\"")
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val id = match.groupValues[1]
                if (id.isNotBlank()) return id
            }
        }
        return ""
    }

    private fun addQiyiEpisodes(list: JSONArray, result: MutableList<EpisodeInfo>) {
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val playUrl = item.optString("playUrl", "")
            if (playUrl.isBlank()) continue
            val period = item.optString("period", "")
            val name = item.optString("name", "")
            val subtitle = item.optString("subtitle", "")
            val title = (if (period.isNotBlank()) "$period\t" else "") + "${name}_$subtitle"
            result.add(EpisodeInfo(playUrl.hashCode(), 0, title, result.size + 1, playUrl))
        }
    }

    private fun parseDuration(duration: String): Long {
        val parts = duration.split(":")
        return when (parts.size) {
            1 -> parts[0].toLongOrNull() ?: 0L
            2 -> {
                val minutes = (parts[0].toLongOrNull() ?: 0L) * 60
                val seconds = parts[1].toLongOrNull() ?: 0L
                minutes + seconds
            }
            else -> duration.toLongOrNull() ?: 0L
        }
    }

    private fun zlibDecompress(data: ByteArray): String? {
        // Try standard zlib first
        try {
            val inflater = Inflater()
            inflater.setInput(data)
            val output = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count <= 0) break
                output.write(buf, 0, count)
            }
            inflater.end()
            if (output.size() > 0) return output.toString("UTF-8")
        } catch (e: Exception) {}

        // Try raw deflate
        try {
            val inflater = Inflater(true)
            inflater.setInput(data)
            val output = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count <= 0) break
                output.write(buf, 0, count)
            }
            inflater.end()
            return output.toString("UTF-8")
        } catch (e: Exception) {}

        return null
    }

    private fun extractXmlTags(xml: String, tag: String): List<String> {
        val pattern = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(xml).map { it.groupValues[1].trim() }.toList()
    }

    private fun containsInvalidChars(s: String): Boolean {
        for (c in s) {
            if (c == '<' || c == '>' || c == '&' || c == '\u0000' || c == '\b') return true
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

    /**
     * 从 iQiyi v_xxx.html 格式的 URL 中提取 tvid。
     */
    private fun extractTvidFromUrl(url: String): String? {
        val match = Regex("/v_([^.]+)").find(url)
        return match?.groupValues?.get(1)
    }

    /**
     * 通过 iQiyi 视频信息 API 反查专辑 ID。
     */
    private suspend fun resolveAlbumIdFromTvid(tvid: String): String {
        return try {
            val apiUrl = "https://pcw-api.iqiyi.com/video/video/info/$tvid"
            Log.d(TAG, "resolveAlbumIdFromTvid: GET $apiUrl")
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.iqiyi.com/")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "resolveAlbumIdFromTvid: HTTP ${resp.code}")
                return ""
            }
            val body = resp.body?.string() ?: return ""
            Log.d(TAG, "resolveAlbumIdFromTvid: body len=${body.length}, sample=${body.take(300)}")
            val json = JSONObject(body)
            // Try multiple possible paths to find album ID
            val data = json.optJSONObject("data") ?: return ""
            var albumId = data.optString("albumId", "").ifBlank {
                data.optString("album_id", "")
            }.ifBlank {
                data.optString("qipuId", "")
            }.ifBlank {
                data.optJSONObject("album")?.optString("albumId", "") ?: ""
            }.ifBlank {
                data.optString("aid", "")
            }
            // Sometimes albumId is in a nested "album" object
            if (albumId.isBlank()) {
                val albumObj = data.optJSONObject("album")
                if (albumObj != null) {
                    albumId = albumObj.optString("albumId", "").ifBlank {
                        albumObj.optString("id", "")
                    }
                }
            }
            Log.d(TAG, "resolveAlbumIdFromTvid: albumId=$albumId")
            albumId
        } catch (e: Exception) {
            Log.w(TAG, "resolveAlbumIdFromTvid failed: ${e.message}")
            ""
        }
    }

    companion object {
        private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
        private const val TAG = "QiyiDanmakuFetcher"
    }
}
