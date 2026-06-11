package dev.anilbeesetti.nextplayer.core.data.quark

import android.util.Log
import dev.anilbeesetti.nextplayer.core.data.BaseCloudApiClient
import dev.anilbeesetti.nextplayer.core.data.CloudHttpClient
import dev.anilbeesetti.nextplayer.core.data.GlobalCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class QuarkApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT
) : BaseCloudApiClient(client) {

    companion object {
        private const val TAG = "QuarkApi"
        private const val QUARK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }

    private val cookieManager = QuarkCookieManager()
    private var driveType: String = "quark"
    var onCookieUpdated: ((String) -> Unit)? = null

    private val baseUrl: String get() = if (driveType == "uc") "https://pc-api.uc.cn" else "https://drive.quark.cn"
    private val homeUrl: String get() = if (driveType == "uc") "https://drive.uc.cn/" else "https://drive.quark.cn/"
    private val pr: String get() = if (driveType == "uc") "UCBrowser" else "ucpro"

    fun setDriveType(type: String) {
        driveType = type
        Log.d(TAG, "setDriveType: $type, baseUrl=$baseUrl, pr=$pr")
    }

    fun setCookie(cookie: String) {
        Log.d(TAG, "setCookie: cookie长度=${cookie.length}, 前100字符=${cookie.take(100)}")
        cookieManager.add(cookie)
        val merged = cookieManager.get()
        Log.d(TAG, "setCookie: 合并后cookie长度=${merged.length}, 包含__uid=${merged.contains("__uid=")}, 包含__pus=${merged.contains("__pus=")}")
        QuarkAuthProvider.cookie = merged
        QuarkAuthProvider.referer = homeUrl
        QuarkAuthProvider.isActive = cookie.isNotBlank()
        if (driveType == "uc") GlobalCookieJar.setUcCookie(cookie) else GlobalCookieJar.setQuarkCookie(cookie)
    }

    fun getCookie(): String = cookieManager.get()
    fun hasValidCookie(): Boolean {
        val c = getCookie()
        return c.contains("__uid=") && c.contains("__pus=")
    }

    private fun buildHeaders(): Map<String, String> {
        val headers = mapOf(
            "cookie" to getCookie(),
            "User-Agent" to QUARK_UA,
            "referer" to baseUrl
        )
        Log.d(TAG, "buildHeaders: cookie长度=${getCookie().length}")
        return headers
    }

    private fun updateCookieFromResponse(resp: okhttp3.Response) {
        val setCookie = resp.header("set-cookie") ?: return
        Log.d(TAG, "updateCookieFromResponse: set-cookie=${setCookie.take(100)}")
        cookieManager.add(setCookie)
        val merged = cookieManager.get()
        QuarkAuthProvider.cookie = merged
        onCookieUpdated?.invoke(merged)
    }

    private suspend fun apiGet(url: String): JSONObject {
        Log.d(TAG, "apiGet: $url")
        val builder = Request.Builder().url(url).get()
        buildHeaders().forEach { (k, v) -> builder.header(k, v) }
        val resp = executeRequestAndGetResponse(builder.build())
        Log.d(TAG, "apiGet: status=${resp.code}, url=$url")
        updateCookieFromResponse(resp)
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response from $url")
        if (!resp.isSuccessful) {
            Log.e(TAG, "apiGet FAILED: HTTP ${resp.code}, body=${respBody.take(500)}")
            throw IllegalStateException("HTTP ${resp.code} from $url: ${respBody.take(200)}")
        }
        Log.d(TAG, "apiGet SUCCESS: body前200字符=${respBody.take(200)}")
        return JSONObject(respBody)
    }

    private suspend fun apiPost(url: String, body: JSONObject? = null): JSONObject {
        Log.d(TAG, "apiPost: $url, body=${body?.toString()?.take(200)}")
        val builder = Request.Builder().url(url)
        if (body != null) {
            builder.post(body.toString().toRequestBody(jsonMediaType))
        } else {
            builder.post("".toRequestBody(null))
        }
        buildHeaders().forEach { (k, v) -> builder.header(k, v) }
        val resp = executeRequestAndGetResponse(builder.build())
        Log.d(TAG, "apiPost: status=${resp.code}")
        updateCookieFromResponse(resp)
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response from $url")
        if (!resp.isSuccessful) {
            Log.e(TAG, "apiPost FAILED: HTTP ${resp.code}, body=${respBody.take(500)}")
            throw IllegalStateException("HTTP ${resp.code} from $url: ${respBody.take(200)}")
        }
        Log.d(TAG, "apiPost SUCCESS: body前200字符=${respBody.take(200)}")
        return JSONObject(respBody)
    }

    suspend fun listFiles(
        pdirFid: String = "0",
        page: Int = 1,
        orderBy: String = "file_name:asc"
    ): Result<List<QuarkFileItem>> = runCatching {
        val url = buildString {
            append(baseUrl)
            append("/1/clouddrive/file/sort?pr=$pr&fr=pc")
            append("&pdir_fid=$pdirFid")
            append("&force=0")
            append("&_page=$page")
            append("&_size=100")
            append("&uc_param_str=")
            append("&_fetch_total=1")
            append("&_fetch_sub_dirs=0")
            append("&_sort=file_type:asc,$orderBy")
        }
        Log.d(TAG, "listFiles: pdirFid=$pdirFid, page=$page, driveType=$driveType, url=$url")
        val json = apiGet(url)
        val status = json.optInt("status", 0)
        val code = json.optInt("code", 0)
        Log.d(TAG, "listFiles: status=$status, code=$code")
        // 夸克 API: status=200 且 code=0 表示成功；code!=0 或 message 非空表示错误
        if (code != 0) {
            val errMsg = json.optString("message", "").ifEmpty { "code=$code" }
            throw IllegalStateException("API error: $errMsg")
        }
        val data = json.optJSONObject("data") ?: json
        val list = data.optJSONArray("list") ?: JSONArray()
        Log.d(TAG, "listFiles: 获取到${list.length()}个文件")
        (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            QuarkFileItem(
                fid = item.optString("fid", ""),
                fileName = item.optString("file_name", ""),
                dir = item.optBoolean("dir", false),
                objCategory = item.optString("obj_category", ""),
                size = item.optLong("size", 0),
                updatedAt = item.optLong("updated_at", 0),
                thumbnail = item.optString("thumbnail", ""),
                shareFidToken = item.optString("share_fid_token", ""),
                includeItems = item.optInt("include_items", 0)
            )
        }
    }

    suspend fun getVideoPlayInfo(fid: String): Result<QuarkPlayResult> = runCatching {
        Log.d(TAG, "getVideoPlayInfo: fid=$fid")
        val body = JSONObject().apply {
            put("fid", fid)
            put("resolutions", "normal,low,high,super,2k,4k")
            put("supports", "fmp4,m3u8")
        }
        val json = apiPost("$baseUrl/1/clouddrive/file/v2/play?pr=$pr&fr=pc", body)

        // status=200 表示错误
        if (json.optInt("status", -1) == 200) {
            throw IllegalStateException(json.optString("message", "获取视频信息失败"))
        }

        val data = json.optJSONObject("data") ?: throw IllegalStateException("无视频列表")
        // 夸克 API 返回 video_list，不是 play_list
        val videoList = data.optJSONArray("video_list") ?: data.optJSONArray("play_list") ?: throw IllegalStateException("无视频列表")
        Log.d(TAG, "getVideoPlayInfo: 原始${videoList.length()}个播放源")

        val urls = mutableListOf<String>()
        val names = mutableListOf<String>()
        for (i in 0 until videoList.length()) {
            val v = videoList.getJSONObject(i)
            val accessible = v.optBoolean("accessable", true)
            val videoInfo = v.optJSONObject("video_info")
            val url = videoInfo?.optString("url", "") ?: ""
            val resolution = v.optString("resolution", "")
            Log.d(TAG, "getVideoPlayInfo: [$i] resolution=$resolution, accessable=$accessible, url=${url.take(80)}")
            if (!accessible) continue
            if (url.isNotBlank()) {
                urls.add(url)
                names.add(resolution)
            }
        }
        Log.d(TAG, "getVideoPlayInfo: 有效${urls.size}个播放源: $names")
        if (urls.isEmpty()) throw IllegalStateException("无可用视频播放源")
        QuarkPlayResult(urls, names)
    }

    suspend fun getDownloadUrl(fid: String): Result<String> = runCatching {
        Log.d(TAG, "getDownloadUrl: fid=$fid")
        val body = JSONObject().apply {
            put("fids", JSONArray().apply { put(fid) })
        }
        val json = apiPost("$baseUrl/1/clouddrive/file/download?pr=$pr&fr=pc", body)
        val data = json.optJSONArray("data") ?: JSONArray()
        if (data.length() == 0) throw IllegalStateException("无下载链接")
        data.getJSONObject(0).optString("download_url", "")
    }

    suspend fun createFolder(name: String, parentFid: String): Result<Boolean> = runCatching {
        Log.d(TAG, "createFolder: name=$name, parentFid=$parentFid")
        val body = JSONObject().apply {
            put("pdir_fid", parentFid)
            put("file_name", name)
            put("dir", true)
        }
        val json = apiPost("$baseUrl/1/clouddrive/file?pr=$pr&fr=pc", body)
        json.optString("file_name") == name
    }

    suspend fun renameFile(fid: String, newName: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("fid", fid)
            put("file_name", newName)
        }
        val json = apiPost("$baseUrl/1/clouddrive/file/rename?pr=$pr&fr=pc", body)
        json.optString("file_name") == newName
    }

    suspend fun deleteFiles(fids: List<String>): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("action_type", 2)
            put("filelist", JSONArray(fids))
            put("exclude_fids", JSONArray())
        }
        val json = apiPost("$baseUrl/1/clouddrive/file/delete?pr=$pr&fr=pc", body)
        json.optInt("code", -1) == 0 || throw IllegalStateException(
            json.optString("message", "删除失败")
        )
    }

    suspend fun moveFiles(fids: List<String>, toPdirFid: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("action_type", 2)
            put("filelist", JSONArray(fids))
            put("to_pdir_fid", toPdirFid)
            put("exclude_fids", JSONArray())
        }
        val json = apiPost("$baseUrl/1/clouddrive/file/move?pr=$pr&fr=pc", body)
        json.optInt("code", -1) == 0 || throw IllegalStateException(
            json.optString("message", "移动失败")
        )
    }
}
