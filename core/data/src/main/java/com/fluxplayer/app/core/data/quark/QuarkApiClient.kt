package com.fluxplayer.app.core.data.quark

import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.data.BaseCloudApiClient
import com.fluxplayer.app.core.data.CloudHttpClient
import com.fluxplayer.app.core.data.GlobalCookieJar
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class QuarkApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT,
    /** 固定来源（"quark" / "uc"）。创建后不可变，避免同一实例被切来切去导致状态串号。 */
    val provider: String = "quark",
) : BaseCloudApiClient(client) {
    private val isUc: Boolean get() = provider == "uc"

    /** 本来源独立的播放鉴权状态，不再与另一家共用。 */
    private val auth: QuarkPlaybackAuth get() = quarkPlaybackAuth(provider)

    private var sessionContext: android.content.Context? = null

    fun configureSession(context: android.content.Context) {
        sessionContext = context.applicationContext
    }

    /**
     * 把本次播放的账号写进**本来源**的鉴权状态。
     * 特性层（搜索、分享转存）播放时也要走这一步，避免沿用上一次另一家的凭据。
     */
    private fun syncPlaybackAuth() {
        val headers = featureHeaders()
        CloudPlayHeaders.activeProvider = provider
        auth.isActive = true
        auth.cookie = getCookie()
        headers["User-Agent"]?.let { auth.userAgent = it }
        auth.referer = headers["referer"] ?: homeUrl
        CloudPlayHeaders.registerSuffixFor(provider, ".quark.cn") { featureHeaders() }
        if (isUc) CloudPlayHeaders.registerSuffixFor(provider, ".uc.cn") { featureHeaders() }
    }

    suspend fun resolvePlaybackUrl(fid: String, name: String): String {
        val video = Regex("\\.(mp4|mkv|avi|mov|webm|flv|m4v|ts|wmv|rmvb)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
        // 本次播放的来源与账号先同步到全局，避免另一家的凭据/注册被沿用
        syncPlaybackAuth()
        if (video) {
            sessionContext?.let { context ->
                if (isUc) {
                    val tv = UcTvService(context)
                    // TV 授权失效时不能让整条播放挂掉，回落到网页转码/下载
                    if (tv.isAuthorized()) {
                        runCatching { tv.playback(fid).first().first }.getOrNull()?.let { url ->
                            // 预签名地址标记为匿名，但只在 UC 播放时生效，不影响夸克同域名播放
                            android.net.Uri.parse(url).host?.let { host ->
                                com.fluxplayer.app.core.common.CloudPlayHeaders.registerAnonymous(host, "uc")
                            }
                            return url
                        }
                    }
                } else if (context.getSharedPreferences("quark", android.content.Context.MODE_PRIVATE).getBoolean("prefer_original_playback", false)) {
                    getOriginalPlaybackUrl(fid, name).getOrNull()?.let { return "$it#quarkPlay=true&quarkOriginal=true" }
                }
            }
            getVideoPlayInfo(fid).getOrNull()?.urls?.firstOrNull()?.let { return it }
        }
        return getDownloadUrl(fid).getOrThrow()
    }

    companion object {
        private const val TAG = "QuarkApi"
        private val originalPlaybackCache = QuarkOriginalPlaybackCache()
        private const val QUARK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }

    private val cookieManager = QuarkCookieManager()
    var onCookieUpdated: ((String) -> Unit)? = null

    private val baseUrl: String get() = if (isUc) "https://pc-api.uc.cn" else "https://drive-pc.quark.cn"
    private val homeUrl: String get() = if (isUc) "https://drive.uc.cn/" else "https://pan.quark.cn/"
    private val pr: String get() = if (isUc) "UCBrowser" else "ucpro"

    fun setCookie(cookie: String) {
        cookieManager.add(cookie)
        val merged = cookieManager.get()
        // 只写本来源的鉴权状态，另一家不受影响
        auth.cookie = merged
        auth.referer = homeUrl
        auth.isActive = cookie.isNotBlank()
        if (isUc) GlobalCookieJar.setUcCookie(cookie) else GlobalCookieJar.setQuarkCookie(cookie)
    }

    fun getCookie(): String = cookieManager.get()

    val pageSize: Int get() = if (isUc) 50 else 100

    internal suspend fun featureRequest(path: String, body: JSONObject?, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): JSONObject {
        val url = ("$baseUrl/1/clouddrive/$path").toHttpUrl().newBuilder()
            .addQueryParameter("pr", pr).addQueryParameter("fr", "pc").apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
                // 夸克侧这些路径要带 uc_param_str（wenxi 的 url() 同款分流）
                if (!isUc && path.removeSuffix("/") in setOf("file/delete", "file/rename", "file/move", "share", "share/password")) {
                    addQueryParameter("uc_param_str", "")
                }
            }.build().toString()
        val requestHeaders = (if (isUc && path.startsWith("transfer_share/")) mapOf("referer" to "https://fast.uc.cn/", "Origin" to "https://fast.uc.cn") else emptyMap()) + headers
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

    /** 清除内存中的 Cookie，防止退出后残留旧账号数据（来源本身不可变，无需重置） */
    fun clearCookie() {
        if (isUc) GlobalCookieJar.setUcCookie("") else GlobalCookieJar.setQuarkCookie("")
        cookieManager.clear()
        auth.clear()
        onCookieUpdated = null
    }

    /** 本账号（本来源）的请求头快照。跨模块（浏览页/下载）用它，避免误用另一家的全局值。 */
    fun featureHeaders(): Map<String, String> = buildHeaders()

    private fun buildHeaders(): Map<String, String> {
        return mapOf(
            "cookie" to getCookie(),
            "User-Agent" to if (isUc) UC_PLAY_UA else QUARK_UA,
            "referer" to homeUrl,
            "Origin" to homeUrl.removeSuffix("/"),
        )
    }

    private fun updateCookieFromResponse(resp: okhttp3.Response, loginFailed: Boolean = false) {
        val setCookies = resp.headers("set-cookie")
        if (setCookies.isEmpty()) return
        // 失败响应不写回会话（与 wenxi 一致），避免把过期/拒绝时下发的 cookie 存下来；
        // 这里同时看 HTTP 状态与业务层的登录失效，两者都不写。
        if (!resp.isSuccessful || resp.code == 401 || loginFailed) return
        // 只收会话相关的 cookie，边缘节点下发的 __cf_bm/埋点 cookie 不能混进主会话；
        // 空值（如 __puus=; Max-Age=0 的清理 cookie）必须跳过，否则会把会话清掉
        val accepted = setCookies.mapNotNull { raw ->
            val pair = raw.substringBefore(';')
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            pair.takeIf { name in setOf("__pus", "__puus", "__pugs") && value.isNotEmpty() }
        }
        if (accepted.isEmpty()) return
        accepted.forEach { cookieManager.add(it) }
        val merged = cookieManager.get()
        auth.cookie = merged
        onCookieUpdated?.invoke(merged)
        if (isUc) GlobalCookieJar.setUcCookie(merged) else GlobalCookieJar.setQuarkCookie(merged)
    }

    private suspend fun apiGet(url: String, retry: Boolean = true, extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val builder = Request.Builder().url(url).get()
        (buildHeaders() + extraHeaders).forEach { (k, v) -> builder.header(k, v) }
        val resp = executeRequestAndGetResponse(builder.build())
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response from $url")
        if (resp.code == 401 && retry && refreshCookie()) return apiGet(url, false, extraHeaders)
        if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code} from $url: ${respBody.take(200)}")
        }
        val json = JSONObject(respBody)
        val loginFailed = json.optInt("code") == 31001 || json.optInt("status") == 401
        updateCookieFromResponse(resp, loginFailed)
        if (retry && loginFailed && refreshCookie()) return apiGet(url, false, extraHeaders)
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
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response from $url")
        if (resp.code == 401 && retry && refreshCookie()) return apiPost(url, body, false, extraHeaders)
        if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code} from $url: ${respBody.take(200)}")
        }
        val json = JSONObject(respBody)
        val loginFailed = json.optInt("code") == 31001 || json.optInt("status") == 401
        updateCookieFromResponse(resp, loginFailed)
        if (retry && loginFailed && refreshCookie()) return apiPost(url, body, false, extraHeaders)
        return json
    }

    private val cookieRefreshLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun refreshCookie(): Boolean = cookieRefreshLock.withLock {
        val expected = getCookie()
        if (!expected.contains("__pus=")) return@withLock false
        val reduced = expected.split(';').filterNot { it.trim().startsWith("__puus=") }.joinToString(";")
        val request = Request.Builder().url("$baseUrl/1/clouddrive/config?pr=$pr&fr=pc")
        (buildHeaders() + ("cookie" to reduced)).forEach { (key, value) -> request.header(key, value) }
        val response = executeRequestAndGetResponse(request.build())
        response.use {
            check(getCookie() == expected) { "网盘账号已变化，请重新操作" }
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
            if (!isUc) {
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
        if (isUc) {
            sessionContext?.let { context ->
                val tv = UcTvService(context)
                if (tv.isAuthorized()) return@runCatching tv.download(fid, 0)
            }
        }
        val body = JSONObject().put("fids", JSONArray().put(fid))
        val json = apiPost("$baseUrl/1/clouddrive/file/download?pr=$pr&fr=pc&sys=win32&ve=${if (isUc) "1.6.1" else "3.23.2"}", body)
        check(json.optInt("code", 0) == 0 && json.optInt("status", 200) == 200) { json.optString("message", "下载接口错误") }
        val data = json.optJSONArray("data") ?: error("网盘未返回下载信息")
        val items = (0 until data.length()).map { data.getJSONObject(it) }
        val item = items.singleOrNull { it.optString("fid") == fid } ?: items.singleOrNull()?.takeIf { it.optString("fid").isBlank() } ?: error("下载文件与所选文件不一致")
        item.optString("download_url").ifBlank { item.optString("url") }.also { url -> check(url.startsWith("https://") || url.startsWith("http://")) { "网盘未返回有效下载链接" } }
    }

    /** 使用当前账号的发送到电脑会话获取原画播放地址。 */
    suspend fun getOriginalPlaybackUrl(fid: String, name: String = "视频"): Result<String> = runCatching {
        val owner = originalPlaybackOwner()
        withTimeoutOrNull(8_000) {
            if (owner.isEmpty()) {
                fetchOriginalPlaybackUrl(fid, name).getOrThrow()
            } else {
                originalPlaybackCache.resolve(owner, fid) {
                    fetchOriginalPlaybackUrl(fid, name).getOrThrow().also { check(originalPlaybackOwner() == owner) { "夸克账号已变化，请重新播放" } }
                }
            }
        } ?: error("原画取址耗时过长，尝试其他播放线路")
    }.onFailure { if (it is CancellationException) throw it }

    private fun originalPlaybackOwner(): String {
        val cookie = getCookie()
        if (cookie.isBlank()) return ""
        val identity = cookie.split(';').map { it.trim() }.filter { it.startsWith("__uid=") || it.startsWith("__pus=") }.sorted().joinToString(";").ifBlank { cookie }
        return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private class OriginalResponseError(message: String) : IllegalStateException(message)

    /** 快传三步共用可取消的时间预算，网络失败不重复发送快传消息。 */
    private suspend fun originalPost(url: String, body: JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(jsonMediaType))
        buildHeaders().forEach { (key, value) -> request.header(key, value) }
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        val started = System.nanoTime()
        val owner = originalPlaybackOwner()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                try {
                    val json = response.use {
                        val value = JSONObject(it.body?.string() ?: error("夸克快传响应为空"))
                        if (it.code == 401 || value.optInt("code") == 31001 || value.optInt("status") == 401) throw IllegalArgumentException("夸克登录已失效，请重新登录")
                        if (!it.isSuccessful || value.optInt("code", -1) != 0 || value.optInt("status", 200) != 200) throw OriginalResponseError("夸克快传请求失败，请稍后重试")
                        check(originalPlaybackOwner() == owner) { "夸克账号已变化，请重新播放" }
                        updateCookieFromResponse(it)
                        value
                    }
                    runCatching { android.util.Log.d(TAG, "原画取址 ${request.build().url.encodedPath.substringAfterLast('/')} 耗时 ${(System.nanoTime() - started) / 1_000_000} 毫秒") }
                    if (continuation.isActive) continuation.resume(json)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private suspend fun fetchOriginalPlaybackUrl(fid: String, name: String): Result<String> = runCatching {
        check(!isUc) { "此播放方式仅适用于夸克" }
        val conversation = "3000008641494773"
        val base = "https://drive-social-api.quark.cn/1/clouddrive/chat/conv"
        val query = "?pr=ucpro&fr=pc&sys=win32&ve=3.19.0"
        val groupId = java.util.UUID.randomUUID().toString()
        val localMsgId = java.util.UUID.randomUUID().toString()
        val body = JSONObject().put("return_msg_as_list", 1).put(
            "conversations",
            JSONArray().put(
                JSONObject().put("merge_file", 0).put("conversation_id", conversation).put("conversation_type", 3)
                    .put(
                        "file_list",
                        JSONArray().put(
                            JSONObject().put("fid", fid).put("content", name)
                                .put("client_extra", JSONObject().put("group_id", groupId).put("device_model", "Administrator").put("local_msg_id", localMsgId)),
                        ),
                    ),
            ),
        )
        val response = originalPost("$base/msg/batch_send$query", body)
        check(response.optInt("code", 0) == 0 && response.optInt("status", 200) == 200) { "夸克快传准备失败" }
        val data = response.optJSONObject("data") ?: throw IllegalStateException("夸克未返回快传信息")
        val raw = data.opt("send_msg_list") ?: data
        val messages = if (raw is JSONArray) (0 until raw.length()).map { raw.getJSONObject(it) } else listOf(raw as JSONObject)
        val message = selectPlaybackFile(messages, fid)
        val messageId = message.optString("store_msg_id").ifBlank { message.optString("msg_id").ifBlank { message.optString("id") } }
        check(messageId.isNotBlank() && messageId != "null") { "夸克未返回快传消息标识" }
        val targetConversation = message.optString("conversation_id").ifBlank { data.optString("conversation_id").ifBlank { conversation } }
        suspend fun token(target: String): String {
            val json = originalPost("$base/file/acquire_dl_token$query", JSONObject().put("conversation_id", target).put("conversation_type", 3).put("msg_id", messageId))
            check(json.optInt("code", 0) == 0 && json.optInt("status", 200) == 200) { "夸克快传授权失败" }
            return json.optJSONObject("data")?.optString("token").orEmpty().also {
                if (it.isBlank() || it.length > 32768 || it == "null" || it.any(Char::isWhitespace)) throw OriginalResponseError("夸克播放授权无效")
            }
        }
        val authorization = try {
            token(targetConversation)
        } catch (error: OriginalResponseError) {
            if (targetConversation == conversation) throw error
            token(conversation)
        }
        val download = originalPost(
            "https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2",
            JSONObject()
                .put("fids", JSONArray().put(fid)).put("speedup_session", "").put("token", authorization),
        )
        check(download.optInt("code", 0) == 0 && download.optInt("status", 200) == 200) { "夸克原画取址失败" }
        // data 可能是数组，也可能是包了一层的对象（wenxi 的 _downloadItem 两种都收）
        val items = when (val data = download.opt("data")) {
            is JSONArray -> (0 until data.length()).map { data.getJSONObject(it) }
            is JSONObject -> data.optJSONArray("data")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } } ?: listOf(data)
            else -> throw IllegalStateException("夸克未返回播放地址")
        }
        val item = selectPlaybackFile(items, fid)
        val url = item.optString("download_url").ifBlank { item.optString("url") }
        validatePlaybackUrl(url)
        url
    }

    /** 与 wenxi 一致：必须是 http/https、有 host、不带 userInfo、不含控制字符。 */
    private fun validatePlaybackUrl(url: String) {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        check(
            uri != null && (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null &&
                url.none { it.code <= 0x20 || it.code == 0x7f },
        ) { "夸克快传未返回有效播放地址" }
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
        get() = if (!isUc) "&uc_param_str=" else ""

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
