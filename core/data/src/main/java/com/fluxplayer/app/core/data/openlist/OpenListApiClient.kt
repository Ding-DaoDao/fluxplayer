package com.fluxplayer.app.core.data.openlist

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * OpenList JSON API 客户端。
 *
 * 所有请求发往 http://127.0.0.1:5244。
 */
class OpenListApiClient(
    private val baseUrl: String = "http://127.0.0.1:5244",
    private val client: OkHttpClient = DEFAULT_CLIENT,
) {

    companion object {
        private const val TAG = "OpenListApiClient"

        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
    }

    private var adminToken: String? = null

    /** 获取当前 admin token（给 Player auth 注入 Bearer 头用） */
    fun getAdminToken(): String? = adminToken

    // ====================================================================
    // Auth
    // ====================================================================

    /**
     * 管理员登录，获取 token。
     */
    suspend fun adminLogin(password: String): Result<String> = runCatching {
        Log.d(TAG, "adminLogin: password=${password.take(4)}..., baseUrl=$baseUrl")
        val body = """{"username":"admin","password":"$password"}"""
        val request = Request.Builder()
            .url("$baseUrl/api/auth/login")
            .post(body.toRequestBody(MEDIA_TYPE_JSON))
            .build()

        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        val responseBody = response.body?.string() ?: error("Empty response")
        Log.d(TAG, "adminLogin response: HTTP ${response.code}, body=${responseBody.take(300)}")

        if (!response.isSuccessful) {
            error("Login failed: HTTP ${response.code} $responseBody")
        }

        val jsonObj = JSONObject(responseBody)
        val code = jsonObj.optLong("code", -1)
        if (code != 200L) {
            error("Login rejected: code=$code $responseBody")
        }

        // 尝试多种 token 字段路径（兼容不同版本的 OpenList/AList）
        val data = jsonObj.optJSONObject("data")
        val token = listOf(
            data?.optString("token"),
            data?.optString("access_token"),
            jsonObj.optString("token"),
            jsonObj.optString("access_token"),
        ).firstOrNull { !it.isNullOrBlank() }
            ?: error("No token found in response: ${responseBody.take(300)}")
        adminToken = token
        Log.d(TAG, "adminLogin: token acquired (len=${token.length})")
        token
    }

    /**
     * 健康检查（ping）。
     */
    suspend fun ping(): Result<Boolean> = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/api/ping")
            .head()
            .build()

        client.newCall(request).execute().isSuccessful
    }

    // ====================================================================
    // File System
    // ====================================================================

    /**
     * 列出指定路径下的文件和目录。
     */
    /**
     * 列出指定路径下的文件和目录。
     * @param path 路径
     * @param adminPassword 管理员密码（用于 public API 没有 Bearer token 时的密码参数）
     */
    suspend fun listFiles(path: String = "/", adminPassword: String? = null, orderBy: String = "name", orderDirection: String = "ASC"): Result<List<OpenListFileItem>> {
        try {
            return listFilesImpl(path, adminPassword, orderBy, orderDirection)
        } catch (e: Exception) {
            Log.e(TAG, "listFiles crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            return Result.failure(e)
        }
    }

    private suspend fun listFilesImpl(path: String, adminPassword: String?, orderBy: String, orderDirection: String): Result<List<OpenListFileItem>> = runCatching {
        val pwdParam = adminPassword ?: ""
        val encodedPath = URLEncoder.encode(path, "UTF-8").replace("%2F", "/")
        val fullUrl = "$baseUrl/api/fs/list?path=$encodedPath&password=$pwdParam&page=1&per_page=0&refresh=false&order_by=$orderBy&order_direction=$orderDirection"
        Log.d(TAG, "listFiles URL: $fullUrl")
        val requestBuilder = Request.Builder()
            .url(fullUrl)

        // 优先使用 Bearer token
        if (adminToken != null) {
            requestBuilder.header("Authorization", adminToken!!)
        }

        val request = requestBuilder.build()

        Log.d(TAG, "executing request...")
        val (response, responseBody) = try {
            withContext(Dispatchers.IO) {
                val resp = client.newCall(request).execute()
                val body = resp.body?.string()
                Pair(resp, body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "execute/body.string() threw: ${e.javaClass.name}: msg='${e.message}'", e)
            throw e
        }
        Log.d(TAG, "response: HTTP ${response.code}, body=${responseBody?.take(200)}")
        if (responseBody == null) {
            error("Empty response body (HTTP ${response.code})")
        }

        if (!response.isSuccessful) {
            error("listFiles failed: HTTP ${response.code} $responseBody")
        }

        val jsonObj = JSONObject(responseBody)
        val code = jsonObj.optLong("code", -1)
        if (code != 200L) {
            error("listFiles rejected: code=$code $responseBody")
        }

        val content = jsonObj
            .getJSONObject("data")
            .getJSONArray("content")

        (0 until content.length()).map { i ->
            val item = content.getJSONObject(i)
            val name = item.optString("name", "")
            val rawPath = item.optString("path", "")
            // OpenList 可能不返回 path 字段，用父目录路径 + name 构造
            val resolvedPath = if (rawPath.isEmpty()) {
                if (path == "/") "/$name" else "$path/$name"
            } else {
                rawPath
            }
            OpenListFileItem(
                name = name,
                path = resolvedPath,
                isDirectory = item.optBoolean("is_dir", false),
                size = item.optLong("size", 0L),
                modified = item.optString("modified", ""),
                created = item.optString("created", ""),
                thumb = item.optString("thumb", ""),
            )
        }
    }

    // ====================================================================
    // File Operations
    // ====================================================================

    /**
     * 移动文件/文件夹到目标目录。
     * @param srcDir 源目录路径
     * @param dstDir 目标目录路径
     * @param names 要移动的文件/文件夹名称列表
     */
    suspend fun moveFiles(srcDir: String, dstDir: String, names: List<String>): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("src_dir", srcDir)
            put("dst_dir", dstDir)
            put("names", JSONArray(names))
        }
        val request = Request.Builder()
            .url("$baseUrl/api/fs/move")
            .header("Authorization", adminToken ?: error("Not logged in"))
            .post(body.toString().toRequestBody(MEDIA_TYPE_JSON))
            .build()

        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        val responseBody = response.body?.string() ?: error("Empty response")
        val jsonObj = JSONObject(responseBody)
        val code = jsonObj.optLong("code", -1)
        if (code != 200L) {
            error("Move failed: code=$code $responseBody")
        }
        true
    }

    // ====================================================================
    // Storage Management (需要 admin token)
    // ====================================================================

    /**
     * 列出存储后端。
     */
    suspend fun listStorages(): Result<List<JSONObject>> = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/api/admin/storage/list")
            .header("Authorization", "Bearer ${adminToken ?: error("Not logged in")}")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: error("Empty response")
        val jsonObj = JSONObject(responseBody)

        val content = jsonObj
            .getJSONObject("data")
            .optJSONArray("content") ?: JSONArray()

        (0 until content.length()).map { content.getJSONObject(it) }
    }

    /**
     * 添加存储后端。
     */
    suspend fun addStorage(config: String): Result<Boolean> = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/api/admin/storage/create")
            .header("Authorization", "Bearer ${adminToken ?: error("Not logged in")}")
            .post(config.toRequestBody(MEDIA_TYPE_JSON))
            .build()

        val response = client.newCall(request).execute()
        val jsonObj = JSONObject(response.body?.string() ?: error("Empty response"))
        jsonObj.optLong("code", -1) == 200L
    }

    /**
     * 删除存储后端。
     */
    suspend fun deleteStorage(storageId: String): Result<Boolean> = runCatching {
        val body = """{"id":"$storageId"}"""
        val request = Request.Builder()
            .url("$baseUrl/api/admin/storage/delete")
            .header("Authorization", "Bearer ${adminToken ?: error("Not logged in")}")
            .post(body.toRequestBody(MEDIA_TYPE_JSON))
            .build()

        val response = client.newCall(request).execute()
        val jsonObj = JSONObject(response.body?.string() ?: error("Empty response"))
        jsonObj.optLong("code", -1) == 200L
    }

    /**
     * 修改管理员密码（PUT /api/auth/admin）。
     */
    suspend fun changePassword(newPassword: String): Result<Boolean> = runCatching {
        val body = """{"password":"$newPassword"}"""
        Log.d(TAG, "changePassword: token=${adminToken?.take(10)}..., url=$baseUrl/api/auth/admin")
        val request = Request.Builder()
            .url("$baseUrl/api/auth/admin")
            .header("Authorization", "Bearer ${adminToken ?: error("Not logged in")}")
            .put(body.toRequestBody(MEDIA_TYPE_JSON))
            .build()

        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        val responseBody = response.body?.string() ?: error("Empty response")
        Log.d(TAG, "changePassword response: HTTP ${response.code}, body=${responseBody.take(500)}")

        if (!response.isSuccessful) {
            error("HTTP ${response.code}: $responseBody")
        }

        val jsonObj = JSONObject(responseBody)
        val code = jsonObj.optLong("code", -1)
        if (code != 200L) {
            error("code=$code: $responseBody")
        }
        true
    }
}

/**
 * OpenList 文件条目。
 */
data class OpenListFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: String = "",
    val created: String = "",
    val thumb: String = "",
)
