package com.fluxplayer.app.core.data.quark

import com.fluxplayer.app.core.data.BaseCloudApiClient
import com.fluxplayer.app.core.data.CloudHttpClient
import com.fluxplayer.app.core.data.GlobalCookieJar
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class QuarkApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT,
) : BaseCloudApiClient(client) {
    private var sessionContext: android.content.Context? = null

    fun configureSession(context: android.content.Context) {
        sessionContext = context.applicationContext
    }

    suspend fun resolvePlaybackUrl(fid: String, name: String): String {
        val video = Regex("\\.(mp4|mkv|avi|mov|webm|flv|m4v|ts|wmv|rmvb)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
        if (video) {
            sessionContext?.let { context ->
                if (driveType == "uc") {
                    val tv = UcTvService(context)
                    if (tv.isAuthorized()) {
                        val url = tv.playback(fid).first().first
                        android.net.Uri.parse(url).host?.let { com.fluxplayer.app.core.common.CloudPlayHeaders.register(it) { emptyMap() } }
                        return url
                    }
                } else if (context.getSharedPreferences("quark", android.content.Context.MODE_PRIVATE).getBoolean("prefer_original_playback", false)) {
                    getOriginalPlaybackUrl(fid, name).getOrNull()?.let { return it }
                }
            }
            getVideoPlayInfo(fid).getOrNull()?.urls?.firstOrNull()?.let { return it }
        }
        return getDownloadUrl(fid).getOrThrow()
    }

    companion object {
        private const val TAG = "QuarkApi"
        private const val QUARK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }

    private val cookieManager = QuarkCookieManager()
    private var driveType: String = "quark"
    var onCookieUpdated: ((String) -> Unit)? = null

    private val baseUrl: String get() = if (driveType == "uc") "https://pc-api.uc.cn" else "https://drive-pc.quark.cn"
    private val homeUrl: String get() = if (driveType == "uc") "https://drive.uc.cn/" else "https://pan.quark.cn/"
    private val pr: String get() = if (driveType == "uc") "UCBrowser" else "ucpro"

    fun setDriveType(type: String) {
        if (driveType != type) {
            cookieManager.clear()
            onCookieUpdated = null
        }
        driveType = type
    }

    fun setCookie(cookie: String) {
        cookieManager.add(cookie)
        val merged = cookieManager.get()
        QuarkAuthProvider.cookie = merged
        QuarkAuthProvider.referer = homeUrl
        QuarkAuthProvider.isActive = cookie.isNotBlank()
        if (driveType == "uc") GlobalCookieJar.setUcCookie(cookie) else GlobalCookieJar.setQuarkCookie(cookie)
    }

    fun getCookie(): String = cookieManager.get()

    val pageSize: Int get() = if (driveType == "uc") 50 else 100

    internal suspend fun featureRequest(path: String, body: JSONObject?, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): JSONObject {
        val url = ("$baseUrl/1/clouddrive/$path").toHttpUrl().newBuilder()
            .addQueryParameter("pr", pr).addQueryParameter("fr", "pc").apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
                // 夸克侧这些路径要带 uc_param_str（wenxi 的 url() 同款分流）
                if (driveType == "quark" && path.removeSuffix("/") in setOf("file/delete", "file/rename", "file/move", "share", "share/password")) {
                    addQueryParameter("uc_param_str", "")
                }
            }.build().toString()
        val requestHeaders = (if (driveType == "uc" && path.startsWith("transfer_share/")) mapOf("referer" to "https://fast.uc.cn/", "Origin" to "https://fast.uc.cn") else emptyMap()) + headers
        val json = if (body == null) apiGet(url, extraHeaders = requestHeaders) else apiPost(url, body, extraHeaders = requestHeaders)
        check(json.optInt("code", 0) == 0 && json.optInt("status", 200) == 200) {
            json.optString("message", "网盘请求失败")
        }
        return json
    }
    fun hasValidCookie(): Boolean {
        val c = getCookie()
        return c.contains("__uid=") && c.contains("__pus=")
    }

    /** 清除内存中的 Cookie 及 driveType，防止退出后残留旧账号数据 */
    fun clearCookie() {
        if (driveType == "uc") GlobalCookieJar.setUcCookie("") else GlobalCookieJar.setQuarkCookie("")
        cookieManager.clear()
        driveType = "quark"
        onCookieUpdated = null
    }

    internal fun featureHeaders(): Map<String, String> = buildHeaders()

    private fun buildHeaders(): Map<String, String> {
        return mapOf(
            "cookie" to getCookie(),
            "User-Agent" to if (driveType == "uc") "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/1.6.1 Chrome/100.0.4896.160 Electron/18.3.5.16-b62cf9c50d Safari/537.36" else QUARK_UA,
            "referer" to homeUrl,
            "Origin" to homeUrl.removeSuffix("/"),
        )
    }

    private fun updateCookieFromResponse(resp: okhttp3.Response) {
        val setCookies = resp.headers("set-cookie")
        if (setCookies.isEmpty()) return
        // 失败响应不写回会话（与 wenxi 一致），避免把过期/拒绝时下发的 cookie 存下来
        if (!resp.isSuccessful || resp.code == 401) return
        // 只收会话相关的 cookie，边缘节点下发的 __cf_bm/埋点 cookie 不能混进主会话
        val accepted = setCookies.mapNotNull { raw ->
            val pair = raw.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            pair.takeIf { name in setOf("__pus", "__puus", "__pugs") && it.isNotBlank() }
        }
        if (accepted.isEmpty()) return
        accepted.forEach { cookieManager.add(it) }
        val merged = cookieManager.get()
        QuarkAuthProvider.cookie = merged
        onCookieUpdated?.invoke(merged)
        if (driveType == "uc") GlobalCookieJar.setUcCookie(merged) else GlobalCookieJar.setQuarkCookie(merged)
    }

    private suspend fun apiGet(url: String, retry: Boolean = true, extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val builder = Request.Builder().url(url).get()
        (buildHeaders() + extraHeaders).forEach { (k, v) -> builder.header(k, v) }
        val resp = executeRequestAndGetResponse(builder.build())
        updateCookieFromResponse(resp)
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response from $url")
        if (resp.code == 401 && retry && refreshCookie()) return apiGet(url, false, extraHeaders)
        if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code} from $url: ${respBody.take(200)}")
        }
        val json = JSONObject(respBody)
        if (retry && (json.optInt("code") == 31001 || json.optInt("status") == 401) && refreshCookie()) return apiGet(url, false, extraHeaders)
        return json
    }

    private suspend fun apiPost(url: String, body: JSONObject? = null, retry: Boolean = true, extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val builder = Request.Builder().url(url)
        if (body != null) {
            builder.post(body.toString().toRequestBody(jsonMediaType))
        } else {
            builder.post("".toRequestBody(null))
        }
        (buildHeaders() + extraHeaders).forEach { (k, v) -> builder.header(k, v) }
        val resp = executeRequestAndGetResponse(builder.build())
        updateCookieFromResponse(resp)
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response from $url")
        if (resp.code == 401 && retry && refreshCookie()) return apiPost(url, body, false, extraHeaders)
        if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code} from $url: ${respBody.take(200)}")
        }
        val json = JSONObject(respBody)
        if (retry && (json.optInt("code") == 31001 || json.optInt("status") == 401) && refreshCookie()) return apiPost(url, body, false, extraHeaders)
        return json
    }

    private val cookieRefreshLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun refreshCookie(): Boolean = cookieRefreshLock.withLock {
        val expectedType = driveType
        val expected = getCookie()
        if (!expected.contains("__pus=")) return@withLock false
        val reduced = expected.split(';').filterNot { it.trim().startsWith("__puus=") }.joinToString(";")
        val request = Request.Builder().url("$baseUrl/1/clouddrive/config?pr=$pr&fr=pc")
        (buildHeaders() + ("cookie" to reduced)).forEach { (key, value) -> request.header(key, value) }
        val response = executeRequestAndGetResponse(request.build())
        response.use {
            check(driveType == expectedType && getCookie() == expected) { "网盘账号已变化，请重新操作" }
            if (!it.isSuccessful || it.headers("set-cookie").isEmpty()) return@withLock false
            updateCookieFromResponse(it)
            true
        }
    }

    suspend fun listFiles(
        pdirFid: String = "0",
        page: Int = 1,
        orderBy: String = "file_name:asc",
    ): Result<List<QuarkFileItem>> = runCatching {
        val url = buildString {
            append(baseUrl)
            append("/1/clouddrive/file/sort?pr=$pr&fr=pc")
            append("&pdir_fid=$pdirFid")

            append("&_page=$page")
            append("&_size=$pageSize")
            append("&_fetch_total=1")
            append("&_fetch_sub_dirs=0")
            if (driveType == "quark") {
                // 夸克侧需要这组参数才能看到全部文件（含风险文件名），UC 不需要
                append("&uc_param_str=")
                append("&fetch_all_file=1")
                append("&fetch_risk_file_name=1")
            }
            append("&__t=${System.currentTimeMillis()}")
            append("&__dt=1000")
            append("&_sort=file_type:asc,$orderBy")
        }
        val json = apiGet(url)
        val status = json.optInt("status", 0)
        val code = json.optInt("code", 0)
        // 夸克 API: status=200 且 code=0 表示成功；code!=0 或 message 非空表示错误
        if (code != 0) {
            val errMsg = json.optString("message", "").ifEmpty { "code=$code" }
            throw IllegalStateException("API error: $errMsg")
        }
        val data = json.optJSONObject("data") ?: json
        val list = data.optJSONArray("list") ?: JSONArray()
        (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            QuarkFileItem(
                fid = item.optString("fid", ""),
                fileName = item.optString("file_name", ""),
                dir = item.optBoolean("dir", false),
                objCategory = item.optString("obj_category", ""),
                size = item.optLong("size", 0),
                updatedAt = item.optLong("updated_at", 0),
                createdAt = item.optLong("created_at", 0),
                thumbnail = item.optString("thumbnail", ""),
                shareFidToken = item.optString("share_fid_token", ""),
                includeItems = item.optInt("include_items", 0),
            )
        }
    }

    suspend fun getVideoPlayInfo(fid: String): Result<QuarkPlayResult> = runCatching {
        val body = JSONObject().apply {
            put("fid", fid)
            put("resolutions", "normal,low,high,super,2k,4k")
            put("supports", "fmp4,m3u8")
        }
        val json = apiPost("$baseUrl/1/clouddrive/file/v2/play?pr=$pr&fr=pc", body)

        // status 不为 200 表示错误
        if (json.optInt("status", -1) != 200) {
            throw IllegalStateException(json.optString("message", "获取视频信息失败"))
        }

        val data = json.optJSONObject("data") ?: throw IllegalStateException("无视频列表")
        // 夸克 API 返回 video_list，不是 play_list
        val videoList = data.optJSONArray("video_list") ?: data.optJSONArray("play_list") ?: throw IllegalStateException("无视频列表")

        val urls = mutableListOf<String>()
        val names = mutableListOf<String>()
        for (i in 0 until videoList.length()) {
            val v = videoList.getJSONObject(i)
            val accessible = v.optBoolean("accessable", true)
            val videoInfo = v.optJSONObject("video_info")
            val url = videoInfo?.optString("url", "") ?: ""
            val resolution = v.optString("resolution", "")
            if (!accessible) continue
            if (url.isNotBlank()) {
                urls.add(url)
                names.add(resolution)
            }
        }
        if (urls.isEmpty()) throw IllegalStateException("无可用视频播放源")
        QuarkPlayResult(urls, names)
    }

    suspend fun getDownloadUrl(fid: String): Result<String> = runCatching {
        if (driveType == "uc") sessionContext?.let { context ->
            val tv = UcTvService(context)
            if (tv.isAuthorized()) return@runCatching tv.download(fid, 0)
        }
        val body = JSONObject().put("fids", JSONArray().put(fid))
        val json = apiPost("$baseUrl/1/clouddrive/file/download?pr=$pr&fr=pc&sys=win32&ve=${if (driveType == "uc") "1.6.1" else "3.23.2"}", body)
        check(json.optInt("code", 0) == 0 && json.optInt("status", 200) == 200) { json.optString("message", "下载接口错误") }
        val data = json.optJSONArray("data") ?: error("网盘未返回下载信息")
        val items = (0 until data.length()).map { data.getJSONObject(it) }
        val item = items.singleOrNull { it.optString("fid") == fid } ?: items.singleOrNull()?.takeIf { it.optString("fid").isBlank() } ?: error("下载文件与所选文件不一致")
        item.optString("download_url").ifBlank { item.optString("url") }.also { url -> check(url.startsWith("https://") || url.startsWith("http://")) { "网盘未返回有效下载链接" } }
    }

    /** 使用当前账号的发送到电脑会话获取原画播放地址。 */
    suspend fun getOriginalPlaybackUrl(fid: String, name: String = "视频"): Result<String> = runCatching {
        check(driveType == "quark") { "此播放方式仅适用于夸克" }
        val conversation = "3000008641494773"
        val base = "https://drive-social-api.quark.cn/1/clouddrive/chat/conv"
        val query = "?pr=ucpro&fr=pc&sys=win32&ve=3.19.0"
        val localId = java.util.UUID.randomUUID().toString()
        val body = JSONObject().put("return_msg_as_list", 1).put(
            "conversations",
            JSONArray().put(
                JSONObject().put("merge_file", 0).put("conversation_id", conversation).put("conversation_type", 3)
                    .put(
                        "file_list",
                        JSONArray().put(
                            JSONObject().put("fid", fid).put("content", name)
                                .put("client_extra", JSONObject().put("group_id", localId).put("device_model", "Administrator").put("local_msg_id", localId)),
                        ),
                    ),
            ),
        )
        val response = apiPost("$base/msg/batch_send$query", body)
        check(response.optInt("code", 0) == 0 && response.optInt("status", 200) == 200) { "夸克快传准备失败" }
        val data = response.optJSONObject("data") ?: throw IllegalStateException("夸克未返回快传信息")
        val raw = data.opt("send_msg_list") ?: data
        val messages = if (raw is JSONArray) (0 until raw.length()).map { raw.getJSONObject(it) } else listOf(raw as JSONObject)
        val message = selectPlaybackFile(messages, fid)
        val messageId = message.optString("store_msg_id").ifBlank { message.optString("msg_id").ifBlank { message.optString("id") } }
        check(messageId.isNotBlank() && messageId != "null") { "夸克未返回快传消息标识" }
        val targetConversation = message.optString("conversation_id").ifBlank { data.optString("conversation_id").ifBlank { conversation } }
        suspend fun token(target: String): String {
            val json = apiPost("$base/file/acquire_dl_token$query", JSONObject().put("conversation_id", target).put("conversation_type", 3).put("msg_id", messageId))
            check(json.optInt("code", 0) == 0 && json.optInt("status", 200) == 200) { "夸克快传授权失败" }
            return json.getJSONObject("data").getString("token").also { check(it.isNotBlank() && it != "null" && it.none(Char::isWhitespace)) { "夸克播放授权无效" } }
        }
        val authorization = try {
            token(targetConversation)
        } catch (error: IllegalStateException) {
            if (targetConversation == conversation) throw error
            token(conversation)
        }
        val download = apiPost(
            "https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2",
            JSONObject()
                .put("fids", JSONArray().put(fid)).put("speedup_session", "").put("token", authorization),
        )
        check(download.optInt("code", 0) == 0 && download.optInt("status", 200) == 200) { "夸克原画取址失败" }
        val list = download.optJSONArray("data") ?: throw IllegalStateException("夸克未返回播放地址")
        val item = selectPlaybackFile((0 until list.length()).map { list.getJSONObject(it) }, fid)
        item.optString("download_url").ifBlank { item.optString("url") }.also { url ->
            check(url.startsWith("https://") || url.startsWith("http://")) { "夸克返回的播放地址无效" }
        }
    }

    private fun selectPlaybackFile(items: List<JSONObject>, fid: String): JSONObject {
        fun identity(item: JSONObject): String = item.optString("fid").ifBlank {
            item.optJSONObject("file")?.optString("fid").orEmpty().ifBlank {
                item.optJSONObject("extra")?.optJSONObject("custom_extra")?.optJSONObject("file")?.optString("fid").orEmpty()
            }
        }
        return items.singleOrNull { identity(it) == fid }
            ?: items.singleOrNull()?.takeIf { identity(it).isBlank() }
            ?: throw IllegalStateException("夸克返回的播放文件与所选文件不一致")
    }

    suspend fun createFolder(name: String, parentFid: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("pdir_fid", parentFid)
            put("file_name", name)
            put("dir_path", "")
            put("dir_init_lock", false)
        }
        val json = apiPost("$baseUrl/1/clouddrive/file?pr=$pr&fr=pc", body)
        finishMutation(json)
    }

    suspend fun renameFile(fid: String, newName: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("fid", fid)
            put("file_name", newName)
        }
        val json = apiPost("$baseUrl/1/clouddrive/file/rename?pr=$pr&fr=pc$mutationQuery", body)
        finishMutation(json)
    }

    suspend fun deleteFiles(fids: List<String>): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("action_type", 2)
            put("filelist", JSONArray(fids))
            put("exclude_fids", JSONArray())
        }
        val json = apiPost("$baseUrl/1/clouddrive/file/delete?pr=$pr&fr=pc$mutationQuery", body)
        finishMutation(json)
    }

    suspend fun moveFiles(fids: List<String>, toPdirFid: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            // action_type=1 才是移动；2 是删除（wenxi 的 move 同为 1）
            put("action_type", 1)
            put("filelist", JSONArray(fids))
            put("to_pdir_fid", toPdirFid)
            put("exclude_fids", JSONArray())
        }
        val json = apiPost("$baseUrl/1/clouddrive/file/move?pr=$pr&fr=pc$mutationQuery", body)
        finishMutation(json)
    }

    /** 夸克的写操作要带 uc_param_str，UC 不需要（与 wenxi 的 url() 分流一致） */
    private val mutationQuery: String
        get() = if (driveType == "quark") "&uc_param_str=" else ""

    private suspend fun finishMutation(json: JSONObject): Boolean {
        check(json.optInt("code", 0) == 0 && json.optInt("status", 200) == 200) { json.optString("message", "网盘操作失败") }
        val taskId = json.optJSONObject("data")?.optString("task_id").orEmpty()
        if (taskId.isBlank()) return true
        repeat(40) { attempt ->
            val result = featureRequest("task", null, mapOf("task_id" to taskId, "retry_index" to attempt.toString()))
            val data = result.optJSONObject("data") ?: error("网盘任务响应不完整")
            val status = data.optInt("status", data.optInt("task_status"))
            check(status !in setOf(3, 4)) { "网盘文件操作失败" }
            if (status == 2 || data.optLong("finished_at") > 0) return true
            kotlinx.coroutines.delay(750)
        }
        error("网盘文件操作超时，请刷新目录确认")
    }
}
