package com.fluxplayer.app.core.data.aliyun

import android.content.Context
import android.content.SharedPreferences
import com.fluxplayer.app.core.data.BaseCloudApiClient
import com.fluxplayer.app.core.data.CloudHttpClient
import com.fluxplayer.app.core.data.security.getSecret
import com.fluxplayer.app.core.data.security.putSecret
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AliyunTokenExpiredException(message: String) : IllegalStateException(message)

class AliyunApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT,
) : BaseCloudApiClient(client) {

    var authorization: String = ""
    var driveId: String = ""
    private var xDeviceId: String = "1bb8bbfa-c8b0-4de8-a5b3-194e48bc0638"
    private var xSignature: String = "ca8bfac0991f986648e8783319e6570c181a6442785179a90fa2515cce32375b52d0b38020c0aad8861c95f7f5a8369529d698b39322b6e74b199184913414a000"
    private var sessionPreferences: SharedPreferences? = null

    companion object {
        private val sessionMutex = Mutex()
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    fun setDeviceId(deviceId: String) {
        xDeviceId = deviceId
    }
    fun setSignature(signature: String) {
        xSignature = signature
    }
    fun getDeviceId(): String = xDeviceId
    fun getSignature(): String = xSignature

    fun configureSession(context: Context) {
        sessionPreferences = context.applicationContext.getSharedPreferences("alipan", Context.MODE_PRIVATE)
    }

    internal suspend fun featureRequest(path: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject {
        return apiPost("https://api.aliyundrive.com$path", body, headers)
    }

    private fun buildHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Authorization" to authorization,
        "Content-Type" to "application/json",
        "Priority" to "u=1, i",
        "Referer" to "https://www.alipan.com/",
        "Sec-CH-UA" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "Sec-CH-UA-Mobile" to "?0",
        "Sec-CH-UA-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "User-Agent" to UA,
        "X-Canary" to "client=web,app=adrive,version=v6.7.7",
        "X-Device-Id" to xDeviceId,
        "X-Signature" to xSignature,
    )

    private suspend fun apiPost(url: String, body: JSONObject? = null, extraHeaders: Map<String, String> = emptyMap(), retry: Boolean = true): JSONObject {
        val expected = authorization
        val builder = Request.Builder().url(url)
        builder.post((body ?: JSONObject()).toString().toRequestBody(jsonMediaType))
        (buildHeaders() + extraHeaders).forEach { (k, v) -> builder.header(k, v) }

        val resp = executeRequestAndGetResponse(builder.build())
        val httpCode = resp.code
        val respBody = resp.use { it.body?.string() } ?: throw IllegalStateException("阿里云盘响应为空")
        val json = try {
            JSONObject(respBody)
        } catch (error: Exception) {
            throw IllegalStateException("阿里云盘返回了无效响应（HTTP $httpCode）", error)
        }

        // 优先检查 token 是否过期（HTTP 401 时 respBody 也包含 AccessTokenInvalid，
        // 必须在 isSuccessful 判断之前捕获，否则会被通用 IllegalStateException 吞掉）
        val code = try {
            JSONObject(respBody).optString("code", "")
        } catch (_: Exception) {
            ""
        }
        if (httpCode == 401 || code in setOf("AccessTokenInvalid", "AccessTokenExpired", "InvalidAccessToken")) {
            if (retry && refreshSession(expected)) return apiPost(url, body, extraHeaders, false)
            throw AliyunTokenExpiredException("阿里云盘登录已过期，请重新登录")
        }

        if (retry && code in setOf("DeviceSessionSignatureInvalid", "UserDeviceOffline", "DeviceSessionNotFound", "SignatureInvalid", "InvalidDeviceSession")) {
            renewDeviceSession(expected)
            return apiPost(url, body, extraHeaders, false)
        }
        check(httpCode in 200..299 && (code.isBlank() || code == "0" || code == "200")) { json.optString("message", "阿里云盘请求失败：$code") }

        if (authorization.isNotBlank()) AliyunAuthProvider.isActive = true
        return json
    }

    private suspend fun refreshSession(expected: String): Boolean = sessionMutex.withLock {
        val prefs = sessionPreferences ?: return@withLock false
        val stored = prefs.getSecret("alipan", "authorization").orEmpty()
        if (stored != expected) {
            check(stored.isNotBlank()) { "阿里账号已退出，请重新登录" }
            // 仅同一登录会话的并发刷新可以共享新令牌。
            check(prefs.getSecret("alipan", "previous_authorization") == expected) { "阿里账号已变化，请重新操作" }
            authorization = stored
            return@withLock true
        }
        val refresh = prefs.getSecret("alipan", "refresh_token").orEmpty()
        if (refresh.isBlank()) return@withLock false
        val request = Request.Builder().url("https://auth.aliyundrive.com/v2/account/token")
            .post(JSONObject().put("refresh_token", refresh).put("grant_type", "refresh_token").toString().toRequestBody(jsonMediaType)).build()
        val result = JSONObject(executeRequest(request))
        val token = result.optString("access_token")
        check(token.isNotBlank()) { "阿里登录凭据已失效，请重新登录" }
        check(prefs.getSecret("alipan", "authorization") == expected) { "阿里账号已变化，请重新操作" }
        val renewed = "${result.optString("token_type", "Bearer")} $token"
        prefs.edit().putSecret("alipan", "previous_authorization", expected).putSecret("alipan", "authorization", renewed)
            .putSecret("alipan", "refresh_token", result.optString("refresh_token").ifBlank { refresh }).apply()
        authorization = renewed
        AliyunAuthProvider.authorization = renewed
        true
    }

    private suspend fun renewDeviceSession(expected: String) = sessionMutex.withLock {
        val prefs = sessionPreferences ?: error("阿里设备会话已失效，请重新登录")
        check(authorization == expected && prefs.getSecret("alipan", "authorization") == expected) { "阿里账号已变化，请重试" }
        val identityRequest = Request.Builder().url("https://user.aliyundrive.com/v2/user/get")
            .post("{}".toRequestBody(jsonMediaType)).header("Authorization", expected).build()
        val identity = JSONObject(executeRequest(identityRequest))
        val userId = identity.optString("user_id").also { check(it.isNotBlank()) { "阿里未返回账号标识" } }
        val key = prefs.getSecret("alipan", "device_private_key").orEmpty().ifBlank { AliyunDeviceSignature.privateKey() }
        val device = prefs.getSecret("alipan", "device_id").orEmpty().ifBlank { java.util.UUID.randomUUID().toString() }
        val signed = AliyunDeviceSignature.sign(key, device, userId)
        val request = Request.Builder().url("https://api.aliyundrive.com/users/v1/users/device/create_session")
            .post(JSONObject().put("deviceName", "FluxPlayer").put("modelName", "Android").put("pubKey", signed.second).toString().toRequestBody(jsonMediaType))
        (buildHeaders() + mapOf("X-Device-Id" to device, "X-Signature" to signed.first)).forEach { (name, value) -> request.header(name, value) }
        val response = JSONObject(executeRequest(request.build()))
        check(response.optBoolean("result")) { "阿里设备会话创建失败，请重新登录" }
        check(prefs.getSecret("alipan", "authorization") == expected) { "阿里账号已变化，请重新操作" }
        xDeviceId = device
        xSignature = signed.first
        prefs.edit().putSecret("alipan", "device_private_key", key).putSecret("alipan", "device_id", device).putSecret("alipan", "signature", signed.first).apply()
    }

    private fun buildListBody(
        parentFileId: String,
        orderBy: String = "name",
        orderDirection: String = "ASC",
        nextMarker: String? = null,
    ): JSONObject = JSONObject().apply {
        put("drive_id", driveId)
        put("parent_file_id", parentFileId)
        put("limit", 100)
        put("all", false)
        put("url_expire_sec", 14400)
        put("image_thumbnail_process", "image/resize,w_256/format,avif")
        put("image_url_process", "image/resize,w_1920/format,avif")
        put("video_thumbnail_process", "video/snapshot,t_120000,f_jpg,m_lfit,w_256,ar_auto,m_fast")
        put("fields", "*")
        put("order_by", orderBy)
        put("order_direction", orderDirection)
        if (nextMarker != null) put("marker", nextMarker)
    }

    suspend fun listFiles(
        parentFileId: String,
        orderBy: String = "name",
        orderDirection: String = "ASC",
        nextMarker: String? = null,
    ): Result<AliyunListResult> = runCatching {
        val body = buildListBody(parentFileId, orderBy = orderBy, orderDirection = orderDirection, nextMarker = nextMarker)
        val json = apiPost("https://api.aliyundrive.com/adrive/v3/file/list", body)
        val itemsArray = json.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsArray.length()).map { i ->
            val item = itemsArray.getJSONObject(i)
            AliyunFileItem(
                fileId = item.getString("file_id"),
                fileName = item.getString("name"),
                type = item.getString("type"),
                category = item.optString("category", ""),
                size = item.optLong("size", 0),
                createdAt = item.optString("created_at", ""),
                updatedAt = item.optString("updated_at", ""),
                thumbnail = item.optString("thumbnail", ""),
                mimeType = item.optString("mime_type", ""),
                parentFileId = item.optString("parent_file_id", ""),
            )
        }
        AliyunListResult(items, json.optString("next_marker", ""))
    }

    suspend fun getVideoPreviewPlayInfo(fileId: String): Result<AliyunPlayResult> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("category", "live_transcoding")
            put("mode", "high_res")
            put("template_id", "")
            put("url_expire_sec", 14400)
        }
        val json = apiPost("https://api.aliyundrive.com/v2/file/get_video_preview_play_info", body)
        val playInfo = json.optJSONObject("video_preview_play_info")
            ?: throw IllegalStateException("无视频播放信息")
        val quickList = playInfo.optJSONArray("live_transcoding_task_list") ?: playInfo.optJSONArray("quick_video_list")
            ?: throw IllegalStateException("无视频流列表")
        val urls = mutableListOf<String>()
        val names = mutableListOf<String>()
        for (i in 0 until quickList.length()) {
            val v = quickList.getJSONObject(i)
            if (v.has("status") && !v.optString("status").equals("finished", ignoreCase = true)) continue
            val url = v.optString("url", "")
            if (url.isNotBlank()) {
                urls.add(url)
                names.add(v.optString("template_id").ifBlank { "${v.optInt("template_width", 0)}x${v.optInt("template_height", 0)}" })
            }
        }
        if (urls.isEmpty()) throw IllegalStateException("无可播放的视频流")
        val ranks = listOf("LD", "SD", "HD", "FHD", "QHD", "4K")
        val sorted = urls.zip(names).sortedByDescending { ranks.indexOf(it.second.uppercase()) }
        AliyunPlayResult(sorted.map { it.first }, sorted.map { it.second })
    }

    suspend fun getAudioPlayInfo(fileId: String): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
        }
        val json = apiPost("https://api.aliyundrive.com/adrive/v2/databox/get_audio_play_info", body)
        val templates = json.optJSONArray("template_list")
        if (templates == null || templates.length() <= 0) throw IllegalStateException("无音频播放地址")
        templates.getJSONObject(0).getString("url") + "#isMusic=true#"
    }

    suspend fun getDownloadUrl(fileId: String): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            // 默认约 15 分钟过期，排队下载/续播容易 403，与 wenxi 一样要 4 小时
            put("expire_sec", 14400)
        }
        val json = apiPost("https://api.aliyundrive.com/v2/file/get_download_url", body)
        json.optString("download_url").ifBlank { json.optString("cdn_url") }.ifBlank { json.optString("url") }
            .also { check(it.isNotBlank()) { "阿里未返回下载地址" } }
    }

    suspend fun createFolder(name: String, parentFileId: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("parent_file_id", parentFileId)
            put("name", name)
            put("type", "folder")
            put("check_name_mode", "refuse")
        }
        val json = apiPost("https://api.aliyundrive.com/adrive/v2/file/createWithFolders", body)
        json.optString("name") == name
    }

    suspend fun renameFile(fileId: String, newName: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("name", newName)
            put("check_name_mode", "refuse")
        }
        val json = apiPost("https://api.aliyundrive.com/v3/file/update", body)
        json.optString("name") == newName
    }

    suspend fun trashFile(fileId: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
        }
        val json = apiPost("https://api.aliyundrive.com/v2/recyclebin/trash", body)
        awaitMutation(json)
        true
    }

    suspend fun moveFile(fileId: String, toParentFileId: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("to_drive_id", driveId)
            put("to_parent_file_id", toParentFileId.ifEmpty { "root" })
        }
        val json = apiPost("https://api.aliyundrive.com/v2/file/move", body)
        awaitMutation(json)
        true
    }

    suspend fun copyFile(fileId: String, toParentFileId: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("to_drive_id", driveId)
            put("to_parent_file_id", toParentFileId)
        }
        val json = apiPost("https://api.aliyundrive.com/v2/file/copy", body)
        awaitMutation(json)
        true
    }

    private suspend fun awaitMutation(response: JSONObject) {
        val id = response.optString("async_task_id")
        if (id.isBlank()) { check(response.has("file_id")) { "阿里未确认文件操作" }; return }
        repeat(80) {
            val result = apiPost("https://api.aliyundrive.com/v2/async_task/get", JSONObject().put("async_task_id", id))
            val state = result.optString("state").lowercase(java.util.Locale.US)
            if (state in setOf("succeed", "success")) return
            check(state in setOf("running", "pending", "waiting")) { "阿里文件操作失败，请刷新目录确认" }
            kotlinx.coroutines.delay(750)
        }
        error("阿里文件操作仍在处理中，请稍后刷新确认")
    }

    suspend fun getUserDriveInfo(): Result<AliyunDriveInfo> = runCatching {
        val json = apiPost("https://user.aliyundrive.com/v2/user/get")
        AliyunDriveInfo(
            defaultDriveId = json.optString("default_drive_id", ""),
            backupDriveId = json.optString("backup_drive_id", ""),
            resourceDriveId = json.optString("resource_drive_id", ""),
        )
    }

    suspend fun verifyToken(): Result<Boolean> = runCatching {
        val json = apiPost("https://user.aliyundrive.com/v2/user/get")
        json.has("default_drive_id")
    }
}
