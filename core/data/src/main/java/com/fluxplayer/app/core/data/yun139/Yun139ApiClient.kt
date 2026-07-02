package com.fluxplayer.app.core.data.yun139

import android.util.Log
import com.fluxplayer.app.core.data.BaseCloudApiClient
import com.fluxplayer.app.core.data.CloudHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class Yun139ApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT
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
            "Referer" to "https://yun.139.com/"
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
            "x-yun-uni" to uni()
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
            "x-yun-uni" to uni()
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
            "x-yun-uni" to uni()
        )

    // endregion

    // region ==================== HTTP ====================

    private suspend fun apiPost(
        url: String,
        body: JSONObject?,
        headers: Map<String, String>
    ): JSONObject {
        val bodyStr = body?.toString() ?: ""
        val builder = Request.Builder().url(url)
            .post(bodyStr.toRequestBody(jsonMediaType))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val req = builder.build()
        // 整个请求 + 读取 body 都在 IO 线程完成
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val resp = executeRequestAndGetResponse(req)
            resp.body?.string() ?: ""
        }
        return JSONObject(text)
    }

    // endregion

    // region ==================== File list (v2 hcy API) ====================

    suspend fun listFiles(
        folderId: String = "/",
        pageCursor: String? = null,
        pageSize: Int = 100,
        orderBy: String = "updated_at",
        orderDirection: String = "DESC"
    ): Result<Yun139ListResult> = runCatching {
        val body = JSONObject().apply {
            put("pageInfo", JSONObject().apply {
                put("pageSize", pageSize)
                put("pageCursor", pageCursor ?: JSONObject.NULL)
            })
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
                }
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
        val body = JSONObject().apply {
            put("contentType", JSONObject.NULL)
            put("description", JSONObject.NULL)
            put("fileId", JSONObject.NULL)
            put("fileRenameMode", JSONObject.NULL)
            put("name", folderName)
            put("ownerId", JSONObject.NULL)
            put("parentFileId", parentFolderId)
            put("parentPath", JSONObject.NULL)
            put("type", "folder")
        }
        apiPost("$BASE_URL/hcy/file/create", body, fileMgmtHeaders)
        true
    }

    suspend fun renameFile(fileId: String, newName: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("FileRenameMode", JSONObject.NULL)
            put("description", JSONObject.NULL)
            put("fileId", fileId)
            put("name", newName)
        }
        apiPost("$BASE_URL/hcy/file/update", body, fileMgmtHeaders)
        true
    }

    suspend fun deleteFiles(fileIds: List<String>): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("fileIds", JSONArray(fileIds))
        }
        apiPost("$BASE_URL/hcy/recyclebin/batchTrash", body, fileMgmtHeaders)
        true
    }

    suspend fun moveFiles(fileIds: List<String>, targetFolderId: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("fileIds", JSONArray(fileIds))
            put("toParentFileId", targetFolderId)
        }
        apiPost("$BASE_URL/hcy/file/batchMove", body, fileMgmtHeaders)
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
            "x-yun-uni" to uni()
        )
        val json = apiPost("$USER_BASE/user/auth/refreshToken", body, loginHeaders)
        val success = json.optBoolean("success", false) || json.optString("message", "") == "请求成功"
        if (success) {
            val token = json.optJSONObject("data")?.optString("token", "")
                ?: json.optString("token", "")
            if (token.isNotBlank()) {
                val phone = Yun139AuthProvider.phoneNumber
                val newAuth = "Basic " + android.util.Base64.encodeToString(
                    "mobile:$phone:$token".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                )
                Yun139AuthProvider.authorization = newAuth
            }
        }
    }

    // endregion
}
