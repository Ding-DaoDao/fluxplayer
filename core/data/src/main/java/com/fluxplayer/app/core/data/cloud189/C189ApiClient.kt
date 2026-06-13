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
import java.net.URLDecoder
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
        return JSONObject(body)
    }

    /** POST — session HMAC-SHA1 签名 (api.cloud.189.cn) */
    private suspend fun signedPost(
        path: String, formParams: Map<String, String> = emptyMap()
    ): JSONObject {
        val sk = sessionKey.ifBlank { C189AuthProvider.sessionKey }
        val ss = sessionSecret.ifBlank { C189AuthProvider.sessionSecret }
        if (sk.isBlank()) throw IllegalStateException("未登录: sessionKey 为空")

        val date = buildDateHeader()
        val requestUri = path.substringBefore("?")
        val sigData = "SessionKey=$sk&Operate=POST&RequestURI=$requestUri&Date=$date"
        val sig = hmacSha1(ss.toByteArray(), sigData)

        val formBody = formParams.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

        val request = Request.Builder()
            .url("$API_BASE$path")
            .post(formBody.toRequestBody(formUrlEncoded))
            .header("User-Agent", "Android")
            .header("accept", "application/json;charset=UTF-8")
            .header("sign-type", "1")
            .header("SessionKey", sk)
            .header("Signature", sig)
            .header("Date", date)
            .build()

        Log.d(TAG, "signedPost: $API_BASE$path, form=$formBody")

        val resp = executeRequestAndGetResponse(request)
        val body = resp.body?.string() ?: throw IllegalStateException("Empty response")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${body.take(200)}")
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
            // token/session 过期 → 尝试刷新
            val canRetry = respBody.contains("InvalidAccessToken") || respBody.contains("InvalidSessionKey")
            if (retry && canRetry) {
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

        // ---- 1) 获取加密公钥 ----
        val encryptRespBody = executeRequestOrNull(
            Request.Builder()
                .url("https://open.e.189.cn/api/logbox/config/encryptConf.do")
                .header("User-Agent", ua)
                .post("appId=$appKey".toRequestBody(formUrlEncoded))
                .build()
        )
        if (encryptRespBody.isNullOrBlank()) throw IllegalStateException("[Step1] 获取加密配置失败: 空响应")

        val encryptJson = JSONObject(encryptRespBody)
        val encryptResult = encryptJson.optInt("result", -1)
        if (encryptResult != 0) throw IllegalStateException("[Step1] 加密配置失败: $encryptResult")
        val data = encryptJson.getJSONObject("data")
        val pubKey = data.getString("pubKey")
        val pre = data.getString("pre")

        // ---- 2) 获取登录页 lt / reqId / paramId ----
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

        val html = withContext(Dispatchers.IO) {
            val resp = noRedirectClient.newCall(
                Request.Builder().url(location).header("User-Agent", ua).get().build()
            ).execute()
            val h = resp.body?.string()
            resp.close()
            h ?: throw IllegalStateException("[Step2] HTML 为空")
        }

        val lt = Regex("""lt\s*=\s*"([A-Fa-f0-9]+)"""").find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
        val reqId = Regex("""reqId\s*=\s*"([A-Fa-f0-9]+)"""").find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
        val paramId = Regex("""paramId\s*=\s*"([A-Fa-f0-9]+)"""").find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
        if (lt.isBlank()) throw IllegalStateException("[Step2] 未获取到 lt")

        // ---- 3) RSA 加密并提交登录 ----
        val rsaPhone = RsaHelper.encryptToRsaHex(phone, pubKey)
        val rsaPassword = RsaHelper.encryptToRsaHex(password, pubKey)

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
        if (loginRespBody.isNullOrBlank()) throw IllegalStateException("[Step3] 登录请求返回空")

        val loginJson = JSONObject(loginRespBody)
        val loginResult = loginJson.optInt("result", -1)
        if (loginResult != 0) throw IllegalStateException("[Step3] 登录失败: ${loginJson.optString("msg")}")
        val toUrl = loginJson.optString("toUrl", "").ifBlank {
            throw IllegalStateException("[Step3] 无 toUrl")
        }

        // ---- 4) 获取 sessionKey / sessionSecret ----
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

        if (sessionRespBody.isNullOrBlank()) throw IllegalStateException("[Step4] 空响应")

        val sessionJson = JSONObject(sessionRespBody)
        val resCode = sessionJson.optString("res_code", "")
        if (resCode != "0") throw IllegalStateException("[Step4] 失败: $resCode ${sessionJson.optString("res_message")}")

        sessionKey = sessionJson.getString("sessionKey")
        sessionSecret = sessionJson.optString("sessionSecret", "")
        val refreshToken = sessionJson.optString("refreshToken", "")

        // ---- 5) sessionKey 换 API accessToken ----
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
        val atJson = JSONObject(atBody)
        accessToken = atJson.optString("accessToken", "")
        if (accessToken.isBlank()) throw IllegalStateException("[Step5] 换取 accessToken 失败: ${atBody.take(200)}")

        C189AuthProvider.setTokens(
            accessToken = accessToken, sessionKey = sessionKey,
            sessionSecret = sessionSecret, refreshToken = refreshToken,
            expiresIn = System.currentTimeMillis() + 518400000
        )

        "ok"
    }

    // ==================== SMS 短信登录 ====================

    /**
     * 获取 SMS 登录用的 RSA 公钥 (GET 无 body, 不同于 PC 版)
     * @return Pair<pubKey, pre>
     */
    private suspend fun encryptConfForSms(): Pair<String, String> {
        val respBody = executeRequestOrNull(
            Request.Builder()
                .url("https://open.e.189.cn/api/logbox/config/encryptConf.do")
                .get()
                .header("User-Agent", C189AuthProvider.userAgent)
                .build()
        ) ?: throw IllegalStateException("获取加密配置失败: 空响应")
        val json = JSONObject(respBody)
        val result = json.optInt("result", -1)
        if (result != 0) throw IllegalStateException("加密配置失败: result=$result")
        val data = json.getJSONObject("data")
        val pubKey = data.getString("pubKey")
        val pre = data.getString("pre")
        return Pair(pubKey, pre)
    }

    /** 解析 query-string 为 Map */
    private fun parseQueryString(qs: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in qs.split("&")) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                map[part.substring(0, idx)] =
                    URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            }
        }
        return map
    }

    /**
     * 发送短信验证码 (SMS 登录第 1-2 步)
     * @param phone 手机号
     * @return 成功返回 "ok", 失败抛异常
     */
    suspend fun sendSmsCode(phone: String): Result<String> = runCatching {
        // 1) 获取 RSA 公钥
        val (pubKey, pre) = encryptConfForSms()

        // 2) RSA 加密手机号
        val rsaPhone = RsaHelper.encryptToRsaHex(phone, pubKey)

        // 3) 发送验证码
        val body = buildString {
            append("apptype=wap")
            append("&appKey=cloud")
            append("&mobile=${URLEncoder.encode("$pre$rsaPhone", "UTF-8")}")
            append("&captchaToken=")
            append("&validateCode=")
            append("&guid=")
            append("&reqId=")
            append("&headerDeviceId=")
        }

        val respBody = executeRequestOrNull(
            Request.Builder()
                .url("https://open.e.189.cn/api/logbox/oauth2/sdk/sendSmsCode.do")
                .post(body.toRequestBody(formUrlEncoded))
                .header("User-Agent", C189AuthProvider.userAgent)
                .build()
        ) ?: throw IllegalStateException("发送验证码失败: 空响应")

        val json = JSONObject(respBody)
        val result = json.optInt("result", -1)
        if (result != 0) {
            throw IllegalStateException("发送验证码失败: ${json.optString("message", "未知错误")}")
        }
        "ok"
    }

    /**
     * 短信验证码登录 (SMS 登录第 3-6 步，一站式完成)
     * @param phone 手机号
     * @param smsCode 验证码
     * @return 成功返回 "ok", 失败抛异常
     */
    suspend fun loginBySms(phone: String, smsCode: String): Result<String> = runCatching {
        // 1) 获取 RSA 公钥
        val (pubKey, pre) = encryptConfForSms()

        // 2) RSA 加密手机号和验证码
        val rsaPhone = RsaHelper.encryptToRsaHex(phone, pubKey)
        val rsaCode = RsaHelper.encryptToRsaHex(smsCode, pubKey)

        // 3) 提交验证码登录
        val loginBody = buildString {
            append("apptype=wap")
            append("&loginType=2")
            append("&dynamicCheck=true")
            append("&appKey=cloud")
            append("&userName=${URLEncoder.encode("$pre$rsaPhone", "UTF-8")}")
            append("&password=${URLEncoder.encode("$pre$rsaCode", "UTF-8")}")
            append("&deviceInfo=")
            append("&jointWay=1|2")
            append("&jointVersion=v3.8.1")
            append("&operator=")
            append("&nwc=")
            append("&nws=2")
            append("&guid=")
            append("&reqId=")
            append("&headerDeviceId=")
        }

        val loginRespBody = executeRequestOrNull(
            Request.Builder()
                .url("https://open.e.189.cn/api/logbox/oauth2/oAuth2SdkLoginByPassword.do")
                .post(loginBody.toRequestBody(formUrlEncoded))
                .header("User-Agent", C189AuthProvider.userAgent)
                .build()
        ) ?: throw IllegalStateException("[SMS Step3] 登录请求返回空")

        val loginJson = try {
            JSONObject(loginRespBody)
        } catch (e: Exception) {
            throw IllegalStateException("[SMS Step3] 响应非 JSON: ${loginRespBody.take(200)}", e)
        }
        val loginResult = loginJson.optInt("result", -1)
        if (loginResult != 0) {
            throw IllegalStateException("[SMS Step3] 登录失败(result=$loginResult): ${loginJson.optString("message", "未知错误")}")
        }

        // 4) 解析 returnParas → XXTEA 解密 paras → 获取 accessToken
        val returnParas = loginJson.optString("returnParas", "")
        if (returnParas.isBlank()) {
            throw IllegalStateException("[SMS Step4] returnParas 为空")
        }

        val returnParams = parseQueryString(returnParas)
        val encryptedParas = returnParams["paras"]
            ?: throw IllegalStateException("[SMS Step4] paras 字段缺失")

        val decryptedPars = try {
            XxteaHelper.decryptParas(encryptedParas)
        } catch (e: Exception) {
            throw IllegalStateException("[SMS Step4] XXTEA 解密失败: ${e.message}", e)
        }

        val parasMap = parseQueryString(decryptedPars)
        val smsAccessToken = parasMap["accessToken"]
            ?: throw IllegalStateException("[SMS Step4] accessToken 缺失")
        val smsRefreshToken = parasMap["refreshToken"] ?: ""
        val userId = parasMap["userId"] ?: ""

        // 5) 用 accessToken 换取 sessionKey (login4MergedClient)
        login4MergedClient(smsAccessToken)

        // 6) 保存 token
        val at = accessToken.ifBlank { smsAccessToken }
        C189AuthProvider.setTokens(
            accessToken = at,
            sessionKey = sessionKey,
            sessionSecret = sessionSecret,
            refreshToken = smsRefreshToken,
            expiresIn = System.currentTimeMillis() + 518400000
        )

        "ok"
    }

    /**
     * 用 accessToken 换取移动端 sessionKey/sessionSecret (SMS 登录内部使用)
     * 调用 api.cloud.189.cn/login4MergedClient.action
     */
    private suspend fun login4MergedClient(at: String) {
        val deviceModel = randomDeviceModel()
        val rand = System.currentTimeMillis().toString()

        val params = linkedMapOf(
            "rand" to rand,
            "accessToken" to at,
            "clientType" to "TELEANDROID",
            "version" to "8.9.0",
            "clientSn" to "",
            "model" to deviceModel,
            "osFamily" to "Android",
            "osVersion" to "29",
            "networkAccessMode" to "WIFI",
            "telecomsOperator" to "460011",
            "channelId" to "nearme"
        )

        val queryStr = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

        val resp = executeRequestAndGetResponse(
            Request.Builder()
                .url("$API_BASE/login4MergedClient.action?$queryStr")
                .get()
                .header("User-Agent", "okhttp/3.12.2")
                .header("accept", "application/json;charset=UTF-8")
                .header("appkey", "600100885")
                .build()
        )

        val body = resp.body?.string()
            ?: throw IllegalStateException("[login4MergedClient] 空响应")

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IllegalStateException("[login4MergedClient] 响应非 JSON: ${body.take(200)}", e)
        }
        val resCode = json.optInt("res_code", -1)
        if (resCode != 0) {
            throw IllegalStateException(
                "[login4MergedClient] 失败: $resCode ${json.optString("res_message", "")}"
            )
        }

        accessToken = json.optString("eAccessToken", at)
        sessionKey = json.getString("sessionKey")
        sessionSecret = json.optString("sessionSecret", "")

        // 同时保存 family 系列 token (家庭云)
        val familySk = json.optString("familySessionKey", "")
        val familySs = json.optString("familySessionSecret", "")
        if (familySk.isNotBlank()) {
            C189AuthProvider.setFamilyTokens(familySk, familySs)
        }

    }

    /** 随机设备型号 (Xiaomi 系列, 来自海阔视界) */
    private fun randomDeviceModel(): String {
        val devices = arrayOf(
            "2312DRAABC", "2312DR AABI", "2312DR AABG", "2310RK86C", "2310RK86I",
            "2311RK78C", "2304FPN6DC", "2306EPN60G", "23026PC78C", "23026PC78I",
            "23013PC75G", "23013PC75I", "24122RKC7C", "24127RK2CC", "24108PN61G",
            "24108PN61I", "24097PN53G", "24097PN53I", "24129PN74C", "24129PN74G",
            "24129PN74I", "2412DRT0AC", "2412DPC0AG", "2412DPC0AI", "2510DRK44C",
            "25102RKBEC", "25087PN36C", "25098PN5AC", "25010PN30G", "25067PYE3C"
        )
        return devices[(Math.random() * devices.size).toInt()]
    }

    // ==================== Token 刷新 ====================

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
        val fileId = fileIds.first()
        val json = signedPost(
            "/moveFile.action",
            mapOf("fileId" to fileId, "destFileName" to "", "destParentFolderId" to targetFolderId)
        )
        json.optBoolean("success", false)
    }
}
