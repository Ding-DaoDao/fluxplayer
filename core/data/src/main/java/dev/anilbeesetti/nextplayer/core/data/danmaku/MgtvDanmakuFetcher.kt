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

class MgtvDanmakuFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .build()
) : PlatformDanmakuFetcher {

    override val name: String = "芒果弹幕"
    override val sourceId: String = "mgtv"

    override fun match(url: String): Boolean {
        return url.contains("mgtv.com")
    }

    override suspend fun fetchDanmaku(url: String): InputStream? {
        Log.d(TAG, "fetchDanmaku called for $url")
        return try {
            val segments = url.split("/")
            if (segments.size < 6) {
                Log.w(TAG, "fetchDanmaku: url segments < 6")
                return null
            }

            val vid = segments[5].removeSuffix(".html")
            val cid = segments[4]

            // Try to get cdn_version for newer barrage format
            val danmuFrom = try {
                val versionUrl = "https://galaxy.bz.mgtv.com/getctlbarrage?version=3.0.0&vid=$vid&abroad=0&pid=0&os=&uuid=&deviceid=2cc092cb-f9df-4f4f-a1ce-33c7fe3575cf&cid=393717&ticket=&mac=&platform=0&appVersion=3.0.0&reqtype=form-post&callback=jsonp_1658216873648_19074&allowedRC=1"
                val req = Request.Builder()
                    .url(versionUrl)
                    .header("User-Agent", PC_UA)
                    .header("Referer", url)
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string()
                if (body != null) {
                    val jsonMatch = Regex("""\{[\S\s]+\}""").find(body)
                    if (jsonMatch != null) {
                        JSONObject(jsonMatch.value).optJSONObject("data")?.optString("cdn_version", null)
                    } else null
                } else null
            } catch (e: Exception) {
                null
            }

            val xmlBuilder = StringBuilder()
            xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<i>\n")

            var hasData = false

            if (danmuFrom != null) {
                // New CDN-based barrage
                var fileNum = 0
                while (true) {
                    val hitvUrl = "https://bullet-ws.hitv.com/$danmuFrom/$fileNum.json"
                    try {
                        val req = Request.Builder()
                            .url(hitvUrl)
                            .header("User-Agent", PC_UA)
                            .header("Referer", url)
                            .get()
                            .build()
                        val resp = client.newCall(req).execute()
                        val body = resp.body?.string() ?: break
                        val data = JSONObject(body).optJSONObject("data")
                        if (data == null) {
                            fileNum++
                            continue
                        }
                        val items = data.optJSONArray("items")
                        if (items == null || items.length() == 0) {
                            fileNum++
                            continue
                        }
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val line = formatMgtvBarrage(item)
                            if (line != null) {
                                xmlBuilder.append(line)
                                hasData = true
                            }
                        }
                        fileNum++
                    } catch (e: Exception) {
                        break
                    }
                }
            } else {
                // Fallback: time-based barrage
                var errNum = 0
                var time = 0
                while (true) {
                    val barrageUrl = "https://galaxy.bz.mgtv.com/cdn/opbarrage?version=3.0.0&vid=$vid&abroad=0&pid=0&os=&uuid=&deviceid=2cc092cb-f9df-4f4f-a1ce-33c7fe3575cf&cid=$cid&ticket=&mac=&platform=0&time=$time&device=0&allowedRC=1&appVersion=3.0.0&reqtype=form-post&callback=jsonp_1658459178998_5150&allowedRC=1"
                    try {
                        val req = Request.Builder()
                            .url(barrageUrl)
                            .header("User-Agent", PC_UA)
                            .header("Referer", url)
                            .get()
                            .build()
                        val resp = client.newCall(req).execute()
                        val body = resp.body?.string() ?: break

                        val jsonMatch = Regex("""\{[\S\s]+\}""").find(body)
                        if (jsonMatch == null) break

                        val danmuData = JSONObject(jsonMatch.value).optJSONObject("data")
                        if (danmuData == null) {
                            errNum++
                            if (errNum > 2) break
                            time += 60000
                            continue
                        }

                        val items = danmuData.optJSONArray("items")
                        if (items == null || items.length() == 0) {
                            errNum++
                            if (errNum > 2) break
                            time = danmuData.optInt("next", time + 60000)
                            continue
                        }

                        errNum = 0
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val line = formatMgtvBarrage(item)
                            if (line != null) {
                                xmlBuilder.append(line)
                                hasData = true
                            }
                        }
                        time = danmuData.optInt("next", time + 60000)
                    } catch (e: Exception) {
                        break
                    }
                }
            }

            xmlBuilder.append("</i>")
            if (!hasData) {
                Log.w(TAG, "fetchDanmaku: no danmaku data for $url")
                return null
            }

            ByteArrayInputStream(xmlBuilder.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "fetchDanmaku failed for $url", e)
            null
        }
    }

    private fun formatMgtvBarrage(item: JSONObject): String? {
        val time = item.optLong("time", -1L)
        if (time < 0) return null

        val timepoint = time / 1000.0
        val ct = if (item.isNull("v2_position") || item.optInt("v2_position", 0) == 0) 1 else 5

        val content = item.optString("content", "")
        if (content.isBlank() || containsInvalidChars(content)) return null

        var color = 0xFFFFFF
        val v2Color = item.optJSONObject("v2_color")
        if (v2Color != null) {
            val left = v2Color.optJSONObject("color_left")
            if (left != null) {
                val r = left.optInt("r", 255)
                val g = left.optInt("g", 255)
                val b = left.optInt("b", 255)
                color = (r shl 16) or (g shl 8) or b
            }
        }

        return "  <d p=\"$timepoint,$ct,25,$color,0\">${escapeXml(content)}</d>\n"
    }

    private fun extractMgtvVid(url: String): String? {
        val segments = url.split("/")
        if (segments.size >= 6) {
            val candidate = segments[5].split(".")[0]
            val cleaned = candidate.split("?")[0]
            if (cleaned.all { it.isDigit() }) {
                if (cleaned.length in 5..20) return cleaned
            }
            return cleaned
        }

        val patterns = listOf(
            Regex("""/(\d+)\.html"""),
            Regex("""/v/(\d+)"""),
            Regex("""id=(\d+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
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

    override suspend fun search(keyword: String): List<AnimeMatch> {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://mobileso.bz.mgtv.com/msite/search/v2?q=$encoded&pc=30&pn=1&sort=0&ty=0&du=0&pt=0&corr=1&abroad=0&_support=10000000000000000&callback=jsonp_ltdyqd2pcfsnbnr"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PC_UA)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()

            val jsonMatch = Regex("""\{[\S\s]+\}""").find(body)
            if (jsonMatch == null) return emptyList()

            val json = JSONObject(jsonMatch.value)
            val rawData = json.opt("data")

            val contents: JSONArray? = when (rawData) {
                is JSONObject -> rawData.optJSONArray("contents")
                is JSONArray -> rawData.optJSONObject(0)?.optJSONArray("contents")
                else -> null
            }

            if (contents == null) {
                Log.w(TAG, "search: cannot find data.contents, response sample=${body.take(500)}")
                return emptyList()
            }

            val results = mutableListOf<AnimeMatch>()
            for (i in 0 until contents.length()) {
                val movie = contents.optJSONObject(i) ?: continue
                if (movie.optString("name", "") != "媒资头部") continue

                val dataArr = movie.optJSONArray("data") ?: continue
                if (dataArr.length() == 0) continue

                val firstData = dataArr.optJSONObject(0) ?: continue
                val title = Regex("<[^>]+>").replace(firstData.optString("title", ""), "")
                val epUrl = firstData.optString("url", "")

                if (epUrl.contains("qq") || epUrl.contains("youku") || epUrl.contains("qiyi") || epUrl.contains("bili")) continue
                if (title.isBlank() || epUrl.isBlank()) continue

                val fullUrl = "https://www.mgtv.com$epUrl"
                results.add(
                    AnimeMatch(
                        animeId = fullUrl.hashCode(),
                        title = title,
                        type = "芒果TV",
                        url = fullUrl
                    )
                )
            }

            Log.d(TAG, "search parsed ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e)
            emptyList()
        }
    }

    override suspend fun getEpisodes(anime: AnimeMatch): List<EpisodeInfo> {
        val url = anime.url ?: return emptyList()
        val vid = extractMgtvVid(url) ?: run {
            Log.w(TAG, "getEpisodes: cannot extract vid from $url")
            return emptyList()
        }
        Log.d(TAG, "getEpisodes: vid=$vid from $url")

        return try {
            val allEpisodes = mutableListOf<EpisodeInfo>()
            val seenUrls = mutableSetOf<String>()

            val firstUrl = "https://pcweb.api.mgtv.com/episode/list?_support=10000000&version=5.5.35&video_id=$vid&page=1&size=30&allowedRC=1&_support=10000000"
            val firstResp = client.newCall(
                Request.Builder()
                    .url(firstUrl)
                    .header("User-Agent", PC_UA)
                    .header("Referer", url)
                    .get()
                    .build()
            ).execute()

            if (!firstResp.isSuccessful) return emptyList()
            val firstBody = firstResp.body?.string() ?: return emptyList()

            val firstJson = JSONObject(firstBody)
            val episodeData = firstJson.optJSONObject("data") ?: return emptyList()
            val totalPage = episodeData.optInt("total_page", 0)
            val list = episodeData.optJSONArray("list") ?: JSONArray()

            addEpisodesFromList(list, url, allEpisodes, seenUrls)

            if (totalPage >= 2) {
                for (page in 2..totalPage) {
                    val pageUrl = "https://pcweb.api.mgtv.com/episode/list?_support=10000000&version=5.5.35&video_id=$vid&page=$page&size=30&allowedRC=1&_support=10000000"
                    try {
                        val resp = client.newCall(
                            Request.Builder()
                                .url(pageUrl)
                                .header("User-Agent", PC_UA)
                                .header("Referer", url)
                                .get()
                                .build()
                        ).execute()
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (body != null) {
                                val pageList = JSONObject(body).optJSONObject("data")?.optJSONArray("list")
                                if (pageList != null) {
                                    addEpisodesFromList(pageList, url, allEpisodes, seenUrls)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // continue to next page
                    }
                }
            }

            Log.d(TAG, "getEpisodes: total ${allEpisodes.size} episodes")
            allEpisodes
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodes failed for vid=$vid", e)
            emptyList()
        }
    }

    private fun addEpisodesFromList(
        list: JSONArray,
        baseUrl: String,
        result: MutableList<EpisodeInfo>,
        seenUrls: MutableSet<String>
    ) {
        for (i in 0 until list.length()) {
            val it = list.optJSONObject(i) ?: continue

            val corner = it.optJSONArray("corner")
            var status = ""
            if (corner != null && corner.length() > 0) {
                val firstCorner = corner.optJSONObject(0)
                if (firstCorner != null) {
                    status = firstCorner.optString("font", "")
                }
            }

            if (Regex("预|花絮").containsMatchIn(status)) continue

            val epRelUrl = it.optString("url", "")
            if (epRelUrl.isBlank() || seenUrls.contains(epRelUrl)) continue

            seenUrls.add(epRelUrl)

            val ts = it.optString("ts", "")
            val t1 = it.optString("t1", "")
            val t2 = it.optString("t2", "")
            val datePart = ts.split(" ").firstOrNull() ?: ""
            val title = buildString {
                if (datePart.isNotBlank()) {
                    append(datePart)
                    append("\t")
                }
                append(t1)
                append("_")
                append(t2)
            }

            val epUrl = "https://www.mgtv.com$epRelUrl"
            result.add(
                EpisodeInfo(
                    episodeId = epUrl.hashCode(),
                    animeId = baseUrl.hashCode(),
                    title = title,
                    episodeNumber = result.size + 1,
                    url = epUrl
                )
            )
        }
    }

    companion object {
        private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TAG = "MgtvDanmakuFetcher"
    }
}
