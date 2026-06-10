package dev.anilbeesetti.nextplayer.core.data.yun139

import dev.anilbeesetti.nextplayer.core.data.BaseCloudApiClient
import dev.anilbeesetti.nextplayer.core.data.CloudHttpClient
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
        private const val API_BASE = "https://yun.139.com"
    }

    private fun buildHeaders(): Map<String, String> = mapOf(
        "Authorization" to Yun139AuthProvider.token,
        "Content-Type" to "application/json",
        "User-Agent" to Yun139AuthProvider.userAgent,
        "Referer" to "https://yun.139.com/"
    )

    private suspend fun apiGet(url: String): JSONObject {
        val builder = Request.Builder().url(url).get()
        buildHeaders().forEach { (k, v) -> builder.header(k, v) }
        val resp = executeRequestAndGetResponse(builder.build())
        val body = resp.body?.string() ?: throw IllegalStateException("Empty response from $url")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} from $url: $body")
        return JSONObject(body)
    }

    private suspend fun apiPost(url: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(url)
        if (body != null) builder.post(body.toString().toRequestBody(jsonMediaType))
        else builder.post("".toRequestBody(null))
        buildHeaders().forEach { (k, v) -> builder.header(k, v) }
        val resp = executeRequestAndGetResponse(builder.build())
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response from $url")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} from $url: $respBody")
        return JSONObject(respBody)
    }

    suspend fun listFiles(
        folderId: String = "root",
        pageNum: Int = 1,
        pageSize: Int = 100
    ): Result<Yun139ListResult> = runCatching {
        val body = JSONObject().apply {
            put("folderId", folderId)
            put("pageNum", pageNum)
            put("pageSize", pageSize)
        }
        val json = apiPost("$API_BASE/ori/file/listFiles.action", body)
        val itemsArray = json.optJSONArray("data") ?: JSONArray()
        val items = (0 until itemsArray.length()).map { i ->
            val item = itemsArray.getJSONObject(i)
            Yun139FileItem(
                fileId = item.optString("fileId", ""),
                fileName = item.optString("fileName", ""),
                fileSize = item.optLong("fileSize", 0),
                isDir = item.optBoolean("isDir", false),
                createDate = item.optString("createDate", ""),
                lastOpTime = item.optString("lastOpTime", ""),
                contentType = item.optString("contentType", ""),
                thumbnailUrl = item.optString("thumbnailUrl", null)
            )
        }
        Yun139ListResult(items, json.optInt("totalCount", 0), json.optString("nextMarker", ""))
    }

    suspend fun getVideoPreviewUrl(fileId: String): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("fileId", fileId)
        }
        val json = apiPost("$API_BASE/ori/file/getVideoPreview.action", body)
        json.optString("playUrl", "")
    }

    suspend fun getDownloadUrl(fileId: String, fileName: String): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("fileId", fileId)
            put("fileName", fileName)
        }
        val json = apiPost("$API_BASE/ori/file/getDownloadUrl.action", body)
        json.optString("downloadUrl", "")
    }

    suspend fun createFolder(parentFolderId: String, folderName: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("parentFolderId", parentFolderId)
            put("folderName", folderName)
        }
        apiPost("$API_BASE/ori/file/createFolder.action", body)
        true
    }

    suspend fun renameFile(fileId: String, newName: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("fileId", fileId)
            put("newName", newName)
        }
        apiPost("$API_BASE/ori/file/renameFile.action", body)
        true
    }

    suspend fun deleteFiles(fileIds: List<String>): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("fileIds", JSONArray(fileIds))
        }
        apiPost("$API_BASE/ori/file/deleteFiles.action", body)
        true
    }

    suspend fun moveFiles(fileIds: List<String>, targetFolderId: String): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("fileIds", JSONArray(fileIds))
            put("targetFolderId", targetFolderId)
        }
        apiPost("$API_BASE/ori/file/moveFiles.action", body)
        true
    }
}
