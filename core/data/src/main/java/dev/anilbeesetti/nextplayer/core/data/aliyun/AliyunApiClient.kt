package dev.anilbeesetti.nextplayer.core.data.aliyun

import dev.anilbeesetti.nextplayer.core.data.BaseCloudApiClient
import dev.anilbeesetti.nextplayer.core.data.CloudHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AliyunApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT
) : BaseCloudApiClient(client) {

    var authorization: String = ""
    var driveId: String = ""
    private var xDeviceId: String = "1bb8bbfa-c8b0-4de8-a5b3-194e48bc0638"
    private var xSignature: String = "ca8bfac0991f986648e8783319e6570c181a6442785179a90fa2515cce32375b52d0b38020c0aad8861c95f7f5a8369529d698b39322b6e74b199184913414a000"

    companion object {
        private const val TAG = "AliyunApi"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    fun setDeviceId(deviceId: String) { xDeviceId = deviceId }
    fun setSignature(signature: String) { xSignature = signature }
    fun getDeviceId(): String = xDeviceId
    fun getSignature(): String = xSignature

    private fun buildHeaders(): Map<String, String> = mapOf(
        "Authorization" to authorization,
        "Content-Type" to "application/json",
        "User-Agent" to UA,
        "Referer" to "https://www.alipan.com/",
        "X-Canary" to "client=web,app=adrive,version=v6.7.7",
        "X-Device-Id" to xDeviceId,
        "X-Signature" to xSignature
    )

    private suspend fun apiPost(url: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(url)
        if (body != null) {
            builder.post(body.toString().toRequestBody(jsonMediaType))
        } else {
            builder.post("".toRequestBody(null))
        }
        buildHeaders().forEach { (k, v) -> builder.header(k, v) }

        val resp = executeRequestAndGetResponse(builder.build())
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response from $url")
        if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code} from $url: $respBody")
        }
        AliyunAuthProvider.isActive = true
        return JSONObject(respBody)
    }

    private fun buildListBody(
        parentFileId: String,
        limit: Int = 200,
        orderBy: String = "name",
        orderDirection: String = "ASC",
        nextMarker: String? = null
    ): JSONObject = JSONObject().apply {
        put("drive_id", driveId)
        put("parent_file_id", parentFileId)
        put("limit", limit)
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
        nextMarker: String? = null
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
                updatedAt = item.optString("updated_at", ""),
                thumbnail = item.optString("thumbnail", ""),
                mimeType = item.optString("mime_type", ""),
                parentFileId = item.optString("parent_file_id", "")
            )
        }
        AliyunListResult(items, json.optString("next_marker", ""))
    }

    suspend fun getVideoPreviewPlayInfo(fileId: String): Result<AliyunPlayResult> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("category", "quick_video")
            put("mode", "high_res")
            put("template_id", "")
            put("url_expire_sec", 14400)
        }
        val json = apiPost("https://api.aliyundrive.com/v2/file/get_video_preview_play_info", body)
        val playInfo = json.optJSONObject("video_preview_play_info")
            ?: throw IllegalStateException("无视频播放信息")
        val quickList = playInfo.optJSONArray("quick_video_list")
            ?: throw IllegalStateException("无视频流列表")
        val urls = mutableListOf<String>()
        val names = mutableListOf<String>()
        for (i in 0 until quickList.length()) {
            val v = quickList.getJSONObject(i)
            val url = v.optString("url", "")
            if (url.isNotBlank()) {
                urls.add(url)
                names.add("${v.optInt("template_width", 0)}x${v.optInt("template_height", 0)}")
            }
        }
        if (urls.isEmpty()) throw IllegalStateException("无可播放的视频流")
        AliyunPlayResult(urls.reversed(), names.reversed())
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
        }
        val json = apiPost("https://api.aliyundrive.com/v2/file/get_download_url", body)
        json.getString("url")
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
        json.has("file_id")
    }

    suspend fun moveFile(fileId: String, toParentFileId: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("to_parent_file_id", toParentFileId.ifEmpty { "root" })
        }
        val json = apiPost("https://api.aliyundrive.com/v2/file/move", body)
        json.has("file_id") || json.has("async_task_id")
    }

    suspend fun copyFile(fileId: String, toParentFileId: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("to_parent_file_id", toParentFileId)
        }
        val json = apiPost("https://api.aliyundrive.com/v2/file/copy", body)
        json.has("file_id") || json.has("async_task_id")
    }

    suspend fun getUserDriveInfo(): Result<AliyunDriveInfo> = runCatching {
        val json = apiPost("https://api.aliyundrive.com/v2/user/get")
        AliyunDriveInfo(
            defaultDriveId = json.optString("default_drive_id", ""),
            backupDriveId = json.optString("backup_drive_id", ""),
            resourceDriveId = json.optString("resource_drive_id", "")
        )
    }

    suspend fun verifyToken(): Result<Boolean> = runCatching {
        val json = apiPost("https://api.aliyundrive.com/v2/user/get")
        json.has("default_drive_id")
    }
}
