package com.fluxplayer.app.core.data.yun139

import com.fluxplayer.app.core.data.BaseCloudApiClient
import com.fluxplayer.app.core.data.CloudHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class Yun139ApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT,
) : BaseCloudApiClient(client) {

    companion object {
        private const val TAG = "Yun139Api"
        private const val BASE_URL = "https://personal-kd-njs.yun.139.com"
        private const val USER_BASE = "https://user-njs.yun.139.com"
        private val DEFAULT_UA = "okhttp/4.12.0"
    }

    // region ==================== Auth helpers ====================

    private fun auth(): String = Yun139AuthProvider.authorization
    private fun uni(): String = Yun139AuthProvider.userDomainId
    private fun devInfo(): String = Yun139AuthProvider.deviceInfo

    fun setToken(authorization: String, phoneNumber: String, userDomainId: String) {
        Yun139AuthProvider.authorization = authorization
        Yun139AuthProvider.phoneNumber = phoneNumber
        Yun139AuthProvider.userDomainId = userDomainId
        Yun139AuthProvider.isActive = true
    }

    fun logout() {
        Yun139AuthProvider.authorization = ""
        Yun139AuthProvider.phoneNumber = ""
        Yun139AuthProvider.userDomainId = ""
        Yun139AuthProvider.isActive = false
    }

    fun isLoggedIn(): Boolean = Yun139AuthProvider.isActive

    internal suspend fun featureRequest(path: String, body: JSONObject): JSONObject {
        val json = apiPost(
            if (path.startsWith("https://")) path else "$BASE_URL/hcy/file/$path",
            body,
            com.fluxplayer.app.core.data.cloud.MobileCloudProtocol.cloudHeaders(body.toString()),
        )
        com.fluxplayer.app.core.data.cloud.MobileCloudProtocol.checkResponse(json)
        return json
    }

    // endregion

    // region ==================== Headers ====================

    private val filterHeaders: Map<String, String>
        get() = mapOf(
            "x-yun-api-version" to "v1",
            "x-yun-net-type" to "",
            "x-yun-device-id" to devInfo(),
            "x-yun-client-info" to devInfo(),
            "x-yun-svc-type" to "1",
            "x-yun-module-type" to "100",
            "x-yun-app-channel" to "10000034",
            "caller" to "web",
            "authorization" to auth(),
            "content-type" to "application/json; charset=UTF-8",
            "User-Agent" to DEFAULT_UA,
            "x-yun-uni" to uni(),
            "Referer" to "https://yun.139.com/",
        )

    private val downloadHeaders: Map<String, String>
        get() = mapOf(
            "x-yun-url-type" to "1",
            "x-yun-api-version" to "v1",
            "x-yun-client-info" to devInfo(),
            "x-yun-app-channel" to "10000023",
            "x-huawei-channelsrc" to "10000023",
            "authorization" to auth(),
            "content-type" to "application/json; charset=UTF-8",
            "User-Agent" to DEFAULT_UA,
            "x-yun-uni" to uni(),
        )

    private val videoPreviewHeaders: Map<String, String>
        get() = mapOf(
            "x-yun-url-type" to "3",
            "x-yun-module-type" to "100",
            "x-yun-api-version" to "v1",
            "x-yun-net-type" to "1",
            "x-yun-svc-type" to "1",
            "x-yun-app-channel" to "10000023",
            "x-yun-client-info" to devInfo(),
            "x-yun-device-id" to devInfo(),
            "x-yun-User-Agent" to "android|24031PN0DC|android 10|mCloud12.4.1-0000",
            "authorization" to auth(),
            "content-type" to "application/json; charset=UTF-8",
            "User-Agent" to DEFAULT_UA,
            "x-yun-uni" to uni(),
        )

    private val fileMgmtHeaders: Map<String, String>
        get() = mapOf(
            "x-yun-api-version" to "v1",
            "x-yun-net-type" to "1",
            "x-yun-svc-type" to "1",
            "x-yun-device-id" to devInfo(),
            "x-yun-client-info" to devInfo(),
            "x-yun-app-channel" to "10000023",
            "x-yun-module-type" to "100",
            "x-mm-source" to "0000",
            "authorization" to auth(),
            "content-type" to "application/json; charset=UTF-8",
            "User-Agent" to DEFAULT_UA,
            "x-yun-uni" to uni(),
        )

    // endregion

    // region ==================== HTTP ====================

    private suspend fun apiPost(
        url: String,
        body: JSONObject?,
        headers: Map<String, String>,
    ): JSONObject {
        val bodyStr = body?.toString() ?: ""
        val builder = Request.Builder().url(url)
            .post(bodyStr.toRequestBody(jsonMediaType))
        // hcy 云盘接口一律要带 mcloud-sign（wenxi 的 cloud() 对 file/list、
        // getDownloadUrl、task/get 都签）；登录/刷新接口保持原样。
        val requestHeaders = if (headers.containsKey("mcloud-sign") || !url.contains("/hcy/")) {
            headers
        } else {
            headers + ("mcloud-sign" to com.fluxplayer.app.core.data.cloud.MobileCloudProtocol.signature(bodyStr))
        }
        requestHeaders.forEach { (k, v) -> builder.header(k, v) }
        val req = builder.build()
        // 整个请求 + 读取 body 都在 IO 线程完成
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            executeRequestAndGetResponse(req).use { response ->
                check(response.isSuccessful) { "移动云盘请求失败（HTTP ${response.code}）" }
                response.body?.string() ?: error("移动云盘响应为空")
            }
        }
        return JSONObject(text).also { com.fluxplayer.app.core.data.cloud.MobileCloudProtocol.checkResponse(it) }
    }

    // endregion

    // region ==================== File list (v2 hcy API) ====================

    suspend fun listFiles(
        folderId: String = "/",
        pageCursor: String? = null,
        pageSize: Int = 100,
        orderBy: String = "updated_at",
        orderDirection: String = "DESC",
    ): Result<Yun139ListResult> = runCatching {
        val body = JSONObject().apply {
            put(
                "pageInfo",
                JSONObject().apply {
                    put("pageSize", pageSize)
                    put("pageCursor", pageCursor ?: JSONObject.NULL)
                },
            )
            put("orderBy", orderBy)
            put("orderDirection", orderDirection)
            put("parentFileId", folderId)
            put("imageThumbnailStyleList", JSONArray(listOf("Small", "Large")))
        }
        val json = apiPost("$BASE_URL/hcy/file/list", body, filterHeaders)

        // 检查 API 级错误
        val success = json.optBoolean("success", true)
        if (!success) {
            val msg = json.optString("message", "未知错误")
            throw IllegalStateException("API error: $msg")
        }

        // 响应格式: data.items + data.nextPageCursor
        val data = json.optJSONObject("data")
        val nextPageCursor = data?.optString("nextPageCursor", "") ?: ""
        val itemsArray = data?.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsArray.length()).map { i ->
            val item = itemsArray.getJSONObject(i)
            val type = item.optString("type", "")
            Yun139FileItem(
                fileId = item.optString("fileId", ""),
                fileName = item.optString("name", ""),
                fileSize = item.optLong("size", 0),
                isDir = type == "folder",
                createDate = item.optString("createdAt", "").replace("\\..*".toRegex(), ""),
                lastOpTime = item.optString("updatedAt", "").replace("\\..*".toRegex(), ""),
                contentType = item.optString("category", ""),
                thumbnailUrl = item.optJSONArray("thumbnailUrls")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                        .firstOrNull { it.optString("style") == "Large" }
                        ?.optString("url", "")
                        ?.ifBlank { null }
                },
            )
        }
        Yun139ListResult(items, 0, nextPageCursor)
    }

    // endregion

    // region ==================== Other operations ====================

    suspend fun getVideoPreviewUrl(fileId: String): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("category", "video")
            put("expireSec", 14400)
            put("fileId", fileId)
            put("qualityList", JSONObject.NULL)
        }
        val json = apiPost("$BASE_URL/hcy/videoPreview/getPreviewInfo", body, videoPreviewHeaders)
        json.optJSONObject("data")?.optJSONObject("previewInfo")?.optString("url", "")
            ?: json.optString("playUrl", "")
    }

    suspend fun getDownloadUrl(fileId: String, fileName: String): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("fileId", fileId)
            put("fileName", fileName)
        }
        val json = apiPost("$BASE_URL/hcy/file/getDownloadUrl", body, downloadHeaders)
        json.optJSONObject("data")?.optString("url", "")
            ?: json.optString("downloadUrl", "")
    }

    suspend fun createFolder(parentFolderId: String, folderName: String): Result<Boolean> = runCatching {
        val response = featureRequest("create", JSONObject().put("parentFileId", parentFolderId).put("name", folderName).put("description", "").put("type", "folder").put("fileRenameMode", "force_rename"))
        check(response.optJSONObject("data")?.optString("fileId").orEmpty().isNotBlank()) { "移动未确认文件夹创建，请刷新后确认" }
        true
    }

    suspend fun renameFile(fileId: String, newName: String): Result<Boolean> = runCatching {
        featureRequest("update", JSONObject().put("fileId", fileId).put("name", newName).put("description", ""))
        true
    }

    private suspend fun awaitFileTask(response: JSONObject) {
        val taskId = response.optJSONObject("data")?.optString("taskId").orEmpty()
        check(taskId.isNotBlank()) { "移动未返回操作任务编号，请刷新目录确认" }
        repeat(30) {
            val result = apiPost("$BASE_URL/hcy/task/get", JSONObject().put("taskId", taskId), com.fluxplayer.app.core.data.cloud.MobileCloudProtocol.cloudHeaders(JSONObject().put("taskId", taskId).toString()))
                .getJSONObject("data")
            val info = result.getJSONObject("taskInfo")
            val status = info.optString("status").lowercase(java.util.Locale.US)
            check(status !in setOf("failed", "error", "cancelled", "3", "4")) { "移动文件操作失败" }
            val entries = result.optJSONArray("batchFileResults")
            check(entries == null || (0 until entries.length()).all { index -> entries.getJSONObject(index).optString("errCode") in setOf("", "0", "0000") }) { "部分移动云盘文件操作失败，请刷新目录确认" }
            if (info.optInt("progress") >= 100 || status in setOf("success", "succeed", "completed", "done", "2")) return
            kotlinx.coroutines.delay(750)
        }
        error("移动文件操作仍在处理中，请稍后刷新确认")
    }

    suspend fun deleteFiles(fileIds: List<String>): Result<Boolean> = runCatching {
        val body = JSONObject().put("fileIds", JSONArray(fileIds))
        awaitFileTask(apiPost("$BASE_URL/hcy/recyclebin/batchTrash", body, com.fluxplayer.app.core.data.cloud.MobileCloudProtocol.cloudHeaders(body.toString())))
        true
    }

    suspend fun moveFiles(fileIds: List<String>, targetFolderId: String): Result<Boolean> = runCatching {
        awaitFileTask(featureRequest("batchMove", JSONObject().put("fileIds", JSONArray(fileIds)).put("toParentFileId", targetFolderId)))
        true
    }

    // endregion

    // region ==================== Token 刷新 ====================

    suspend fun refreshToken(): Result<Unit> = runCatching {
        val body = JSONObject().apply {
            put("clientType", "414")
        }
        val loginHeaders = mapOf(
            "x-nationcode" to "+86",
            "x-nettype" to "1",
            "x-deviceinfo" to devInfo(),
            "x-yun-client-info" to devInfo(),
            "x-yun-app-channel" to "10000023",
            "x-huawei-channelsrc" to "10000023",
            "x-mm-source" to "0000",
            "x-svctype" to "1",
            "content-type" to "application/json; charset=UTF-8",
            "User-Agent" to DEFAULT_UA,
            "authorization" to auth(),
            "x-yun-uni" to uni(),
        )
        val json = apiPost("$USER_BASE/user/auth/refreshToken", body, loginHeaders)
        val success = json.optBoolean("success", false) || json.optString("message", "") == "请求成功"
        if (success) {
            val token = json.optJSONObject("data")?.optString("token", "")
                ?: json.optString("token", "")
            if (token.isNotBlank()) {
                val phone = Yun139AuthProvider.phoneNumber
                val newAuth = "Basic " + android.util.Base64.encodeToString(
                    "mobile:$phone:$token".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
                )
                Yun139AuthProvider.authorization = newAuth
            }
        }
    }

    // endregion
}
