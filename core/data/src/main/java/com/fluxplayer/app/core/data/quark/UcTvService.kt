package com.fluxplayer.app.core.data.quark

import android.content.Context
import android.util.Base64
import com.fluxplayer.app.core.data.CloudHttpClient
import com.fluxplayer.app.core.data.security.getSecret
import com.fluxplayer.app.core.data.security.putSecret
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class UcTvService(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("uc", Context.MODE_PRIVATE)
    private val client = CloudHttpClient.DEFAULT.newBuilder().followRedirects(false).followSslRedirects(false).build()

    data class Authorization(val device: String, val queryToken: String, val image: ByteArray, val expiresAt: Long, val owner: String, val revision: String)

    companion object {
        private const val CLIENT_ID = "5acf882d27b74502b7040b0c65519aa7"
        private const val SIGN_KEY = "l3srvtd7p42l0d0x1u8d7yc8ye9kki4d"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1"
        private val refreshMutex = Mutex()
    }

    private fun owner(): String = Regex("(?:^|;\\s*)__uid=([^;]+)").find(preferences.getSecret("uc", "cookie").orEmpty())?.groupValues?.get(1).orEmpty()

    fun isAuthorized(): Boolean = owner().isNotBlank() && preferences.getString("tv_owner", "") == owner() &&
        !preferences.getSecret("uc", "tv_refresh_token").isNullOrBlank()

    fun clearAuthorization() {
        preferences.edit().remove("tv_owner").remove("tv_device").remove("tv_expires_at")
            .remove("tv_access_token").remove("tv_refresh_token").putString("tv_generation", UUID.randomUUID().toString()).apply()
    }

    private fun parameters(device: String): JSONObject = JSONObject()
        .put("req_id", md5("$device${System.currentTimeMillis()}"))
        .put("app_ver", "1.7.2.2").put("device_id", device).put("device_brand", "Xiaomi").put("platform", "tv")
        .put("device_name", "M2004J7AC").put("device_model", "M2004J7AC").put("build_device", "M2004J7AC")
        .put("build_product", "M2004J7AC").put("device_gpu", "Adreno (TM) 550").put("activity_rect", "{}").put("channel", "UCTVOFFICIALWEB")

    private suspend fun get(path: String, device: String, query: Map<String, String>, access: String = ""): JSONObject {
        val timestamp = System.currentTimeMillis().toString()
        val params = parameters(device)
        val url = ("https://open-api-drive.uc.cn$path").toHttpUrl().newBuilder()
        params.keys().forEach { key -> url.addQueryParameter(key, params.getString(key)) }
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        if (access.isNotBlank()) url.addQueryParameter("access_token", access)
        val signature = MessageDigest.getInstance("SHA-256").digest("GET&$path&$timestamp&$SIGN_KEY".toByteArray()).hex()
        return request(
            Request.Builder().url(url.build()).header("User-Agent", USER_AGENT)
                .header("x-pan-tm", timestamp).header("x-pan-token", signature).header("x-pan-client-id", CLIENT_ID).build(),
        )
    }

    suspend fun beginAuthorization(): Authorization {
        val expected = owner()
        check(expected.isNotBlank()) { "请先完成 UC 网页登录" }
        val device = UUID.randomUUID().toString().replace("-", "")
        val revision = UUID.randomUUID().toString()
        preferences.edit().putString("tv_generation", revision).apply()
        val response = checked(get("/oauth/authorize", device, mapOf("auth_type" to "code", "client_id" to CLIENT_ID, "scope" to "netdisk", "qrcode" to "1", "qr_width" to "460", "qr_height" to "460")))
        check(owner() == expected) { "UC 账号已变化，请重新授权" }
        val raw = response.getString("qr_data").substringAfter("base64,", response.getString("qr_data"))
        check(raw.length <= 512 * 1024) { "UC 二维码响应过大" }
        val image = Base64.decode(raw, Base64.DEFAULT)
        check(image.size > 24) { "UC 二维码无效" }
        return Authorization(device, response.getString("query_token"), image, System.currentTimeMillis() + response.optLong("expires_in", 300).coerceIn(1, 600) * 1000, expected, revision)
    }

    suspend fun pollAuthorization(authorization: Authorization): Boolean {
        check(owner() == authorization.owner && owner().isNotBlank()) { "UC 账号已变化，请重新授权" }
        check(preferences.getString("tv_generation", "") == authorization.revision) { "UC 授权已取消，请重新扫码" }
        check(System.currentTimeMillis() < authorization.expiresAt) { "二维码已过期，请重新获取" }
        val response = get("/oauth/code", authorization.device, mapOf("client_id" to CLIENT_ID, "scope" to "netdisk", "query_token" to authorization.queryToken))
        if (response.optInt("errno") == 11003) return false
        val code = checked(response).optString("code")
        if (code.isBlank()) return false
        val tokens = exchange(authorization.device, code = code)
        val access = tokens.getString("access_token")
        checked(get("/user", authorization.device, mapOf("method" to "user_info"), access))
        check(owner() == authorization.owner) { "UC 账号已变化，请重新授权" }
        save(tokens, authorization.device, authorization.owner, authorization.revision)
        return true
    }

    private suspend fun exchange(device: String, code: String = "", refresh: String = ""): JSONObject {
        val body = parameters(device)
        if (code.isNotBlank()) body.put("code", code) else body.put("refresh_token", refresh)
        val response = request(
            Request.Builder().url("https://api.extscreen.com/ucdrive/token")
                .header("Accept", "application/json").header("User-Agent", USER_AGENT)
                .post(body.toString().toRequestBody("application/json;charset=utf-8".toMediaType())).build(),
        )
        check(response.optInt("code") == 200) { "UC TV 授权交换失败，请重新扫码" }
        val data = checked(response.getJSONObject("data"))
        check(data.optString("access_token").isNotBlank() && data.optLong("expires_in") in 1..31_622_400) { "UC TV 授权信息不完整" }
        if (data.optString("refresh_token").isBlank()) data.put("refresh_token", refresh)
        check(data.optString("refresh_token").isNotBlank()) { "UC TV 未返回续期凭据" }
        return data
    }

    private fun save(tokens: JSONObject, device: String, expected: String, revision: String) {
        check(owner() == expected && expected.isNotBlank()) { "UC 账号已变化，请重新授权" }
        check(preferences.getString("tv_generation", "") == revision) { "UC TV 授权已变化，请重新操作" }
        preferences.edit().putString("tv_owner", expected).putString("tv_device", device)
            .putSecret("uc", "tv_access_token", tokens.getString("access_token"))
            .putSecret("uc", "tv_refresh_token", tokens.getString("refresh_token"))
            .putLong("tv_expires_at", System.currentTimeMillis() + tokens.getLong("expires_in") * 1000).apply()
    }

    private suspend fun access(): Pair<String, String> = refreshMutex.withLock {
        check(isAuthorized()) { "请在 UC 网盘菜单中完成 TV 播放授权" }
        val expected = owner()
        val revision = preferences.getString("tv_generation", "").orEmpty()
        val device = preferences.getString("tv_device", "").orEmpty()
        var access = preferences.getSecret("uc", "tv_access_token").orEmpty()
        if (access.isBlank() || preferences.getLong("tv_expires_at", 0) <= System.currentTimeMillis() + 60_000) {
            val refresh = preferences.getSecret("uc", "tv_refresh_token").orEmpty()
            val response = exchange(device, refresh = refresh)
            save(response, device, expected, revision)
            access = response.getString("access_token")
        }
        device to access
    }

    suspend fun playback(fid: String): List<Pair<String, String>> {
        val expected = owner()
        val (device, access) = access()
        val response = checked(get("/file", device, mapOf("method" to "streaming", "group_by" to "source", "fid" to fid, "resolution" to "low,normal,high,super,2k,4k", "support" to "dolby_vision"), access))
        check(owner() == expected) { "UC 账号已变化，请重新播放" }
        val data = response.getJSONObject("data")
        check(!data.has("fid") || data.optString("fid") == fid) { "UC 返回的视频与所选文件不一致" }
        val array = data.getJSONArray("video_info")
        val ranks = listOf("low", "normal", "high", "super", "2k", "4k")
        val list = (0 until array.length()).map { array.getJSONObject(it) }.filter {
            (!it.has("accessable") || it.optInt("accessable") == 1) &&
                (!it.has("fid") || it.optString("fid") == fid) && it.optString("url").startsWith("http")
        }.sortedByDescending { ranks.indexOf(it.optString("resolution")) }
        check(list.isNotEmpty()) { "UC TV 暂无可用画质，请稍后重试" }
        return list.map { it.getString("url") to it.optString("resolution") }
    }

    suspend fun download(fid: String, expectedSize: Long): String {
        val expected = owner()
        val (device, access) = access()
        val data = checked(get("/file", device, mapOf("method" to "download", "group_by" to "source", "fid" to fid), access)).getJSONObject("data")
        check(owner() == expected && data.optString("fid") == fid) { "UC 返回的下载文件与所选文件不一致" }
        check(expectedSize <= 0 || data.optLong("size", -1) == expectedSize) { "UC 返回的原文件大小与列表不一致" }
        val url = data.getString("download_url").also { check(it.startsWith("http://") || it.startsWith("https://")) { "UC 下载地址无效" } }
        val size = data.getLong("size")
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Range", "bytes=0-1").header("Accept-Encoding", "identity").build()
            client.newCall(request).execute().use { response ->
                val total = when (response.code) {
                    206 -> Regex("bytes 0-\\d+/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    200 -> response.header("Content-Length")?.toLongOrNull()
                    416 -> if (response.header("Content-Range") == "bytes */0") 0L else null
                    else -> null
                }
                check(total != null && total == size && (expectedSize <= 0 || total == expectedSize)) { "UC 返回的实际内容不是所选原文件，已停止下载" }
                check(response.header("Content-Encoding").orEmpty() in setOf("", "identity")) { "UC 原文件长度无法确认" }
            }
        }
        return url
    }

    private suspend fun request(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            check(response.code in listOf(200, 400)) { "UC TV 请求失败（HTTP ${response.code}）" }
            JSONObject(response.body?.string() ?: error("UC TV 响应为空"))
        }
    }

    private fun checked(response: JSONObject): JSONObject {
        check(response.optInt("status", -1) in listOf(0, 200) && response.optInt("errno") == 0) { "UC TV 授权已失效或请求失败，请重新扫码" }
        return response
    }
    private fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray()).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
