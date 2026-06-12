package com.fluxplayer.app.core.data.cloud189

import android.util.Base64
import android.util.Log
import com.fluxplayer.app.core.data.BaseCloudApiClient
import com.fluxplayer.app.core.data.CloudHttpClient
import com.fluxplayer.app.core.data.GlobalCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class C189ApiClient(
    client: OkHttpClient = CloudHttpClient.DEFAULT
) : BaseCloudApiClient(client) {

    companion object {
        private const val TAG = "C189Api"
        private const val API_BASE = "https://api.cloud.189.cn"
        private const val OPEN_API_BASE = "https://cloud.189.cn/api/open"
    }

    var accessToken: String = ""
    var sessionKey: String = ""
    var sessionSecret: String = ""

    // ==================== 密码学工具 ====================

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha1(key: ByteArray, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun md5Signature(params: Map<String, String>): String {
        val sorted = params.entries.sortedBy { it.key }
        val raw = sorted.joinToString("&") { "${it.key}=${it.value}" }
        return md5(raw)
    }

    private fun buildDateHeader(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date())
    }

    // ==================== API 请求 ====================

    /** GET — session HMAC-SHA1 签名 (api.cloud.189.cn) */
    private suspend fun signedGet(
        path: String, queryParams: Map<String, String> = emptyMap()
    ): JSONObject {
        val sk = sessionKey.ifBlank { C189AuthProvider.sessionKey }
        val ss = sessionSecret.ifBlank { C189AuthProvider.sessionSecret }
        if (sk.isBlank()) throw IllegalStateException("未登录: sessionKey 为空")

        val date = buildDateHeader()
        val queryStr = if (queryParams.isNotEmpty()) {
            "?" + queryParams.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        } else ""

        val requestUri = path.substringBefore("?")
        val sigData = "SessionKey=$sk&Operate=GET&RequestURI=$requestUri&Date=$date"
        val sig = hmacSha1(ss.toByteArray(), sigData)

        val request = Request.Builder()
            .url("$API_BASE$path$queryStr")
            .get()
            .header("User-Agent", "Android")
            .header("accept", "application/json;charset=UTF-8")
            .header("sign-type", "1")
            .header("SessionKey", sk)
            .header("Signature", sig)
            .header("Date", date)
            .build()

        Log.d(TAG, "signedGet: sigData=$sigData, url=$API_BASE$path$queryStr")

        val resp = executeRequestAndGetResponse(request)
        val body = resp.body?.string() ?: throw IllegalStateException("Empty response")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${body.take(200)}")
        Log.d(TAG, "signedGet resp: HTTP ${resp.code}, body=${body.take(300)}")
        return JSONObject(body)
    }

    /** POST — accessToken + MD5 签名 → cloud.189.cn/api/open/ */
    private suspend fun openApiPost(
        actionPath: String, formParams: Map<String, String>
    ): JSONObject {
        val at = accessToken.ifBlank { C189AuthProvider.accessToken }
        if (at.isBlank()) throw IllegalStateException("未登录或 AccessToken 为空")

        val ts = System.currentTimeMillis().toString()
        val sigParams = linkedMapOf<String, String>()
        sigParams.putAll(formParams)
        sigParams["timestamp"] = ts
        sigParams["AccessToken"] = at
        val signature = md5Signature(sigParams)

        val formBody = formParams.entries.joinToString("&") { "${it.key}=${it.value}" }

        val builder = Request.Builder()
            .url("$OPEN_API_BASE/$actionPath")
            .header("User-Agent", "Android")
            .header("Referer", "https://cloud.189.cn/web/main/")
            .header("accept", "application/json;charset=UTF-8")
            .header("Sign-Type", "1")
            .header("Signature", signature)
            .header("Timestamp", ts)
            .header("Accesstoken", at)
            .post(formBody.toRequestBody(formUrlEncoded))

        Log.d(TAG, "openApiPost: $OPEN_API_BASE/$actionPath, ts=$ts")

        val resp = executeRequestAndGetResponse(builder.build())
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${respBody.take(200)}")
        return JSONObject(respBody)
    }

    /** GET — open API: cloud.189.cn/api/open/{actionPath} + MD5 签名 (参数放 URL) */
    private suspend fun openApiGet(
        actionPath: String, queryParams: Map<String, String>, retry: Boolean = true
    ): JSONObject {
        val at = accessToken.ifBlank { C189AuthProvider.accessToken }
        if (at.isBlank()) throw IllegalStateException("未登录或 AccessToken 为空")

        val ts = System.currentTimeMillis().toString()
        val sigParams = linkedMapOf<String, String>()
        sigParams.putAll(queryParams)
        sigParams["timestamp"] = ts
        sigParams["AccessToken"] = at
        val signature = md5Signature(sigParams)

        val queryStr = queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }

        val request = Request.Builder()
            .url("$OPEN_API_BASE/$actionPath?$queryStr")
            .get()
            .header("User-Agent", "Android")
            .header("Referer", "https://cloud.189.cn/web/main/")
            .header("accept", "application/json;charset=UTF-8")
            .header("Sign-Type", "1")
            .header("Signature", signature)
            .header("Timestamp", ts)
            .header("Accesstoken", at)
            .build()

        val resp = executeRequestAndGetResponse(request)
        val respBody = resp.body?.string() ?: throw IllegalStateException("Empty response")

            if (!resp.isSuccessful) {
            Log.e(TAG, "openApiGet FAILED: HTTP ${resp.code}, body=${respBody.take(200)}")
            // token/session 过期 → 尝试刷新
            val canRetry = respBody.contains("InvalidAccessToken") || respBody.contains("InvalidSessionKey")
            if (retry && canRetry) {
                Log.d(TAG, "openApiGet: token/session 过期，刷新后重试...")
                if (refreshAccessToken()) {
                    accessToken = C189AuthProvider.accessToken
                    sessionKey = C189AuthProvider.sessionKey
                    return openApiGet(actionPath, queryParams, false)
                }
            }
            throw IllegalStateException("HTTP ${resp.code}: ${respBody.take(200)}")
        }
        return JSONObject(respBody)
    }

    // ==================== 登录 ====================

    suspend fun loginByCookies(cookies: String): Boolean {
        try {
            val date = buildDateHeader()
            val requestUri = "/api/portal/loginByCookies.action"
            val sigData = "SessionKey=&Operate=GET&RequestURI=$requestUri&Date=$date"
            val signature = hmacSha1(ByteArray(0), sigData)

            val resp = executeRequestAndGetResponse(
                Request.Builder()
                    .url("$API_BASE$requestUri")
                    .get()
                    .header("User-Agent", "Android")
                    .header("accept", "application/json;charset=UTF-8")
                    .header("sign-type", "1")
                    .header("SessionKey", "")
                    .header("Signature", signature)
                    .header("Date", date)
                    .header("Cookie", cookies)
                    .build()
            )
            val body = resp.body?.string() ?: return false
            val json = JSONObject(body)

            accessToken = json.optString("accessToken", "")
            sessionKey = json.optString("sessionKey", "")
            sessionSecret = json.optString("sessionSecret", "")

            if (accessToken.isNotBlank()) {
                C189AuthProvider.accessToken = accessToken
                C189AuthProvider.sessionKey = sessionKey
                C189AuthProvider.sessionSecret = sessionSecret
                C189AuthProvider.isActive = true
            }
            return accessToken.isNotBlank()
        } catch (e: Exception) {
            Log.e(TAG, "loginByCookies failed", e)
        }
        return false
    }

    suspend fun loginByPassword(phone: String, password: String): Result<String> = runCatching {
        val ua = C189AuthProvider.userAgent
        val appKey = "8025431004"
        val returnUrl = "https://m.cloud.189.cn/zhuanti/2020/loginErrorPc/index.html"

        Log.d(TAG, "========== loginByPassword START phone=${phone.take(3)}**** ==========")

        // ---- 1) 获取加密公钥 ----
        Log.d(TAG, "[Step1] 请求 encryptConf.do...")
        val encryptRespBody = executeRequestOrNull(
            Request.Builder()
                .url("https://open.e.189.cn/api/logbox/config/encryptConf.do")
                .header("User-Agent", ua)
                .post("appId=$appKey".toRequestBody(formUrlEncoded))
                .build()
        )
        Log.d(TAG, "[Step1] encryptConf.do resp=$encryptRespBody")
        if (encryptRespBody.isNullOrBlank()) throw IllegalStateException("[Step1] 获取加密配置失败: 空响应")

        val encryptJson = JSONObject(encryptRespBody)
        val encryptResult = encryptJson.optInt("result", -1)
        if (encryptResult != 0) throw IllegalStateException("[Step1] 加密配置失败: $encryptResult")
        val data = encryptJson.getJSONObject("data")
        val pubKey = data.getString("pubKey")
        val pre = data.getString("pre")
        Log.d(TAG, "[Step1] OK pubKey.len=${pubKey.length}, pre=$pre")

        // ---- 2) 获取登录页 lt / reqId / paramId ----
        Log.d(TAG, "[Step2] 请求 unifyLoginForPC...")
        val noRedirectClient = CloudHttpClient.NO_REDIRECT
        val unifyUrl = "https://cloud.189.cn/api/portal/unifyLoginForPC.action?" +
                "appId=$appKey&clientType=10020" +
                "&returnURL=${URLEncoder.encode(returnUrl, "UTF-8")}" +
                "&timeStamp=${System.currentTimeMillis()}"

        val location = withContext(Dispatchers.IO) {
            val resp = noRedirectClient.newCall(
                Request.Builder().url(unifyUrl).header("User-Agent", ua).get().build()
            ).execute()
            val loc = resp.header("Location")
            resp.close()
            loc ?: throw IllegalStateException("[Step2] 未返回 302 Location")
        }
        Log.d(TAG, "[Step2] Location=$location")

        val html = withContext(Dispatchers.IO) {
            val resp = noRedirectClient.newCall(
                Request.Builder().url(location).header("User-Agent", ua).get().build()
            ).execute()
            val h = resp.body?.string()
            resp.close()
            h ?: throw IllegalStateException("[Step2] HTML 为空")
        }
        Log.d(TAG, "[Step2] HTML len=${html.length}")

        val lt = Regex("""lt\s*=\s*"([A-Fa-f0-9]+)"""").find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
        val reqId = Regex("""reqId\s*=\s*"([A-Fa-f0-9]+)"""").find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
        val paramId = Regex("""paramId\s*=\s*"([A-Fa-f0-9]+)"""").find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
        Log.d(TAG, "[Step2] lt=${lt.take(8)}... reqId=${reqId.take(8)}... paramId=${paramId.take(8)}...")
        if (lt.isBlank()) throw IllegalStateException("[Step2] 未获取到 lt")

        // ---- 3) RSA 加密并提交登录 ----
        Log.d(TAG, "[Step3] RSA 加密...")
        val rsaPhone = RsaHelper.encryptToRsaHex(phone, pubKey)
        val rsaPassword = RsaHelper.encryptToRsaHex(password, pubKey)
        Log.d(TAG, "[Step3] RSA OK phoneHex.len=${rsaPhone.length} pwdHex.len=${rsaPassword.length}")

        val loginBody = buildString {
            append("appKey=$appKey")
            append("&accountType=02")
            append("&validateCode=")
            append("&captchaToken=")
            append("&dynamicCheck=FALSE")
            append("&clientType=1")
            append("&cb_SaveName=3")
            append("&isOauth2=false")
            append("&returnUrl=${URLEncoder.encode(returnUrl, "UTF-8")}")
            append("&paramId=$paramId")
            append("&userName=${URLEncoder.encode("$pre$rsaPhone", "UTF-8")}")
            append("&password=${URLEncoder.encode("$pre$rsaPassword", "UTF-8")}")
        }

        val loginRespBody = executeRequestOrNull(
            Request.Builder()
                .url("https://open.e.189.cn/api/logbox/oauth2/loginSubmit.do")
                .header("User-Agent", ua)
                .header("Referer", "https://open.e.189.cn/")
                .header("lt", lt)
                .header("REQID", reqId)
                .post(loginBody.toRequestBody(formUrlEncoded))
                .build()
        )
        Log.d(TAG, "[Step3] loginSubmit.do resp=$loginRespBody")
        if (loginRespBody.isNullOrBlank()) throw IllegalStateException("[Step3] 登录请求返回空")

        val loginJson = JSONObject(loginRespBody)
        val loginResult = loginJson.optInt("result", -1)
        if (loginResult != 0) throw IllegalStateException("[Step3] 登录失败: ${loginJson.optString("msg")}")
        val toUrl = loginJson.optString("toUrl", "").ifBlank {
            throw IllegalStateException("[Step3] 无 toUrl")
        }
        Log.d(TAG, "[Step3] 登录通过")

        // ---- 4) 获取 sessionKey / sessionSecret ----
        Log.d(TAG, "[Step4] getSessionForPC...")
        val sessionResp = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("https://api.cloud.189.cn/getSessionForPC.action?" +
                            "appId=$appKey&clientType=TELEPC&version=6.2" +
                            "&channelId=web_cloud.189.cn" +
                            "&rand=${System.currentTimeMillis()}" +
                            "&redirectURL=${URLEncoder.encode(toUrl, "UTF-8")}")
                    .header("User-Agent", ua)
                    .header("accept", "application/json;charset=UTF-8")
                    .post("".toRequestBody(null))
                    .build()
            ).execute()
        }
        val sessionRespBody = sessionResp.body?.string()
        Log.d(TAG, "[Step4] resp=$sessionRespBody")

        if (sessionRespBody.isNullOrBlank()) throw IllegalStateException("[Step4] 空响应")

        val sessionJson = JSONObject(sessionRespBody)
        val resCode = sessionJson.optString("res_code", "")
        if (resCode != "0") throw IllegalStateException("[Step4] 失败: $resCode ${sessionJson.optString("res_message")}")

        sessionKey = sessionJson.getString("sessionKey")
        sessionSecret = sessionJson.optString("sessionSecret", "")
        val refreshToken = sessionJson.optString("refreshToken", "")
        Log.d(TAG, "[Step4] OK sessionKey=${sessionKey.take(10)}...")

        // ---- 5) sessionKey 换 API accessToken ----
        Log.d(TAG, "[Step5] getAccessTokenBySsKey...")
        val ts = System.currentTimeMillis().toString()
        val sigParams = mapOf("sessionKey" to sessionKey, "timestamp" to ts, "AppKey" to "600100422")
        val sig = md5Signature(sigParams)

        val atResp = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("https://cloud.189.cn/api/open/oauth2/getAccessTokenBySsKey.action?sessionKey=$sessionKey")
                    .header("User-Agent", ua)
                    .header("accept", "application/json;charset=UTF-8")
                    .header("Sign-Type", "1")
                    .header("Signature", sig)
                    .header("Timestamp", ts)
                    .header("AppKey", "600100422")
                    .get().build()
            ).execute()
        }
        val atBody = atResp.body?.string() ?: "{}"
        Log.d(TAG, "[Step5] HTTP ${atResp.code}, resp=$atBody")
        val atJson = JSONObject(atBody)
        accessToken = atJson.optString("accessToken", "")
        if (accessToken.isBlank()) throw IllegalStateException("[Step5] 换取 accessToken 失败: ${atBody.take(200)}")
        Log.d(TAG, "[Step5] OK accessToken=${accessToken.take(10)}...")

        C189AuthProvider.setTokens(
            accessToken = accessToken, sessionKey = sessionKey,
            sessionSecret = sessionSecret, refreshToken = refreshToken,
            expiresIn = System.currentTimeMillis() + 518400000
        )

        Log.d(TAG, "========== loginByPassword OK ==========")
        "ok"
    }

    suspend fun refreshAccessToken(): Boolean {
        try {
            val rt = C189AuthProvider.refreshToken
            if (rt.isNotBlank()) {
                val resp = executeRequestOrNull(
                    Request.Builder()
                        .url("https://open.e.189.cn/api/oauth2/refreshToken.do")
                        .header("User-Agent", C189AuthProvider.userAgent)
                        .post("clientId=8025431004&refreshToken=$rt&grantType=refresh_token&format=json"
                            .toRequestBody(formUrlEncoded))
                        .build()
                )
                if (resp != null) {
                    val json = JSONObject(resp)
                    val newAt = json.optString("accessToken", "")
                    if (newAt.isNotBlank()) {
                        accessToken = newAt
                        C189AuthProvider.accessToken = newAt
                        C189AuthProvider.refreshToken = json.optString("refreshToken", rt)
                        C189AuthProvider.expiresIn = System.currentTimeMillis() + 518400000

                        // 关键：用新 accessToken 刷新 sessionKey/sessionSecret
                        tryRefreshSessionViaAccessToken(newAt)
                        Log.d(TAG, "refreshAccessToken: 通过 refreshToken 成功")
                        return true
                    }
                }
            }

            // refreshToken 失败 → 尝试用 sessionKey 重新换取 accessToken
            val sk = sessionKey.ifBlank { C189AuthProvider.sessionKey }
            if (sk.isNotBlank()) {
                val ts = System.currentTimeMillis().toString()
                val sigParams = mapOf("sessionKey" to sk, "timestamp" to ts, "AppKey" to "600100422")
                val sig = md5Signature(sigParams)

                val resp = withContext(Dispatchers.IO) {
                    client.newCall(
                        Request.Builder()
                            .url("https://cloud.189.cn/api/open/oauth2/getAccessTokenBySsKey.action?sessionKey=$sk")
                            .header("User-Agent", C189AuthProvider.userAgent)
                            .header("accept", "application/json;charset=UTF-8")
                            .header("Sign-Type", "1")
                            .header("Signature", sig)
                            .header("Timestamp", ts)
                            .header("AppKey", "600100422")
                            .get().build()
                    ).execute()
                }
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val newAt = json.optString("accessToken", "")
                if (newAt.isNotBlank()) {
                    accessToken = newAt
                    C189AuthProvider.accessToken = newAt
                    C189AuthProvider.expiresIn = System.currentTimeMillis() + 518400000
                    Log.d(TAG, "refreshAccessToken: 通过 sessionKey 成功")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshAccessToken failed", e)
        }
        return false
    }

    /** 用 accessToken 通过 getSessionForPC 刷新 sessionKey/sessionSecret */
    private suspend fun tryRefreshSessionViaAccessToken(at: String) {
        try {
            val resp = executeRequestOrNull(
                Request.Builder()
                    .url("https://api.cloud.189.cn/getSessionForPC.action?" +
                            "appId=8025431004&clientType=TELEPC&version=6.2" +
                            "&channelId=web_cloud.189.cn" +
                            "&rand=${System.currentTimeMillis()}&accessToken=$at")
                    .header("User-Agent", "Android")
                    .header("accept", "application/json;charset=UTF-8")
                    .header("sign-type", "1")
                    .post("".toRequestBody(null))
                    .build()
            ) ?: return
            Log.d(TAG, "tryRefreshSessionViaAccessToken resp=$resp")
            val json = JSONObject(resp)
            // getSessionForPC?accessToken= 返回格式与 ?redirectURL= 不同
            // 有 sessionKey 时 res_code 不为 0（可能是 -1 表示无此字段）
            val resCode = json.optInt("res_code", -1)
            if (resCode == 0) {
                // res_code=0 表示返回的是普通成功响应，不含 sessionKey
                // 回退到 loginByOpen189AccessToken
                val loginResp = executeRequestOrNull(
                    Request.Builder()
                        .url("https://api.cloud.189.cn/loginByOpen189AccessToken.action?accessToken=$at")
                        .header("User-Agent", "Android")
                        .header("accept", "application/json;charset=UTF-8")
                        .header("sign-type", "1")
                        .get().build()
                )
                if (loginResp != null) {
                    val loginJson = JSONObject(loginResp)
                    val newSk = loginJson.optString("sessionKey", "")
                    if (newSk.isNotBlank()) {
                        val newSs = loginJson.optString("sessionSecret", "")
                        sessionKey = newSk
                        sessionSecret = newSs
                        C189AuthProvider.sessionKey = newSk
                        C189AuthProvider.sessionSecret = newSs
                        Log.d(TAG, "tryRefreshSessionViaAccessToken(fallback): 刷新 sessionKey 成功")
                    }
                }
                return
            }
            // res_code != 0 → 响应中包含 sessionKey
            val newSk = json.optString("sessionKey", "")
            if (newSk.isNotBlank()) {
                val newSs = json.optString("sessionSecret", "")
                sessionKey = newSk
                sessionSecret = newSs
                C189AuthProvider.sessionKey = newSk
                C189AuthProvider.sessionSecret = newSs
                Log.d(TAG, "tryRefreshSessionViaAccessToken: 刷新 sessionKey 成功")
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryRefreshSessionViaAccessToken failed", e)
        }
    }

    fun getSessionCookie(): String = buildString {
        if (sessionKey.isNotBlank()) append("sessionKey=$sessionKey; ")
        if (sessionSecret.isNotBlank()) append("sessionSecret=$sessionSecret; ")
        if (accessToken.isNotBlank()) append("accessToken=$accessToken")
    }

    fun isLoggedIn(): Boolean = accessToken.isNotBlank() && sessionKey.isNotBlank()

    /** 保存 GlobalCookieJar 中所有 cloud.189.cn 相关 Cookie 为字符串 */
    fun saveCookies(): String {
        val sb = StringBuilder()
        for (host in listOf("cloud.189.cn", "api.cloud.189.cn", "m.cloud.189.cn", "open.e.189.cn")) {
            val cookies = GlobalCookieJar.loadForRequest(okhttp3.HttpUrl.Builder().scheme("https").host(host).build())
            if (cookies.isNotEmpty()) {
                sb.append(cookies.joinToString(";") { "${it.name}=${it.value}" })
                sb.append(";")
            }
        }
        return sb.toString()
    }

    /** 恢复 Cookie 到 GlobalCookieJar（app 重启后调用） */
    fun restoreCookies(cookies: String) {
        if (cookies.isBlank()) return
        for (part in cookies.split(";")) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) {
                    for (host in listOf("cloud.189.cn", "api.cloud.189.cn", "m.cloud.189.cn", "open.e.189.cn")) {
                        GlobalCookieJar.setCookie(host, name, value)
                    }
                }
            }
        }
    }

    // ==================== 文件操作 API ====================

    suspend fun listFiles(
        folderId: String = "-11", pageNum: Int = 1, pageSize: Int = 60,
        orderBy: String = "lastOpTime", descending: Boolean = true
    ): Result<C189ListResult> = runCatching {
        val params = linkedMapOf(
            "folderId" to folderId,
            "pageNum" to pageNum.toString(),
            "pageSize" to pageSize.toString(),
            "mediaType" to "0",
            "iconOption" to "5",
            "orderBy" to orderBy,
            "descending" to descending.toString()
        )
        // 直接用 openApi GET 请求（cloud.189.cn/api/open/），与 openApiPost 同样的 MD5 签名
        val json = openApiGet("file/listFiles.action", params)
        Log.d(TAG, "listFiles resp: $json")

        // 尝试两种响应格式: "data" 数组 或 "fileListAO"
        val fileListAO = json.optJSONObject("fileListAO")
        val items = if (fileListAO != null) {
            val folders = fileListAO.optJSONArray("folderList") ?: JSONArray()
            val files = fileListAO.optJSONArray("fileList") ?: JSONArray()
            val result = mutableListOf<C189FileItem>()

            // 文件夹（isDir = true, 缩略图无）
            for (i in 0 until folders.length()) {
                val f = folders.getJSONObject(i)
                result.add(C189FileItem(
                    id = f.optString("id", ""),
                    name = f.optString("name", ""),
                    isDir = true,
                    size = f.optLong("size", 0),
                    lastOpTime = f.optString("lastOpTime", ""),
                    createDate = f.optString("createDate", ""),
                    fileCount = f.optInt("fileCount", 0),
                    folderSize = f.optLong("fileListSize", 0),
                    mediaType = -1,
                    thumbnailUrl = null
                ))
            }

            // 文件（isDir = false, 缩略图取 icon 对象）
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val icon = f.optJSONObject("icon")
                result.add(C189FileItem(
                    id = f.optString("id", ""),
                    name = f.optString("name", ""),
                    isDir = false,
                    size = f.optLong("size", 0),
                    lastOpTime = f.optString("lastOpTime", ""),
                    createDate = f.optString("createDate", ""),
                    fileCount = 0,
                    mediaType = f.optInt("mediaType", -1),
                    thumbnailUrl = icon?.optString("smallUrl", null) ?: icon?.optString("largeUrl", null)
                ))
            }
            result
        } else {
            val itemsArray = json.optJSONArray("data") ?: JSONArray()
            (0 until itemsArray.length()).map { i ->
                val item = itemsArray.getJSONObject(i)
                C189FileItem(
                    id = item.optString("id", ""),
                    name = item.optString("name", ""),
                    isDir = item.optBoolean("isDir", false),
                    size = item.optLong("size", 0),
                    lastOpTime = item.optString("lastOpTime", ""),
                    createDate = item.optString("createDate", ""),
                    fileCount = item.optInt("fileCount", 0),
                    folderSize = item.optLong("fileListSize", 0),
                    mediaType = item.optInt("mediaType", -1),
                    thumbnailUrl = item.optString("thumbnailUrl", null)
                )
            }
        }
        C189ListResult(items, fileListAO?.optInt("count", 0) ?: json.optInt("totalCount", 0))
    }

    suspend fun getVideoPlayUrl(fileId: String): Result<String> = runCatching {
        // cloud.189.cn/api/portal/getNewVlcVideoPlayUrl.action — Cookie 认证，无需签名
        val resp = executeRequestAndGetResponse(
            Request.Builder()
                .url("https://cloud.189.cn/api/portal/getNewVlcVideoPlayUrl.action?fileId=$fileId&type=2")
                .get()
                .header("User-Agent", "Android")
                .header("accept", "application/json;charset=UTF-8")
                .header("sign-type", "1")
                .build()
        )
        val body = resp.body?.string() ?: throw IllegalStateException("Empty response")
        Log.d(TAG, "getVideoPlayUrl resp: ${body.take(300)}")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${body.take(200)}")
        val json = JSONObject(body)
        json.optJSONObject("normal")?.optString("url", "")
            ?: json.optString("playUrl", "")
            ?: json.optString("url", "")
    }

    suspend fun getDownloadUrl(fileId: String): Result<String> = runCatching {
        // cloud.189.cn/api/open/file/getFileDownloadUrl.action — Cookie + sign-type 认证
        val resp = executeRequestAndGetResponse(
            Request.Builder()
                .url("https://cloud.189.cn/api/open/file/getFileDownloadUrl.action?fileId=$fileId")
                .get()
                .header("User-Agent", "Android")
                .header("accept", "application/json;charset=UTF-8")
                .header("sign-type", "1")
                .build()
        )
        val body = resp.body?.string() ?: throw IllegalStateException("Empty response")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${body.take(200)}")
        JSONObject(body).optString("fileDownloadUrl", "")
    }

    suspend fun createFolder(parentFolderId: String, folderName: String): Result<Boolean> = runCatching {
        val json = openApiPost("file/createFolder.action", mapOf(
            "parentFolderId" to parentFolderId, "folderName" to folderName
        ))
        json.optBoolean("success", false)
    }

    suspend fun renameFile(fileId: String, newName: String): Result<Boolean> = runCatching {
        val json = openApiPost("file/renameFile.action", mapOf(
            "fileId" to fileId, "destFileName" to newName
        ))
        json.optBoolean("success", false)
    }

    suspend fun deleteFiles(fileIds: List<String>): Result<Boolean> = runCatching {
        val json = openApiPost("file/deleteFiles.action", mapOf(
            "fileIds" to fileIds.joinToString(",")
        ))
        json.optBoolean("success", false)
    }

    suspend fun moveFiles(fileIds: List<String>, targetFolderId: String): Result<Boolean> = runCatching {
        val json = openApiPost("file/moveFiles.action", mapOf(
            "fileIds" to fileIds.joinToString(","), "targetFolderId" to targetFolderId
        ))
        json.optBoolean("success", false)
    }
}
