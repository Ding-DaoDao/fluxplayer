package dev.anilbeesetti.nextplayer.core.data.pan123

import android.util.Log
import dev.anilbeesetti.nextplayer.core.data.BaseCloudApiClient
import dev.anilbeesetti.nextplayer.core.data.CloudHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Pan123ApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT
) : BaseCloudApiClient(client) {

    companion object {
        private const val TAG = "Pan123Api"
        private const val CONFIG_URL = "https://apigate.123795.com/getconfig-api/v1/getconfig?platform=android&version=313&channel=1003&env="
        private const val WEB_API_BASE = "https://api.123278.com/b/api"

        private val DEVICE_TYPES = listOf(
            "2312DRAABC", "2312DRAABI", "2312DRAABG", "2310RK86C", "2310RK86I",
            "2311RK78C", "2304FPN6DC", "2306EPN60G", "23026PC78C", "23026PC78I",
            "24122RKC7C", "24127RK2CC", "24108PN61G", "24108PN61I",
            "24097PN53G", "24097PN53I", "24129PN74C", "24129PN74G", "24129PN74I"
        )
        private val OS_VERSIONS = listOf("Android_13", "Android_14", "Android_15", "Android_16")

        fun parseErrorMessage(json: JSONObject, operation: String): String {
            val raw = json.optString("message", "")
            return raw.ifBlank { "$operation 失败" }
        }
    }

    // region ==================== 设备模拟 ====================

    private val uuid: String = UUID.randomUUID().toString().replace("-", "")
    private val deviceType: String = DEVICE_TYPES.random()
    private val osVersion: String = OS_VERSIONS.random()

    // endregion

    // region ==================== 认证 & 配置 ====================

    private var authToken: String? = null
    private var config: JSONObject? = null

    fun setToken(token: String?) {
        Log.d(TAG, "setToken: ${token?.take(20)}...")
        this.authToken = token
    }

    fun setTokenDirectly(token: String) {
        Log.d(TAG, "setTokenDirectly: ${token.take(20)}...")
        this.authToken = token
    }

    fun getToken(): String? = authToken

    suspend fun loadConfig(): Result<JSONObject> = runCatching {
        if (config != null) {
            Log.d(TAG, "loadConfig: using cached config")
            return@runCatching config!!
        }
        Log.d(TAG, "loadConfig: fetching from $CONFIG_URL")
        val request = Request.Builder().url(CONFIG_URL).get().build()
        val body = executeRequest(request)
        Log.d(TAG, "loadConfig response: ${body.take(500)}")
        val json = JSONObject(body)
        val cfg = json.getJSONObject("data")
        config = cfg
        Log.d(TAG, "loadConfig success, interfaceapi keys: ${cfg.optJSONObject("interfaceapi")?.keys()?.asSequence()?.toList()}")
        cfg
    }

    private fun apiEndpoint(key: String): String {
        val cfg = config ?: throw IllegalStateException("Config not loaded")
        val apis = cfg.optJSONObject("interfaceapi")
            ?: throw IllegalStateException("No interfaceapi in config")
        val endpoint = apis.optString(key)
        if (endpoint.isBlank()) throw IllegalStateException("Endpoint '$key' not found in config")
        Log.d(TAG, "apiEndpoint($key) = $endpoint")
        return endpoint
    }

    // endregion

    // region ==================== Headers ====================

    private fun buildHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["x-channel"] = "1003"
        headers["user-agent"] = "123pan/v3.1.3(Android_10;Xiaomi)"
        authToken?.let { headers["authorization"] = it }
        headers["content-type"] = "application/json; charset=UTF-8"
        headers["osversion"] = osVersion
        headers["loginuuid"] = uuid
        headers["platform"] = "android"
        headers["devicetype"] = deviceType
        headers["devicename"] = "Xiaomi"
        headers["host"] = "www.123pan.com"
        headers["app-version"] = "313"
        headers["x-app-version"] = "3.1.3"
        return headers
    }

    private fun buildWebHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        authToken?.let { headers["authorization"] = it }
        headers["content-type"] = "application/json; charset=UTF-8"
        headers["platform"] = "web"
        headers["app-version"] = "3"
        headers["Referer"] = "https://yun.123pan.cn/"
        return headers
    }

    // endregion

    // region ==================== HTTP 请求 ====================

    private suspend fun apiPost(endpoint: String, body: JSONObject? = null): JSONObject {
        val headers = buildHeaders()
        val requestBuilder = Request.Builder().url(endpoint)
        if (body != null) {
            val bodyStr = body.toString()
            Log.d(TAG, "apiPost -> $endpoint body=${bodyStr.take(200)}")
            requestBuilder.post(bodyStr.toRequestBody(jsonMediaType))
        } else {
            Log.d(TAG, "apiPost(GET) -> $endpoint")
            requestBuilder.get()
        }
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val respBody = executeRequest(requestBuilder.build())
        Log.d(TAG, "apiPost <- ${respBody.take(500)}")
        return try {
            JSONObject(respBody)
        } catch (e: Exception) {
            Log.e(TAG, "apiPost JSON parse error: ${e.message}, body=${respBody.take(1000)}")
            throw IllegalStateException("响应解析失败: ${e.message}", e)
        }
    }

    private suspend fun apiGet(endpoint: String): JSONObject {
        val headers = buildHeaders()
        val requestBuilder = Request.Builder().url(endpoint).get()
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        Log.d(TAG, "apiGet -> $endpoint")
        val respBody = executeRequest(requestBuilder.build())
        Log.d(TAG, "apiGet <- ${respBody.take(500)}")
        return try {
            JSONObject(respBody)
        } catch (e: Exception) {
            Log.e(TAG, "apiGet JSON parse error: ${e.message}, body=${respBody.take(1000)}")
            throw IllegalStateException("响应解析失败: ${e.message}", e)
        }
    }

    /**
     * 执行 Web API 请求（使用 buildWebHeaders）
     */
    private suspend fun webGet(endpoint: String): JSONObject {
        val headers = buildWebHeaders()
        val requestBuilder = Request.Builder().url(endpoint).get()
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        Log.d(TAG, "webGet -> $endpoint")
        val respBody = executeRequest(requestBuilder.build())
        Log.d(TAG, "webGet <- ${respBody.take(500)}")
        return try {
            JSONObject(respBody)
        } catch (e: Exception) {
            Log.e(TAG, "webGet JSON parse error: ${e.message}, body=${respBody.take(1000)}")
            throw IllegalStateException("响应解析失败: ${e.message}", e)
        }
    }

    // endregion

    // region ==================== 登录 ====================

    /**
     * 使用手机号+密码登录 123 云盘（Android APP API）
     */
    suspend fun login(passport: String, password: String): Result<String> = runCatching {
        Log.d(TAG, "login: passport=$passport")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("login")
        val body = JSONObject().apply {
            put("passport", passport)
            put("password", password)
            put("type", 1)
        }
        Log.d(TAG, "login request body: $body")
        val json = apiPost(endpoint, body)
        Log.d(TAG, "login response: $json")

        val message = json.optString("message", "")
        Log.d(TAG, "login message=$message")
        if (message != "success") {
            val errMsg = json.optString("message", "登录失败")
            Log.e(TAG, "login failed: $errMsg")
            throw IllegalStateException(errMsg)
        }
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("登录响应无data字段: $json")
        val token = data.optString("token", "")
        if (token.isBlank()) {
            Log.e(TAG, "login: no token in response, data=$data")
            throw IllegalStateException("登录响应中未找到token")
        }
        authToken = "Bearer $token"
        Log.d(TAG, "login success, token=${token.take(10)}...")
        token
    }

    // endregion

    // region ==================== 文件操作 ====================

    /**
     * 列出文件 — 使用 Web API（与反编译代码一致）
     * API: https://api.123278.com/b/api/file/list/new
     */
    suspend fun listFiles(
        parentFileId: String = "0",
        page: Int = 1,
        orderBy: String = "update_time",
        orderDirection: String = "desc",
        searchData: String = ""
    ): Result<List<Pan123FileItem>> = runCatching {
        Log.d(TAG, "listFiles: parentFileId=$parentFileId, page=$page")
        // 使用 Web API（与反编译代码一致）
        val webUrl = "$WEB_API_BASE/file/list/new" +
            "?driveId=0" +
            "&limit=100" +
            "&next=0" +
            "&orderBy=$orderBy" +
            "&orderDirection=$orderDirection" +
            "&parentFileId=$parentFileId" +
            "&trashed=false" +
            "&SearchData=$searchData" +
            "&Page=$page" +
            "&OnlyLookAbnormalFile=0" +
            "&event=homeListFile" +
            "&operateType=1" +
            "&inDirectSpace=false" +
            "&fileCategory=0" +
            "&isSearchOrder=false"

        val json = webGet(webUrl)

        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("message", "未知错误")
            Log.e(TAG, "listFiles failed: code=$code, message=$msg")
            throw IllegalStateException("获取文件列表失败: $msg")
        }

        val data = json.optJSONObject("data")
        val infoList = data?.optJSONArray("InfoList") ?: JSONArray()
        Log.d(TAG, "listFiles: got ${infoList.length()} items")

        (0 until infoList.length()).map { i ->
            val item = infoList.getJSONObject(i)
            val downloadUrl = item.optString("DownloadUrl", "")
            val thumbnail = item.optString("Thumbnail", "")
            val thumbnailUrl = when {
                thumbnail.isNotBlank() -> thumbnail
                downloadUrl.contains("trade_key=123pan-thumbnail") -> downloadUrl
                else -> ""
            }.ifBlank { null }

            Pan123FileItem(
                fileId = item.getString("FileId"),
                fileName = item.getString("FileName"),
                type = item.getInt("Type"),
                size = item.optLong("Size", 0),
                etag = item.optString("Etag", ""),
                s3keyFlag = item.optString("S3KeyFlag", ""),
                downloadUrl = downloadUrl,
                createAt = item.optString("CreateAt", ""),
                trashedAt = item.optString("TrashedAt", ""),
                starredStatus = item.optInt("StarredStatus", 0),
                thumbnailUrl = thumbnailUrl
            )
        }
    }

    suspend fun getFileDownloadUrl(item: Pan123FileItem): Result<String> = runCatching {
        Log.d(TAG, "getFileDownloadUrl: fileId=${item.fileId}")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("fileDownloadInfo")
        val body = JSONObject().apply {
            put("fileId", item.fileId)
            put("etag", item.etag)
            put("size", item.size)
            put("s3keyFlag", item.s3keyFlag)
        }
        val json = apiPost(endpoint, body)
        val url = json.optJSONObject("data")?.optString("DownloadUrl", "") ?: ""
        Log.d(TAG, "getFileDownloadUrl result: ${url.take(100)}")
        url
    }

    /**
     * 获取视频播放信息 — 使用 apiGet + URL 参数（与反编译代码一致）
     * 解析 video_play_info 数组获取不同清晰度的播放 URL
     */
    suspend fun getVideoPlayInfo(item: Pan123FileItem): Result<VideoPlayResult> = runCatching {
        Log.d(TAG, "getVideoPlayInfo: fileId=${item.fileId}, size=${item.size}, etag=${item.etag}")
        loadConfig().getOrThrow()

        // 使用 buildUrl + apiGet（与反编译代码一致）
        val baseEndpoint = apiEndpoint("getVideoPlayInfo")
        val params = mapOf(
            "etag" to item.etag,
            "size" to item.size.toString()
        )
        val fullUrl = buildUrl(baseEndpoint, params)
        Log.d(TAG, "getVideoPlayInfo URL: $fullUrl")

        val json = apiGet(fullUrl)
        Log.d(TAG, "getVideoPlayInfo response: ${json.toString().take(500)}")

        val data = json.optJSONObject("data")
        if (data == null) {
            Log.e(TAG, "getVideoPlayInfo: no data field")
            throw IllegalStateException("无视频信息")
        }

        val urls = mutableListOf<String>()
        val names = mutableListOf<String>()

        // 主播放 URL（原画）
        val videoUrl = data.optString("url", "")
        if (videoUrl.isNotBlank()) {
            urls.add(videoUrl)
            names.add("原画")
            Log.d(TAG, "getVideoPlayInfo: added 原画 url=${videoUrl.take(100)}")
        }

        // 下载 URL 作为备选（原画2）
        val dlUrl = item.downloadUrl
        if (dlUrl.isNotBlank() && dlUrl != videoUrl) {
            urls.add(dlUrl)
            names.add("原画2")
            Log.d(TAG, "getVideoPlayInfo: added 原画2")
        }

        // 转码清晰度列表
        val playInfos = data.optJSONArray("video_play_info")
        if (playInfos != null) {
            for (i in 0 until playInfos.length()) {
                val info = playInfos.getJSONObject(i)
                val url = info.getString("url")
                val resolution = info.optString("resolution", "转码${i + 1}")
                urls.add(url)
                names.add(resolution)
                Log.d(TAG, "getVideoPlayInfo: added $resolution url=${url.take(100)}")
            }
        }

        Log.d(TAG, "getVideoPlayInfo: total ${urls.size} quality options: $names")
        VideoPlayResult(urls, names)
    }

    /**
     * 获取文件下载信息（含 headers）— 与反编译代码一致
     */
    suspend fun getFileDownloadInfo(item: Pan123FileItem): Result<DownloadInfo> = runCatching {
        Log.d(TAG, "getFileDownloadInfo: fileId=${item.fileId}")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("fileDownloadInfo")
        val body = JSONObject().apply {
            put("driveId", 0)
            put("etag", item.etag)
            put("fileId", item.fileId)
            put("s3keyFlag", item.s3keyFlag)
            put("FileName", item.fileName)
            put("Size", item.size)
        }
        val json = apiPost(endpoint, body)
        val data = json.getJSONObject("data")
        val downloadUrl = data.optString("DownloadUrl", "")
        if (downloadUrl.isBlank()) {
            throw IllegalStateException("未获取到下载链接")
        }
        DownloadInfo(
            url = downloadUrl,
            fileName = data.optString("fileName", item.fileName),
            size = data.optLong("size", item.size)
        )
    }

    suspend fun createFolder(name: String, parentFileId: String = "0"): Result<Boolean> = runCatching {
        Log.d(TAG, "createFolder: name=$name, parentFileId=$parentFileId")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("createFolder")
        val body = JSONObject().apply {
            put("parentFileId", parentFileId)
            put("name", name)
        }
        apiPost(endpoint, body)
        true
    }

    suspend fun renameFile(fileId: String, newName: String): Result<Boolean> = runCatching {
        Log.d(TAG, "renameFile: fileId=$fileId, newName=$newName")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("renameFile")
        val body = JSONObject().apply {
            put("fileId", fileId)
            put("name", newName)
        }
        apiPost(endpoint, body)
        true
    }

    suspend fun deleteFile(
        fileId: String, fileName: String, etag: String,
        size: Long, s3keyFlag: String
    ): Result<Boolean> = runCatching {
        Log.d(TAG, "deleteFile: fileId=$fileId, fileName=$fileName")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("deleteFile")
        val body = JSONObject().apply {
            put("fileId", fileId)
            put("fileName", fileName)
            put("etag", etag)
            put("size", size)
            put("s3keyFlag", s3keyFlag)
        }
        apiPost(endpoint, body)
        true
    }

    suspend fun deleteFolder(fileId: String, newName: String): Result<Boolean> = runCatching {
        Log.d(TAG, "deleteFolder: fileId=$fileId")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("deleteFolder")
        val body = JSONObject().apply {
            put("fileId", fileId)
            put("name", newName)
        }
        apiPost(endpoint, body)
        true
    }

    suspend fun moveFile(fileId: String, parentFileId: String): Result<Boolean> = runCatching {
        Log.d(TAG, "moveFile: fileId=$fileId -> parentFileId=$parentFileId")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("moveFile")
        val body = JSONObject().apply {
            put("fileId", fileId)
            put("parentFileId", parentFileId)
        }
        apiPost(endpoint, body)
        true
    }

    suspend fun copyFile(
        fileId: String, targetFileId: Long, etag: String,
        size: Long, s3keyFlag: String
    ): Result<Boolean> = runCatching {
        Log.d(TAG, "copyFile: fileId=$fileId -> targetFileId=$targetFileId")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("copyFile")
        val body = JSONObject().apply {
            put("fileId", fileId)
            put("targetFileId", targetFileId)
            put("etag", etag)
            put("size", size)
            put("s3keyFlag", s3keyFlag)
        }
        apiPost(endpoint, body)
        true
    }

    /**
     * 兼容旧接口：trashFile 通过 web API 实现
     */
    suspend fun trashFile(fileIds: List<String>): Result<Boolean> = runCatching {
        Log.d(TAG, "trashFile: fileIds=$fileIds")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("trashFile")
        val body = JSONObject().apply { put("fileIds", JSONArray(fileIds)) }
        apiPost(endpoint, body)
        true
    }

    // endregion

    // region ==================== 用户信息 ====================

    suspend fun getUserInfo(): Result<Pan123UserInfo> = runCatching {
        Log.d(TAG, "getUserInfo")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("userInfo")
        val json = apiGet(endpoint)
        val data = json.optJSONObject("data") ?: throw IllegalStateException("No user data")
        Pan123UserInfo(
            nickname = data.optString("nickname", ""),
            uid = data.optLong("uid", 0),
            spaceUsed = data.optLong("spaceUsed", 0),
            spacePermanent = data.optLong("spacePermanent", 0),
            isVip = data.optBoolean("isVip", false),
            headImage = data.optString("headImage", ""),
            vipDesc = data.optString("vipDesc", ""),
            vipTimeDesc = data.optString("vipTimeDesc", "")
        )
    }

    // endregion
}
