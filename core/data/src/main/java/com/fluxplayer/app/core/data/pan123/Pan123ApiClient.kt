package com.fluxplayer.app.core.data.pan123

import android.util.Base64
import android.util.Log
import com.fluxplayer.app.core.data.BaseCloudApiClient
import com.fluxplayer.app.core.data.CloudHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Pan123ApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT
) : BaseCloudApiClient(client) {

    companion object {
        private const val TAG = "Pan123Api"
        private const val CONFIG_URL = "https://apigate.123795.com/getconfig-api/v1/getconfig?platform=android&version=313&channel=1003&env="
        private const val WEB_API_BASE = "https://api.123278.com/b/api"
        private const val API_BASE = "https://api.123278.com/api"

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

        private const val AES_KEY = "pXce-DF4m7FnlftioS2nwg=="

        /**
         * 从CDN下载URL的query string中提取ref参数
         */
        fun parseRefFromUrl(url: String): String? {
            return try {
                val uri = java.net.URI(url)
                val query = uri.query ?: return null
                query.split("&")
                    .map { it.split("=", limit = 2) }
                    .associate { it[0] to (it.getOrNull(1) ?: "") }["ref"]
            } catch (e: Exception) {
                Log.e(TAG, "parseRefFromUrl error: ${e.message}")
                null
            }
        }

        /**
         * AES/CBC/PKCS5Padding 解密ref参数
         * 密钥: pXce-DF4m7FnlftioS2nwg==
         * IV: 密文字节的前16字节
         * 密文格式: URL-safe Base64（-→+，_→/）
         */
        fun decryptRef(encryptedStr: String): String {
            val trimmed = encryptedStr.trim()
            var standardBase64 = trimmed.replace('-', '+').replace('_', '/')
            val remainder = standardBase64.length % 4
            if (remainder > 0) {
                standardBase64 += "=".repeat(4 - remainder)
            }

            val keyBytes = AES_KEY.toByteArray(Charsets.UTF_8)
            val decoded = Base64.decode(standardBase64, Base64.DEFAULT)

            if (decoded.size < 16) throw IllegalArgumentException("密文长度不足，至少需16字节")

            val ivBytes = decoded.copyOfRange(0, 16)
            val cipherText = decoded.copyOfRange(16, decoded.size)

            val secretKeySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(cipherText)
            return String(decryptedBytes, Charsets.UTF_8)
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
        this.authToken = token
        if (token.isNullOrBlank()) this.config = null
    }

    fun setTokenDirectly(token: String) {
        this.authToken = token
    }

    fun getToken(): String? = authToken

    suspend fun loadConfig(): Result<JSONObject> = runCatching {
        if (config != null) {
            return@runCatching config!!
        }
        val request = Request.Builder().url(CONFIG_URL).get().build()
        val body = executeRequest(request)
        val json = JSONObject(body)
        val cfg = json.getJSONObject("data")
        config = cfg
        val apis = cfg.optJSONObject("interfaceapi")
        if (apis != null) {
            val names = apis.names()
            val keys = if (names != null) (0 until names.length()).map { names.getString(it) } else emptyList()
            Log.d(TAG, "Config interfaceapi keys: $keys")
        }
        cfg
    }

    private fun apiEndpoint(key: String): String {
        val cfg = config ?: throw IllegalStateException("Config not loaded")
        val apis = cfg.optJSONObject("interfaceapi")
            ?: throw IllegalStateException("No interfaceapi in config")
        val endpoint = apis.optString(key)
        if (endpoint.isBlank()) throw IllegalStateException("Endpoint '$key' not found in config")
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
            requestBuilder.post(body.toString().toRequestBody(jsonMediaType))
        } else {
            requestBuilder.get()
        }
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val respBody = executeRequest(requestBuilder.build())
        return try {
            JSONObject(respBody)
        } catch (e: Exception) {
            Log.e(TAG, "apiPost JSON parse error: ${e.message}")
            throw IllegalStateException("响应解析失败: ${e.message}", e)
        }
    }

    private suspend fun apiGet(endpoint: String): JSONObject {
        val headers = buildHeaders()
        val requestBuilder = Request.Builder().url(endpoint).get()
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val respBody = executeRequest(requestBuilder.build())
        return try {
            JSONObject(respBody)
        } catch (e: Exception) {
            Log.e(TAG, "apiGet JSON parse error: ${e.message}")
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
        val respBody = executeRequest(requestBuilder.build())
        return try {
            JSONObject(respBody)
        } catch (e: Exception) {
            Log.e(TAG, "webGet JSON parse error: ${e.message}")
            throw IllegalStateException("响应解析失败: ${e.message}", e)
        }
    }

    // endregion

    // region ==================== 登录 ====================

    /**
     * 使用手机号+密码登录 123 云盘（Android APP API）
     */
    suspend fun login(passport: String, password: String): Result<LoginResult> = runCatching {
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("login")
        val body = JSONObject().apply {
            put("passport", passport)
            put("password", password)
            put("type", 1)
        }
        val json = apiPost(endpoint, body)

        val message = json.optString("message", "")
        if (message != "success") {
            val errMsg = json.optString("message", "登录失败")
            throw IllegalStateException(errMsg)
        }
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("登录响应无data字段")
        val token = data.optString("token", "")
        if (token.isBlank()) {
            throw IllegalStateException("登录响应中未找到token")
        }
        val refreshTokenExpireTime = data.optLong("refresh_token_expire_time", 0)
        authToken = "Bearer $token"
        LoginResult(token, refreshTokenExpireTime)
    }

    // endregion

    // region ==================== 文件操作 ====================

    /**
     * 列出文件 — 使用 Web API + page 页码分页（Web API 的 DownloadUrl 含缩略图参数）
     * API: https://api.123278.com/b/api/file/list/new
     */
    suspend fun listFiles(
        parentFileId: String = "0",
        page: Int = 1,
        orderBy: String = "update_time",
        orderDirection: String = "desc",
        searchData: String = ""
    ): Result<Pan123ListResult> = runCatching {
        val url = "$WEB_API_BASE/file/list/new" +
            "?driveId=0" +
            "&limit=100" +
            "&page=$page" +
            "&orderBy=$orderBy" +
            "&orderDirection=$orderDirection" +
            "&parentFileId=$parentFileId" +
            "&trashed=false" +
            "&SearchData=$searchData" +
            "&OnlyLookAbnormalFile=0" +
            "&event=homeListFile" +
            "&operateType=1" +
            "&inDirectSpace=false" +
            "&fileCategory=0" +
            "&isSearchOrder=false"

        val json = webGet(url)

        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("message", "未知错误")
            throw IllegalStateException("获取文件列表失败: $msg")
        }

        val data = json.optJSONObject("data")
        val infoList = data?.optJSONArray("InfoList") ?: JSONArray()

        val items = (0 until infoList.length()).map { i ->
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
                category = item.optInt("Category", 0),
                etag = item.optString("Etag", ""),
                s3keyFlag = item.optString("S3KeyFlag", ""),
                downloadUrl = downloadUrl,
                createAt = item.optString("CreateAt", ""),
                trashedAt = item.optString("TrashedAt", ""),
                starredStatus = item.optInt("StarredStatus", 0),
                thumbnailUrl = thumbnailUrl
            )
        }
        Pan123ListResult(items, null)
    }

    suspend fun getFileDownloadUrl(item: Pan123FileItem): Result<String> = runCatching {
        Log.d(TAG, "========== getFileDownloadUrl START ==========")
        Log.d(TAG, "getFileDownloadUrl: fileId=${item.fileId}, fileName=${item.fileName}")
        
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("fileDownloadInfo")
        Log.d(TAG, "getFileDownloadUrl: endpoint=$endpoint")
        
        val body = JSONObject().apply {
            put("driveId", 0)
            put("fileId", item.fileId)
            put("etag", item.etag)
            put("size", item.size)
            put("s3keyFlag", item.s3keyFlag)
            put("type", item.type)
        }
        Log.d(TAG, "getFileDownloadUrl: request body=$body")
        
        val json = apiPost(endpoint, body)
        Log.d(TAG, "getFileDownloadUrl: response code=${json.optInt("code", -1)}, message=${json.optString("message", "")}")
        
        val url = json.optJSONObject("data")?.optString("DownloadUrl", "") ?: ""
        Log.d(TAG, "getFileDownloadUrl: downloadUrl=${url.take(200)}...")
        
        if (url.isBlank()) {
            Log.e(TAG, "getFileDownloadUrl: URL is BLANK! json=$json")
            throw IllegalStateException("未获取到下载链接")
        }
        
        Log.d(TAG, "========== getFileDownloadUrl END ==========")
        url
    }

    /**
     * 获取视频播放信息 — 使用 apiGet + URL 参数（与反编译代码一致）
     * 解析 video_play_info 数组获取不同清晰度的播放 URL
     */
    suspend fun getVideoPlayInfo(item: Pan123FileItem): Result<VideoPlayResult> = runCatching {
        Log.d(TAG, "========== getVideoPlayInfo START ==========")
        Log.d(TAG, "getVideoPlayInfo: fileId=${item.fileId}, fileName=${item.fileName}, etag=${item.etag}, size=${item.size}")
        
        loadConfig().getOrThrow()
        Log.d(TAG, "getVideoPlayInfo: loadConfig SUCCESS")

        // 使用 buildUrl + apiGet（与反编译代码一致）
        val baseEndpoint = apiEndpoint("getVideoPlayInfo")
        Log.d(TAG, "getVideoPlayInfo: baseEndpoint=$baseEndpoint")
        
        val params = mapOf(
            "etag" to item.etag,
            "size" to item.size.toString()
        )
        val fullUrl = buildUrl(baseEndpoint, params)
        Log.d(TAG, "getVideoPlayInfo: fullUrl=$fullUrl")

        Log.d(TAG, "getVideoPlayInfo: Calling apiGet...")
        val json = apiGet(fullUrl)
        Log.d(TAG, "getVideoPlayInfo: apiGet response code=${json.optInt("code", -1)}, message=${json.optString("message", "")}")
        Log.d(TAG, "getVideoPlayInfo: apiGet response data=${json.optJSONObject("data")?.toString()?.take(500)}")

        val data = json.optJSONObject("data")
        if (data == null) {
            Log.e(TAG, "getVideoPlayInfo: data is NULL! json=$json")
            throw IllegalStateException("无视频信息: code=${json.optInt("code", -1)}, message=${json.optString("message", "")}")
        }

        val urls = mutableListOf<String>()
        val names = mutableListOf<String>()

        // getVideoPlayInfo URL → 原画（默认播放用）
        val videoUrl = data.optString("url", "")
        Log.d(TAG, "getVideoPlayInfo: original videoUrl=$videoUrl")
        if (videoUrl.isNotBlank()) {
            urls.add(videoUrl)
            names.add("原画")
            Log.d(TAG, "getVideoPlayInfo: Added original video URL")
        }

        // 转码清晰度列表
        val playInfos = data.optJSONArray("video_play_info")
        Log.d(TAG, "getVideoPlayInfo: video_play_info array size=${playInfos?.length() ?: 0}")
        if (playInfos != null) {
            for (i in 0 until playInfos.length()) {
                val info = playInfos.getJSONObject(i)
                val url = info.getString("url")
                val resolution = info.optString("resolution", "转码${i + 1}")
                Log.d(TAG, "getVideoPlayInfo: transcode[$i] resolution=$resolution, url=${url.take(100)}...")
                urls.add(url)
                names.add(resolution)
            }
        }
        
        Log.d(TAG, "getVideoPlayInfo: FINAL result - urls size=${urls.size}, names=$names")
        Log.d(TAG, "========== getVideoPlayInfo END ==========")

        VideoPlayResult(urls, names)
    }

    /**
     * 获取文件下载信息（含 headers）— 与反编译代码一致
     */
    suspend fun getFileDownloadInfo(item: Pan123FileItem): Result<DownloadInfo> = runCatching {
        Log.d(TAG, "========== getFileDownloadInfo START ==========")
        Log.d(TAG, "getFileDownloadInfo: fileId=${item.fileId}, fileName=${item.fileName}, etag=${item.etag}")
        
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("fileDownloadInfo")
        Log.d(TAG, "getFileDownloadInfo: endpoint=$endpoint")
        
        val body = JSONObject().apply {
            put("driveId", 0)
            put("etag", item.etag)
            put("fileId", item.fileId)
            put("s3keyFlag", item.s3keyFlag)
            put("FileName", item.fileName)
            put("Size", item.size)
            put("type", item.type)
        }
        Log.d(TAG, "getFileDownloadInfo: request body=$body")
        
        val json = apiPost(endpoint, body)
        Log.d(TAG, "getFileDownloadInfo: response code=${json.optInt("code", -1)}, message=${json.optString("message", "")}")
        Log.d(TAG, "getFileDownloadInfo: response data=${json.optJSONObject("data")?.toString()?.take(500)}")
        
        val data = json.getJSONObject("data")
        val downloadUrl = data.optString("DownloadUrl", "")
        Log.d(TAG, "getFileDownloadInfo: downloadUrl=${downloadUrl.take(200)}...")
        
        if (downloadUrl.isBlank()) {
            Log.e(TAG, "getFileDownloadInfo: DownloadUrl is BLANK! json=$json")
            throw IllegalStateException("未获取到下载链接: code=${json.optInt("code", -1)}, message=${json.optString("message", "")}")
        }
        
        Log.d(TAG, "========== getFileDownloadInfo END ==========")
        DownloadInfo(
            url = downloadUrl,
            fileName = data.optString("fileName", item.fileName),
            size = data.optLong("size", item.size)
        )
    }

    suspend fun createFolder(name: String, parentFileId: String = "0"): Result<Boolean> = runCatching {
        loadConfig().getOrThrow()
        val endpoint = "$API_BASE/file/upload_request"
        val body = JSONObject().apply {
            put("driveId", 0)
            put("parentFileId", parentFileId)
            put("duplicate", 1)
            put("NotReuse", true)
            put("etag", "")
            put("fileName", name)
            put("size", 0)
            put("type", 1)
        }
        val json = apiPost(endpoint, body)
        true
    }

    suspend fun renameFile(fileId: String, newName: String): Result<Boolean> = runCatching {
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("modifyFileName")
        val body = JSONObject().apply {
            put("driveId", 0)
            put("fileName", newName)
            put("fileId", fileId)
        }
        val json = apiPost(endpoint, body)
        json.optString("message") == "ok"
    }

    suspend fun moveFile(fileId: String, parentFileId: String): Result<Boolean> = runCatching {
        loadConfig().getOrThrow()
        val endpoint = "$API_BASE/file/mod_pid"
        val fileInfo = JSONObject().apply { put("FileId", fileId) }
        val body = JSONObject().apply {
            put("fileIdList", JSONArray(listOf(fileInfo)))
            put("parentFileId", parentFileId)
        }
        val json = apiPost(endpoint, body)
        true
    }

    suspend fun copyFile(
        fileId: String, targetFileId: Long, etag: String,
        size: Long, s3keyFlag: String
    ): Result<Boolean> = runCatching {
        loadConfig().getOrThrow()
        val endpoint = "$API_BASE/restful/goapi/v1/file/copy/async"
        val fileInfo = JSONObject().apply {
            put("fileId", fileId)
            put("size", size)
            put("etag", etag)
            put("type", 0)
            put("parentFileId", 0)
            put("fileName", "")
        }
        val body = JSONObject().apply {
            put("fileList", JSONArray(listOf(fileInfo)))
            put("targetFileId", targetFileId.toString())
        }
        val json = apiPost(endpoint, body)
        true
    }

    suspend fun trashFile(items: List<Pan123FileItem>): Result<Boolean> = runCatching {
        loadConfig().getOrThrow()
        val endpoint = "$API_BASE/file/trash"
        val trashInfoList = JSONArray()
        for (item in items) {
            trashInfoList.put(JSONObject().apply {
                put("FileId", item.fileId)
                put("FileName", item.fileName)
                put("Size", item.size)
                put("Etag", item.etag)
                put("S3KeyFlag", item.s3keyFlag)
                put("Type", item.type)
            })
        }
        val body = JSONObject().apply {
            put("driveId", 0)
            put("fileTrashInfoList", trashInfoList)
            put("operation", true)
        }
        val json = apiPost(endpoint, body)
        true
    }

    // endregion

    // region ==================== 用户信息 ====================

    suspend fun getUserInfo(): Result<Pan123UserInfo> = runCatching {
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("getUserInfo")
        Log.d(TAG, "getUserInfo resolved endpoint: $endpoint")
        val json = apiGet(endpoint)
        Log.d(TAG, "getUserInfo response: $json")
        val data = json.optJSONObject("data") ?: throw IllegalStateException("No user data")
        val vipInfos = data.optJSONArray("UserVipDetailInfos")
        val firstVip = if (vipInfos != null && vipInfos.length() > 0) vipInfos.getJSONObject(0) else null
        Pan123UserInfo(
            nickname = data.optString("Nickname", ""),
            uid = data.optLong("UID", 0),
            spaceUsed = data.optLong("SpaceUsed", 0),
            spacePermanent = data.optLong("SpacePermanent", 0),
            isVip = data.optBoolean("Vip", false),
            headImage = data.optString("HeadImage", ""),
            vipDesc = firstVip?.optString("VipDesc", "") ?: "",
            vipTimeDesc = firstVip?.optString("TimeDesc", "") ?: ""
        )
    }

    // endregion

    // region ==================== 下载辅助 ====================

    /**
     * 通过HEAD请求跟随重定向，获取最终CDN下载URL
     * @param intermediateUrl fileDownloadInfo返回的中转URL
     * @return 最终CDN URL
     */
    suspend fun resolveFinalDownloadUrl(intermediateUrl: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(intermediateUrl).head().build()
            val response = client.newCall(request).execute()
            response.use { it.request.url.toString() }
        }
    }

    /**
     * 构建123云盘下载专用Headers
     * 从最终CDN URL提取ref参数，AES解密得到Referer值
     */
    fun buildDownloadHeaders(finalUrl: String): Map<String, String> {
        Log.d(TAG, "buildDownloadHeaders: START, finalUrl=${finalUrl.take(200)}...")
        val ref = parseRefFromUrl(finalUrl)
        Log.d(TAG, "buildDownloadHeaders: parsed ref=$ref")
        
        val referer = if (ref != null) {
            try {
                val decrypted = decryptRef(ref)
                Log.d(TAG, "buildDownloadHeaders: decrypted referer=$decrypted")
                decrypted
            } catch (e: Exception) {
                Log.e(TAG, "buildDownloadHeaders: decryptRef failed: ${e.message}, using fallback")
                "https://yun.123pan.cn/"
            }
        } else {
            Log.w(TAG, "buildDownloadHeaders: No ref parameter found in URL, using default referer")
            "https://yun.123pan.cn/"
        }
        
        val headers = mapOf(
            "Referer" to referer,
            "X-MF-PAN-RANGE" to "1",
            "User-Agent" to Pan123AuthProvider.userAgent
        )
        Log.d(TAG, "buildDownloadHeaders: FINAL headers=$headers")
        return headers
    }

    // endregion

    // region ==================== 分享链接 ====================

    fun parseShareUrl(url: String): Pair<String, String?>? {
        try {
            val normalized = url.trim()
            val uri = java.net.URI(normalized)
            val host = uri.host ?: return null
            val path = uri.path ?: ""

            // https://123865.com/s/u9izjv-SYpOv?pwd=Qiye
            if (host.contains("123865.com") && path.startsWith("/s/")) {
                val shareKey = path.removePrefix("/s/").trimEnd('/')
                if (shareKey.isBlank()) return null
                val query = uri.query ?: ""
                val pwd = query.split("&")
                    .map { it.split("=", limit = 2) }
                    .associate { it[0] to (it.getOrNull(1) ?: "") }["pwd"]
                return shareKey to pwd
            }

            // https://1840976528.mshare.123pan.cn/123pan/cHCOTd-jVoM
            if (host.contains("mshare.123pan.cn") && path.contains("/123pan/")) {
                val shareKey = path.substringAfter("/123pan/").trimEnd('/')
                if (shareKey.isBlank()) return null
                return shareKey to null
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "parseShareUrl error: ${e.message}")
            return null
        }
    }

    fun isShareUrl(text: String): Boolean = parseShareUrl(text) != null

    suspend fun listShareFiles(
        shareKey: String,
        sharePwd: String?,
        parentFileId: String = "0",
    ): Result<Pan123ShareListing> = runCatching {
        loadConfig().getOrThrow()
        val baseUrl = apiEndpoint("shareFileDetails")
        val url = buildUrl(baseUrl, mapOf(
            "Page" to "1",
            "limit" to "100",
            "next" to "-1",
            "ParentFileId" to parentFileId,
            "shareKey" to shareKey,
            "SharePwd" to (sharePwd ?: ""),
            "orderBy" to "update_time",
            "orderDirection" to "desc",
        ))
        Log.d(TAG, "listShareFiles URL: $url")
        val json = apiGet(url)

        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("message", "获取分享内容失败")
            throw IllegalStateException(msg)
        }

        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("分享内容为空")
        val infoList = data.optJSONArray("InfoList")
        val items = mutableListOf<Pan123ShareFileItem>()
        if (infoList != null) {
            for (i in 0 until infoList.length()) {
                val f = infoList.getJSONObject(i)
                items.add(
                    Pan123ShareFileItem(
                        fileId = f.optString("FileId", ""),
                        fileName = f.optString("FileName", ""),
                        type = f.optInt("Type", 0),
                        size = f.optLong("Size", 0),
                        etag = f.optString("Etag", ""),
                    )
                )
            }
        }
        Pan123ShareListing(files = items, total = data.optInt("total", items.size))
    }

    suspend fun copySaveFiles(
        shareKey: String,
        sharePwd: String?,
        files: List<Pan123ShareFileItem>,
        targetFolderId: String,
    ): Result<Boolean> = runCatching {
        if (files.isEmpty()) throw IllegalArgumentException("files 不能为空")
        loadConfig().getOrThrow()
        val endpoint = apiEndpoint("copySaveFiles")
        Log.d(TAG, "copySaveFiles endpoint: $endpoint")
        val fileListArray = JSONArray()
        files.forEach { file ->
            fileListArray.put(JSONObject().apply {
                put("drive_id", 0)
                put("etag", file.etag)
                put("file_id", file.fileId)
                put("file_name", file.fileName)
                put("parent_file_id", targetFolderId)
                put("size", file.size)
                put("type", file.type)
            })
        }
        val body = JSONObject().apply {
            put("share_key", shareKey)
            put("share_pwd", sharePwd ?: "")
            put("file_list", fileListArray)
            put("current_level", 1)
            put("event", "transfer")
        }
        Log.d(TAG, "copySaveFiles body: $body")
        val json = apiPost(endpoint, body)
        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("message", "转存失败")
            throw IllegalStateException(msg)
        }
        true
    }

    // endregion
}
