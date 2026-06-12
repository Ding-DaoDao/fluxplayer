package com.fluxplayer.app.core.data.danmaku

import android.util.Log
import com.fluxplayer.app.core.model.AnimeMatch
import com.fluxplayer.app.core.model.EpisodeInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.json.JSONObject

class BilibiliDanmakuFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .build()
) : PlatformDanmakuFetcher {

    override val name: String = "B站弹幕"
    override val sourceId: String = "bilibili"

    private var debugElemCount = 0

    override fun match(url: String): Boolean {
        return url.contains("bilibili.com") || url.contains("b23.tv")
    }

    override suspend fun fetchDanmaku(url: String): InputStream? {
        return try {
            val cid = resolveCid(url) ?: run {
                Log.w(TAG, "Failed to resolve cid for $url")
                return null
            }
            val danmakuUrl = "https://comment.bilibili.com/$cid.xml"
            Log.d(TAG, "Fetching danmaku from $danmakuUrl")

            val request = Request.Builder()
                .url(danmakuUrl)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.bilibili.com/")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for $danmakuUrl")
                return null
            }

            val contentType = response.header("Content-Type", "?")
            Log.d(TAG, "Response Content-Type: $contentType")

            val bodyBytes = response.body?.bytes()
            if (bodyBytes == null || bodyBytes.size < 2) return null

            val firstByte = bodyBytes[0].toInt() and 0xFF
            val secondByte = bodyBytes[1].toInt() and 0xFF

            if (firstByte == 0x1F && secondByte == 0x8B) {
                Log.d(TAG, "Response is gzip-compressed, decompressing (${bodyBytes.size} bytes)")
                return GZIPInputStream(ByteArrayInputStream(bodyBytes))
            }

            if (bodyBytes[0].toInt() == 0x3C) {
                Log.d(TAG, "Response is XML (${bodyBytes.size} bytes)")
                return ByteArrayInputStream(bodyBytes)
            }

            Log.w(TAG, "XML endpoint returned non-XML (content-type=$contentType, first=0x${firstByte.toString(16)}, size=${bodyBytes.size}), trying alternatives")

            val listXml = fetchDanmakuListXml(cid)
            if (listXml != null) return listXml

            fetchDanmakuProtobuf(cid)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDanmaku failed for $url", e)
            null
        }
    }

    private fun fetchDanmakuListXml(cid: String): InputStream? {
        return try {
            val apiUrl = "https://api.bilibili.com/x/v2/dm/list.so?oid=$cid"
            Log.d(TAG, "Trying list.so API: $apiUrl")

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.bilibili.com/")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "list.so HTTP ${response.code}")
                return null
            }

            val bodyBytes = response.body?.bytes()
            if (bodyBytes == null || bodyBytes.size < 4) return null

            if (bodyBytes[0].toInt() == 0x3C) {
                Log.d(TAG, "list.so returned XML (${bodyBytes.size} bytes)")
                ByteArrayInputStream(bodyBytes)
            } else {
                val first = bodyBytes[0].toInt() and 0xFF
                Log.w(TAG, "list.so returned non-XML (first=0x${first.toString(16)}, size=${bodyBytes.size})")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchDanmakuListXml failed for cid=$cid", e)
            null
        }
    }

    private fun fetchDanmakuProtobuf(cid: String): InputStream? {
        return try {
            val xmlBuilder = StringBuilder()
            xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<i>\n")

            val headers = mapOf(
                "User-Agent" to PC_UA,
                "Referer" to "https://www.bilibili.com/"
            )

            var hasData = false
            for (segment in 1..10) {
                val segUrl = "https://api.bilibili.com/x/v2/dm/web/seg.so?oid=$cid&type=1&segment_index=$segment"
                val segRequest = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", headers["User-Agent"]!!)
                    .header("Referer", headers["Referer"]!!)
                    .get()
                    .build()

                val segResponse = client.newCall(segRequest).execute()
                if (!segResponse.isSuccessful) break

                val segBytes = segResponse.body?.bytes() ?: break
                if (segBytes.isEmpty()) break

                if (segment == 1) {
                    val firstHex = segBytes.take(40).joinToString(" ") { "%02x".format(it) }
                    Log.d(TAG, "seg.so first 40 bytes: $firstHex")
                }

                val entries = parseDanmakuProtobuf(segBytes)
                if (entries.isEmpty()) break

                hasData = true
                for (dm in entries) {
                    xmlBuilder.append("  <d p=\"")
                        .append(dm.progress / 1000.0f)
                        .append(",")
                        .append(dm.mode)
                        .append(",")
                        .append(dm.fontsize)
                        .append(",")
                        .append(dm.color)
                        .append(",0,0,0,0")
                        .append("\">")
                        .append(escapeXml(dm.content))
                        .append("</d>\n")
                }
            }

            if (!hasData) {
                Log.w(TAG, "Protobuf API returned no data for cid=$cid")
                return null
            }

            xmlBuilder.append("</i>")
            val xmlBytes = xmlBuilder.toString().toByteArray(Charsets.UTF_8)
            Log.d(TAG, "Protobuf API returned ${xmlBytes.size} bytes (XML) for cid=$cid")
            ByteArrayInputStream(xmlBytes)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDanmakuProtobuf failed for cid=$cid", e)
            null
        }
    }

    private data class DanmakuEntry(
        val progress: Int,
        val mode: Int,
        val fontsize: Int,
        val color: Int,
        val content: String
    )

    private fun parseDanmakuProtobuf(bytes: ByteArray): List<DanmakuEntry> {
        val result = mutableListOf<DanmakuEntry>()
        try {
            val buf = ByteArrayInputStream(bytes)
            var tag = readVarint(buf)
            while (tag >= 0) {
                if (buf.available() <= 0) break

                val fieldNumber = (tag shr 3).toInt()
                val wireType = (tag and 0x7).toInt()

                if (fieldNumber == 1 && wireType == 2) {
                    val msgLen = readVarint(buf).toInt()
                    val msgBytes = ByteArray(msgLen)
                    if (buf.read(msgBytes) != msgLen) break

                    val entry = parseDanmakuElem(msgBytes)
                    if (entry != null) result.add(entry)
                    tag = readVarint(buf)
                } else {
                    when (wireType) {
                        0 -> readVarint(buf)
                        2 -> {
                            val len = readVarint(buf).toInt()
                            if (buf.skip(len.toLong()) < len) break
                        }
                        else -> break
                    }
                    tag = readVarint(buf)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseDanmakuProtobuf failed after ${result.size} entries", e)
        }
        return result
    }

    private fun parseDanmakuElem(bytes: ByteArray): DanmakuEntry? {
        return try {
            val buf = ByteArrayInputStream(bytes)
            var tag = readVarint(buf)
            val fieldValues = mutableListOf<String>()
            var progress = 0
            var mode = 1
            var fontsize = 25
            var color = 16777215
            var content = ""

            while (tag >= 0) {
                if (buf.available() <= 0) {
                    if (debugElemCount < 3 && fieldValues.isNotEmpty()) {
                        Log.d(TAG, "  elem fields: ${fieldValues.joinToString(", ")}")
                        debugElemCount++
                    }
                    return DanmakuEntry(progress, mode, fontsize, color, content)
                }

                val fieldNumber = (tag shr 3).toInt()
                val wireType = (tag and 0x7).toInt()

                when (wireType) {
                    0 -> {
                        val v = readVarint(buf).toInt()
                        when (fieldNumber) {
                            2 -> progress = v
                            3 -> mode = v
                            4 -> fontsize = v
                            5 -> color = v
                        }
                        if (debugElemCount < 3) {
                            fieldValues.add("f$fieldNumber(varint)=$v")
                        }
                    }
                    1, 5, 6 -> buf.skip(8L)
                    2 -> {
                        val len = readVarint(buf).toInt()
                        if (len > 0 && len <= 4096 && buf.available() >= len) {
                            val strBytes = ByteArray(len)
                            if (buf.read(strBytes) != len) {
                                if (debugElemCount < 3) {
                                    fieldValues.add("f$fieldNumber(read_err)")
                                }
                            } else {
                                val str = String(strBytes, Charsets.UTF_8)
                                if (fieldNumber == 7) content = str
                                if (debugElemCount < 3) {
                                    fieldValues.add("f$fieldNumber(str,len=$len)='${str.take(40)}'")
                                }
                            }
                        } else {
                            buf.skip(len.toLong())
                            if (debugElemCount < 3) {
                                fieldValues.add("f$fieldNumber(skip=$len)")
                            }
                        }
                    }
                }
                tag = readVarint(buf)
            }

            if (debugElemCount < 3 && fieldValues.isNotEmpty()) {
                Log.d(TAG, "  elem fields: ${fieldValues.joinToString(", ")}")
                debugElemCount++
            }
            DanmakuEntry(progress, mode, fontsize, color, content)
        } catch (e: Exception) {
            null
        }
    }

    private fun readVarint(stream: ByteArrayInputStream): Long {
        var result = 0L
        for (shift in 0 until 64 step 7) {
            val b = stream.read()
            if (b < 0) return -1L
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result
        }
        return -1L
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

    private fun resolveCid(url: String): String? {
        if (url.contains("/video/av") || url.contains("?av=") || url.contains("&av=")) {
            val aid = extractAid(url) ?: return null
            return resolveCidByAid(aid)
        }
        if (url.contains("/bangumi/play/ep") || url.contains("ep=")) {
            val epid = extractEpId(url) ?: return null
            return resolveCidByEpId(epid)
        }
        if (url.contains("/bangumi/play/ss")) {
            val ssid = extractSsId(url) ?: return null
            return resolveCidBySeasonId(ssid)
        }
        if (url.contains("/bangumi/play/md") || url.contains("md=")) {
            val mdId = extractMdId(url) ?: return null
            val seasonId = getSeasonIdFromMediaId(mdId) ?: return null
            return resolveCidBySeasonId(seasonId)
        }
        Log.w(TAG, "Unrecognized Bilibili URL pattern: $url")
        return null
    }

    private fun extractAid(url: String): String? {
        val patterns = listOf(
            Regex("""(?:/video/|/|%2F)av(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""[?&]av=(\d+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun extractEpId(url: String): String? {
        val patterns = listOf(
            Regex("""/ep(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""[?&]ep=(\d+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun extractSsId(url: String): String? {
        return Regex("""/ss(\d+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
    }

    private fun extractMdId(url: String): String? {
        return Regex("""/md(\d+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
    }

    private fun resolveCidByAid(aid: String): String? {
        return try {
            val apiUrl = "https://api.bilibili.com/x/web-interface/view?aid=$aid"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.bilibili.com")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val cid = json.optJSONObject("data")?.optString("cid", "")
                if (cid.isNullOrBlank()) null else cid
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "resolveCidByAid failed", e)
            null
        }
    }

    private fun resolveCidByEpId(epid: String): String? {
        return try {
            val apiUrl = "https://api.bilibili.com/pgc/view/web/season?ep_id=$epid"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.bilibili.com")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null

            val json = JSONObject(body)
            val episodes = json.optJSONObject("result")?.optJSONArray("episodes")
                ?: return null

            for (i in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(i) ?: continue
                val link = ep.optString("link", "")
                val epIdStr = ep.optString("ep_id", "")
                if (link.contains(epid) || epIdStr.contains(epid)) {
                    val cid = ep.optString("cid", "")
                    return if (cid.isBlank()) null else cid
                }
            }

            val firstCid = episodes.optJSONObject(0)?.optString("cid", "")
            if (firstCid.isNullOrBlank()) null else firstCid
        } catch (e: Exception) {
            Log.e(TAG, "resolveCidByEpId failed", e)
            null
        }
    }

    private fun resolveCidBySeasonId(seasonId: String): String? {
        return try {
            val apiUrl = "https://api.bilibili.com/pgc/web/season/section?season_id=$seasonId"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.bilibili.com")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val cid = json.optJSONObject("result")
                    ?.optJSONObject("main_section")
                    ?.optJSONArray("episodes")
                    ?.optJSONObject(0)
                    ?.optString("cid", "")
                if (cid.isNullOrBlank()) null else cid
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "resolveCidBySeasonId failed", e)
            null
        }
    }

    private fun getSeasonIdFromMediaId(mdId: String): String? {
        return try {
            val apiUrl = "https://api.bilibili.com/pgc/review/user?media_id=$mdId"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.bilibili.com")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val seasonId = json.optJSONObject("result")
                    ?.optJSONObject("media")
                    ?.optString("season_id", "")
                if (seasonId.isNullOrBlank()) null else seasonId
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "getSeasonIdFromMediaId failed", e)
            null
        }
    }

    override suspend fun search(keyword: String): List<AnimeMatch> {
        Log.d(TAG, "========== search START: keyword=$keyword ==========")
        return try {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            val results = mutableListOf<AnimeMatch>()

            for (searchType in listOf("media_bangumi", "media_ft")) {
                try {
                    Log.d(TAG, "search: trying type=$searchType")
                    val items = searchBiliSearchType(keyword, encodedKeyword, searchType)
                    Log.d(TAG, "search: type=$searchType returned ${items.size} items")
                    results.addAll(items)
                } catch (e: Exception) {
                    Log.w(TAG, "search: type $searchType EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
            Log.d(TAG, "search: combined ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search: EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }.also {
            Log.d(TAG, "========== search END: ${it.size} results ==========")
        }
    }

    private suspend fun searchBiliSearchType(
        keyword: String,
        encodedKeyword: String,
        searchType: String
    ): List<AnimeMatch> {
        return try {
            val params = mutableMapOf(
                "__refresh__" to "true",
                "_extra" to "",
                "context" to "",
                "page" to "1",
                "page_size" to "12",
                "order" to "",
                "duration" to "",
                "from_source" to "",
                "from_spmid" to "333.337",
                "platform" to "pc",
                "highlight" to "1",
                "single_column" to "0",
                "keyword" to keyword,
                "search_type" to searchType,
                "preload" to "true",
                "com2co" to "true"
            )

            Log.d(TAG, "searchBiliSearchType: type=$searchType signing WBI...")
            val signedQuery = signWbi(params)
            val apiUrl = "https://api.bilibili.com/x/web-interface/wbi/search/type?$signedQuery"
            Log.d(TAG, "searchBiliSearchType: GET $apiUrl")

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.bilibili.com")
                .get()
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "searchBiliSearchType: HTTP ${response.code}")
            if (!response.isSuccessful) {
                Log.w(TAG, "searchBiliSearchType: HTTP ${response.code} FAILED")
                return emptyList()
            }

            val body = response.body?.string() ?: ""
            if (body.isBlank()) {
                Log.w(TAG, "searchBiliSearchType: empty body")
                return emptyList()
            }
            Log.d(TAG, "searchBiliSearchType: body len=${body.length}, sample=${body.take(200)}")

            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            val message = json.optString("message", "")
            Log.d(TAG, "searchBiliSearchType: code=$code message=$message")

            val resultArray = json.optJSONObject("data")?.optJSONArray("result")
            if (resultArray == null) {
                Log.w(TAG, "searchBiliSearchType: no data.result, body keys=${json.keys().asSequence().toList()}")
                return emptyList()
            }
            Log.d(TAG, "searchBiliSearchType: result array length=${resultArray.length()}")

            val list = mutableListOf<AnimeMatch>()
            for (i in 0 until resultArray.length()) {
                val item = resultArray.optJSONObject(i) ?: continue
                val seasonId = item.optString("season_id", "")
                if (seasonId.isBlank()) continue

                val title = Regex("<[^>]+>").replace(item.optString("title", ""), "")
                val type = item.optString("type", "")
                val episodeCount = item.optInt("episodes", 0)
                val url = "https://www.bilibili.com/bangumi/play/ss$seasonId"

                list.add(
                    AnimeMatch(
                        animeId = seasonId.toIntOrNull() ?: 0,
                        title = title,
                        type = type,
                        episodeCount = episodeCount,
                        url = url
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "searchBiliSearchType $searchType failed", e)
            emptyList()
        }
    }

    private fun signWbi(params: MutableMap<String, String>): String {
        return try {
            val navRequest = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/nav")
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.bilibili.com")
                .get()
                .build()

            val navResponse = client.newCall(navRequest).execute()
            val navBody = navResponse.body?.string() ?: return fallbackSign(params)

            val navJson = JSONObject(navBody)
            val wbiImg = navJson.optJSONObject("data")?.optJSONObject("wbi_img")
                ?: return fallbackSign(params)

            val imgUrl = wbiImg.optString("img_url", "")
            val subUrl = wbiImg.optString("sub_url", "")

            if (imgUrl.isBlank() || subUrl.isBlank()) return fallbackSign(params)

            val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
            val subKey = subUrl.substringAfterLast("/").substringBefore(".")
            val mixinKey = getMixinKey(imgKey + subKey)

            val currentTime = (System.currentTimeMillis() / 1000).toString()
            params["wts"] = currentTime

            val query = params.keys.sorted().joinToString("&") { key ->
                "${URLEncoder.encode(key, "UTF-8")}=${
                    URLEncoder.encode(params[key]!!, "UTF-8").replace("+", "%20")
                }"
            }

            val wbiSign = md5(query + mixinKey)
            "$query&w_rid=$wbiSign"
        } catch (e: Exception) {
            Log.w(TAG, "signWbi failed, using fallback", e)
            fallbackSign(params)
        }
    }

    private fun fallbackSign(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun getMixinKey(orig: String): String {
        val mixinKeyEncTab = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
        )
        val sb = StringBuilder()
        for (i in mixinKeyEncTab) {
            if (i < orig.length) sb.append(orig[i])
        }
        return sb.toString().take(32)
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override suspend fun getEpisodes(anime: AnimeMatch): List<EpisodeInfo> {
        val seasonId = anime.animeId
        Log.d(TAG, "========== getEpisodes START: seasonId=$seasonId animeTitle=${anime.title} ==========")
        if (seasonId <= 0) {
            Log.w(TAG, "getEpisodes: seasonId <= 0")
            return emptyList()
        }

        return try {
            val url = "https://api.bilibili.com/pgc/view/web/season?season_id=$seasonId"
            Log.d(TAG, "getEpisodes: GET $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PC_UA)
                .header("Referer", "https://www.bilibili.com")
                .get()
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "getEpisodes: HTTP ${response.code}")
            if (!response.isSuccessful) {
                Log.w(TAG, "getEpisodes: HTTP ${response.code} FAILED")
                return emptyList()
            }

            val body = response.body?.string() ?: ""
            if (body.isBlank()) {
                Log.w(TAG, "getEpisodes: empty body")
                return emptyList()
            }
            Log.d(TAG, "getEpisodes: body len=${body.length}, sample=${body.take(200)}")

            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            val message = json.optString("message", "")
            Log.d(TAG, "getEpisodes: code=$code message=$message")

            val episodes = json.optJSONObject("result")?.optJSONArray("episodes")
            if (episodes == null) {
                Log.w(TAG, "getEpisodes: no result.episodes, body keys=${json.keys().asSequence().toList()}")
                return emptyList()
            }
            Log.d(TAG, "getEpisodes: episodes array length=${episodes.length()}")

            val list = mutableListOf<EpisodeInfo>()
            for (i in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(i) ?: continue
                val epId = ep.optInt("ep_id", 0)
                if (epId <= 0) continue

                val title = ep.optString("title", "")
                val longTitle = ep.optString("long_title", "")
                val epNum = ep.optString("ep_num", "")
                val link = ep.optString("link", "")

                val displayTitle = if (longTitle.isNotBlank()) {
                    "$epNum $longTitle"
                } else {
                    title
                }.ifBlank { "第${i + 1}集" }

                list.add(
                    EpisodeInfo(
                        episodeId = epId,
                        animeId = seasonId,
                        title = displayTitle,
                        episodeNumber = i + 1,
                        url = link
                    )
                )
            }
            Log.d(TAG, "getEpisodes: parsed ${list.size} episodes")
            list
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodes: EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }.also {
            Log.d(TAG, "========== getEpisodes END: ${it.size} episodes ==========")
        }
    }

    companion object {
        private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TAG = "BilibiliDanmakuFetcher"
    }
}
