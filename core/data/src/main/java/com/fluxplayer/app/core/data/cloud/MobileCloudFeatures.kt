package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject

internal object MobileCloudProtocol {
    private const val SHARE_BASE = "https://share-kd-njs.yun.139.com/yun-share/richlifeApp/devapp"
    private const val FAMILY_BASE = "https://yun.139.com/orchestration/familyCloud-rebuild"
    private const val WEB_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val key = "PVGDwmcvfs1uV3d1".toByteArray()

    fun calculateSign(body: String, date: String, nonce: String): String {
        val safe = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.!~*'()"
        val encoded = body.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            val value = byte.toInt() and 255
            if (value.toChar() in safe) value.toChar().toString() else "%%%02X".format(value)
        }
        val sorted = encoded.toCharArray().sorted().joinToString("")
        fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return md5(md5(sorted.toByteArray().toByteString().base64()) + md5("$date:$nonce")).uppercase(Locale.US)
    }

    fun signature(body: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        val nonce = (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return "$date,$nonce,${calculateSign(body, date, nonce)}"
    }

    fun cloudHeaders(plain: String, family: Boolean = false, domain: Boolean = false): Map<String, String> = buildMap {
        put("Authorization", Yun139AuthProvider.authorization)
        put("mcloud-sign", signature(plain))
        put("x-yun-channel-source", "10000034")
        put("x-yun-app-channel", "10000034")
        put("x-huawei-channelSrc", if (family) "10230043" else "10000034")
        put("mcloud-version", "7.17.9")
        put("mcloud-client", "10701")
        put("mcloud-channel", "1000101")
        put("mcloud-route", "001")
        put("x-yun-module-type", "100")
        put("x-yun-api-version", "v1")
        put("x-yun-svc-type", if (family) "2" else "1")
        put("x-SvcType", if (family) "2" else "1")
        if (family && domain) put("mcloud-userid-flag", "1")
        put("caller", "web")
        put("x-inner-ntwk", "2")
        put("CMS-DEVICE", "default")
        put("x-m4c-src", "10002")
        put("x-m4c-caller", "PC")
        put("X-Deviceinfo", "||9|7.17.9|chrome|116.0.0.0|2cdaf7ada9e353c70eba99092e177991||windows 10||zh-CN|||")
        put("x-yun-client-info", "||9|7.17.9|chrome|116.0.0.0|2cdaf7ada9e353c70eba99092e177991||windows 10||zh-CN|||dW5kZWZpbmVk||")
        put("INNER-HCY-ROUTER-HTTPS", "1")
        put("X-Requested-With", "mark.via")
        put("User-Agent", WEB_AGENT)
        put("Origin", "https://yun.139.com")
        put("Referer", "https://yun.139.com/")
        put("Accept", "application/json, text/plain, */*")
        if (Yun139AuthProvider.cookie.isNotBlank()) {
            put("Cookie", Yun139AuthProvider.cookie)
            Regex("(?:^|;\\s*)skey=([^;]+)").find(Yun139AuthProvider.cookie)?.groupValues?.get(1)?.let { put("mcloud-skey", it) }
        }
    }

    fun data(json: JSONObject): JSONObject {
        val data = json.optJSONObject("data") ?: json
        return listOf("getOutLinkInfoResp", "getOutLinkGeneralResp", "getOutLinkRes").firstNotNullOfOrNull { data.optJSONObject(it) } ?: data
    }

    fun checkResponse(json: JSONObject) {
        listOf(json, json.optJSONObject("data")?.optJSONObject("result")).filterNotNull().forEach { value ->
            val code = value.optString("resultCode", value.optString("code", ""))
            check(value.optBoolean("success", true) && code in setOf("", "0", "0000", "200", "S_OK")) {
                value.optString("resultDesc", value.optString("desc", value.optString("message", "移动请求失败：$code")))
            }
        }
    }

    fun encrypt(plain: String, iv: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        return (iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))).toByteString().base64()
    }

    fun decrypt(raw: String): String {
        val bytes = raw.trim().decodeBase64()?.toByteArray() ?: error("移动响应无法解码")
        check(bytes.size > 16) { "移动响应不完整" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(bytes.copyOfRange(0, 16))) }
        val decoded = cipher.doFinal(bytes.copyOfRange(16, bytes.size))
        val expanded = if (decoded.size > 2 && decoded[0] == 0x1f.toByte() && decoded[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(decoded)).use { it.readBytes() } else decoded
        return String(expanded, Charsets.UTF_8)
    }

    suspend fun request(path: String, body: JSONObject, client: okhttp3.OkHttpClient, anonymous: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val family = path.startsWith("family/")
        val plain = body.toString()
        val request = Request.Builder().url(if (family) "$FAMILY_BASE/${path.removePrefix("family/")}" else "$SHARE_BASE/${path.removePrefix("share/")}")
            .post((if (family) plain else encrypt(plain)).toRequestBody("application/json;charset=UTF-8".toMediaType()))
        val headers = if (family) cloudHeaders(plain, true, body.optJSONObject("commonAccountInfo")?.has("userDomainId") == true) else buildMap {
            put("hcy-cool-flag", "1")
            put("x-deviceinfo", "||3|12.27.0|||||chrome 150.0.0.0|360X444|zh-cn|||")
            put("x-huawei-channelsrc", "10245500")
            put("x-mm-source", "0002")
            put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
            put("Origin", "https://yun.139.com")
            put("Referer", "https://yun.139.com/")
            put("Accept", "application/json, text/plain, */*")
            if (!anonymous) {
                put("mcloud-sign", signature(plain))
                put("Authorization", Yun139AuthProvider.authorization)
            }
        }
        headers.forEach { (name, value) -> request.header(name, value) }
        client.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful) { "移动云盘请求失败（HTTP ${response.code}）" }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(if (raw.trimStart().startsWith("{")) raw else decrypt(raw))
            checkResponse(json)
            json
        }
    }
}

internal suspend fun CloudProviderFeatures.mobileListShare(session: CloudShareSession, parent: String): List<CloudRemoteFile> {
    val files = mutableListOf<CloudRemoteFile>()
    val seen = mutableSetOf<String>()
    for (begin in 1..20000 step 200) {
        val value = MobileCloudProtocol.data(
            MobileCloudProtocol.request(
                "share/IOutLink/getOutLinkInfoV6",
                jsonObject(
                    "getOutLinkInfoReq" to jsonObject(
                        "account" to "", "linkID" to session.id, "passwd" to session.password, "caSrt" to 1, "coSrt" to 1,
                        "srtDr" to 0, "bNum" to begin, "eNum" to begin + 199, "pCaID" to parent,
                    ),
                ),
                httpClient,
                anonymous = true,
            ),
        )
        check(value.has("caLst") || value.has("coLst")) { "移动分享列表不完整，请检查提取码" }
        val batch = value.objects("caLst").map { CloudRemoteFile(it.requiredString("caID"), it.requiredString("caName"), true, parentId = parent) } +
            value.objects("coLst").map { CloudRemoteFile(it.requiredString("coID"), it.requiredString("coName"), it.optInt("coType") == 2, it.optLong("coSize"), parentId = parent) }
        val fresh = batch.filter { seen.add(it.id) }
        files.addAll(fresh)
        if (batch.size < 200) return files
        check(fresh.isNotEmpty()) { "移动分享分页重复，请重试" }
    }
    error("分享文件过多，请进入子目录后重试")
}

internal suspend fun CloudProviderFeatures.mobileSaveShare(session: CloudShareSession, files: List<CloudRemoteFile>, target: String) {
    val account = com.fluxplayer.app.core.data.yun139.Yun139AuthProvider.phoneNumber
    val response = MobileCloudProtocol.data(
        MobileCloudProtocol.request(
            "share/IBatchOprTask/createOuterLinkBatchOprTask",
            jsonObject(
                "createOuterLinkBatchOprTaskReq" to jsonObject(
                    "msisdn" to account, "ownerAccount" to "", "taskType" to 1, "linkID" to session.id, "needPassword" to true,
                    "taskInfo" to jsonObject(
                        "contentInfoList" to files.filterNot { it.directory }.map { "/${it.id}" }, "catalogInfoList" to files.filter { it.directory }.map { it.id },
                        "newCatalogID" to target, "linkID" to session.id, "newCatalogName" to "FluxPlayer转存", "needPassword" to true,
                    ),
                ),
                "commonAccountInfo" to mobileAccount(),
            ),
            httpClient,
        ),
    )
    val taskId = response.requiredString("taskID")
    repeat(40) {
        val result = MobileCloudProtocol.data(
            MobileCloudProtocol.request(
                "share/IBatchOprTask/queryBatchOprTaskDetail",
                jsonObject(
                    "queryBatchOprTaskDetailReq" to jsonObject(
                        "taskID" to taskId, "msisdn" to account, "commonAccountInfo" to mobileAccount(),
                    ),
                ),
                httpClient,
            ),
        )
        val task = result.getJSONObject("batchOprTask")
        val status = task.optString("taskStatus").lowercase(Locale.US)
        check(status !in setOf("3", "4", "5", "failed", "error")) { "移动转存任务失败" }
        if ((status == "2" && task.optInt("progress") >= 100) || status in setOf("success", "succeed")) {
            val entries = result.optJSONObject("contentList")?.objects("idRspInfo").orEmpty()
            check(entries.all { entry -> entry.optString("reason") in setOf("0", "0000") }) { "部分文件转存失败，请刷新目标目录确认" }
            check(files.filterNot { it.directory }.all { file -> entries.any { it.optString("srcId") == file.id && it.optString("rstId").isNotBlank() } }) { "移动未确认所选文件转存结果，请刷新目标目录确认" }
            return
        }
        delay(800)
    }
    error("移动转存任务超时，请刷新目标目录确认")
}

internal suspend fun CloudProviderFeatures.mobileOpenShare(text: String, password: String): CloudShareSession {
    val link = Regex("https?://(?:yun|caiyun)\\.139\\.com/[^\\s]+", RegexOption.IGNORE_CASE).find(text)?.value
        ?: error("请输入有效的移动云盘分享链接")
    val patterns = listOf("/w/i/([A-Za-z0-9_-]+)", "/m/i\\?([A-Za-z0-9]+)(?:&|$)", "[?&]linkID=([A-Za-z0-9_-]+)", "/(?:s|t|w)/([A-Za-z0-9_-]+)")
    val id = patterns.firstNotNullOfOrNull { Regex(it, RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1) }
        ?: Regex("/([A-Za-z0-9]{8,})(?:[?#]|$)").find(link)?.groupValues?.get(1)
        ?: error("移动分享链接缺少链接 ID")
    val urlPassword = Regex("[?&](?:pass|pwd|password|passcode)=([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1).orEmpty()
    val explicit = password.ifBlank { urlPassword }.ifBlank {
        Regex("(?:提取码|访问码|密码)[:：\\s]*([A-Za-z0-9]+)").find(text)?.groupValues?.get(1).orEmpty()
    }
    val general = MobileCloudProtocol.data(MobileCloudProtocol.request(
        "share/IOutLink/getOutLinkGeneral",
        jsonObject("getOutLinkGeneralReq" to jsonObject("linkID" to id, "isPasswd" to 1, "account" to "")),
        httpClient, anonymous = true,
    )).objects("outLinkGeneral").firstOrNull()
    return CloudShareSession(id, explicit.ifBlank { general?.optString("passwd").orEmpty().ifBlank { general?.optString("password").orEmpty() } }, rootId = "root")
}
