package com.fluxplayer.app.core.data.cloud

import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject

internal class CloudProviderUpload(private val features: CloudProviderFeatures, private val client: OkHttpClient) {
    private suspend fun post(path: String, body: JSONObject) = features.data(features.post(path, body))

    suspend fun upload(parent: String, source: CloudUploadSource): String =
        when (features.provider) {
            "alipan" -> aliyun(parent, source)
            "pan123" -> pan123(parent, source)
            "quark", "uc" -> quark(parent, source)
            "yun139" -> mobile(parent, source)
            "cloud189" -> tianyi(parent, source)
            else -> error("未知网盘")
        }

    private suspend fun aliyun(parent: String, source: CloudUploadSource): String {
        val chunk = maxOf(10L * 1024 * 1024, (source.size + 8999) / 9000)
        val count = maxOf(1, ((source.size + chunk - 1) / chunk).toInt())
        val drive = features.driveId()
        val task = post(
            "/adrive/v2/file/createWithFolders",
            jsonObject(
                "drive_id" to drive, "parent_file_id" to parent, "name" to source.name,
                "type" to "file", "size" to source.size, "check_name_mode" to "refuse", "content_hash_name" to "none",
                "proof_version" to "v1", "part_info_list" to (1..count).map { jsonObject("part_number" to it) },
            ),
        )
        check(!task.optBoolean("exist")) { "已有同名文件，请重命名后上传" }
        val id = task.requiredString("file_id")
        if (!task.optBoolean("rapid_upload")) {
            val uploadId = task.requiredString("upload_id")
            val parts = task.objects("part_info_list").sortedBy { it.optInt("part_number") }
            check(parts.size == count) { "阿里未返回完整上传分段" }
            parts.forEachIndexed { index, part ->
                check(part.optInt("part_number") == index + 1) { "上传分段序号错误" }
                val headers = part.optString("content_type").takeIf { it.isNotBlank() }?.let { mapOf("Content-Type" to it) }.orEmpty()
                source.send(client, part.requiredString("upload_url"), index * chunk, minOf(source.size, (index + 1) * chunk), headers)
            }
            post("/v2/file/complete", jsonObject("drive_id" to drive, "file_id" to id, "upload_id" to uploadId))
        }
        return id
    }

    private suspend fun pan123(parent: String, source: CloudUploadSource): String {
        val pre = post(
            "file/upload_request",
            jsonObject(
                "driveId" to 0,
                "duplicate" to 1,
                "etag" to source.hash("MD5"),
                "fileName" to source.name,
                "parentFileId" to parent.toLong(),
                "size" to source.size,
                "type" to 0,
            ),
        )
        val id = pre.optString("FileId").ifBlank { pre.optJSONObject("Info")?.optString("FileId").orEmpty() }
        check(id.isNotBlank()) { "123 未返回上传文件标识" }
        if (pre.optBoolean("Reuse")) return id
        if (pre.optString("AccessKeyId").isNotBlank() && pre.optString("SecretAccessKey").isNotBlank()) {
            s3(pre, source)
            post("file/upload_complete", jsonObject("fileId" to id))
            return id
        }
        val chunk = 16L * 1024 * 1024
        val count = maxOf(1, ((source.size + chunk - 1) / chunk).toInt())
        val body = jsonObject("StorageNode" to pre.opt("StorageNode"), "bucket" to pre.requiredString("Bucket"), "key" to pre.requiredString("Key"), "uploadId" to pre.requiredString("UploadId"))
        repeat(count) { index ->
            val request = JSONObject(body.toString()).put("partNumberStart", index + 1).put("partNumberEnd", index + 2)
            val urls = post(if (count == 1) "file/s3_upload_object/auth" else "file/s3_repare_upload_parts_batch", request)
            source.send(client, urls.getJSONObject("presignedUrls").requiredString((index + 1).toString()), index * chunk, minOf(source.size, (index + 1) * chunk))
        }
        post("file/upload_complete/v2", body.put("fileId", id).put("fileSize", source.size).put("isMultipart", count > 1))
        return id
    }

    private suspend fun mobile(parent: String, source: CloudUploadSource): String {
        val hash = source.hash("SHA-256")
        val chunk = if (source.size > 30L * 1024 * 1024 * 1024) 512L * 1024 * 1024 else 100L * 1024 * 1024
        val count = maxOf(1, ((source.size + chunk - 1) / chunk).toInt())
        val parts = (0 until count).map { index -> jsonObject("partNumber" to index + 1, "partSize" to minOf(chunk, source.size - index * chunk), "parallelHashCtx" to jsonObject("partOffset" to index * chunk)) }
        val pre = post(
            "create",
            jsonObject(
                "contentHash" to hash, "contentHashAlgorithm" to "SHA256", "contentType" to source.mimeType,
                "parallelUpload" to false, "partInfos" to parts.take(100), "size" to source.size, "parentFileId" to parent,
                "name" to source.name, "type" to "file", "fileRenameMode" to "auto_rename",
            ),
        )
        check(!pre.optBoolean("exist")) { "已有同名文件，请重命名后上传" }
        val id = pre.requiredString("fileId")
        if (pre.optBoolean("rapidUpload")) return id
        val uploadId = pre.requiredString("uploadId")
        for (first in 0 until count step 100) {
            val batch = if (first == 0) pre else post("getUploadUrl", jsonObject("fileId" to id, "uploadId" to uploadId, "partInfos" to parts.drop(first).take(100)))
            val addresses = batch.objects("partInfos").sortedBy { it.optInt("partNumber") }
            check(addresses.size == minOf(100, count - first)) { "移动未返回完整上传分段" }
            addresses.forEachIndexed { offset, part ->
                val index = part.optInt("partNumber") - 1
                check(index == first + offset) { "移动上传分段序号异常" }
                source.send(client, part.requiredString("uploadUrl"), index * chunk, minOf(source.size, (index + 1) * chunk), mapOf("Content-Type" to source.mimeType, "Origin" to "https://yun.139.com", "Referer" to "https://yun.139.com/"))
            }
        }
        post("complete", jsonObject("contentHash" to hash, "contentHashAlgorithm" to "SHA256", "fileId" to id, "uploadId" to uploadId))
        return id
    }

    private suspend fun quark(parent: String, source: CloudUploadSource): String {
        val response = features.post(
            "file/upload/pre",
            jsonObject(
                "ccp_hash_update" to true, "dir_name" to "", "file_name" to source.name, "format_type" to source.mimeType,
                "pdir_fid" to parent, "size" to source.size, "l_created_at" to source.file.lastModified(),
                "l_updated_at" to source.file.lastModified(), "same_path_reuse" to false,
            ),
        )
        val pre = features.data(response)
        val task = pre.requiredString("task_id")
        val hashes = post("file/update/hash", jsonObject("md5" to source.hash("MD5"), "sha1" to source.hash("SHA-1"), "task_id" to task))
        if (hashes.optBoolean("finish")) return hashes.optString("fid").ifBlank { pre.optString("fid") }
        val bucket = pre.requiredString("bucket")
        val key = pre.requiredString("obj_key")
        val uploadId = pre.requiredString("upload_id")
        val endpoint = pre.requiredString("upload_url").toHttpUrl()
        val address = endpoint.newBuilder().scheme("https").host(if (endpoint.host.startsWith("$bucket.")) endpoint.host else "$bucket.${endpoint.host}")
            .encodedPath("/").addPathSegments(key).query(null).build()
        suspend fun authorize(meta: String): String = post("file/upload/auth", jsonObject("auth_info" to pre.get("auth_info"), "auth_meta" to meta, "task_id" to task)).requiredString("auth_key")
        val agent = "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit"
        val origin = if (features.provider == "uc") "https://drive.uc.cn/" else "https://pan.quark.cn/"
        val chunk = response.optJSONObject("metadata")?.optLong("part_size", 8L * 1024 * 1024) ?: 8L * 1024 * 1024
        check(chunk > 0) { "上传分段大小无效" }
        val count = maxOf(1, ((source.size + chunk - 1) / chunk).toInt())
        val etags = mutableListOf<String>()
        repeat(count) { index ->
            val date = httpDate()
            val auth = authorize("PUT\n\n${source.mimeType}\n$date\nx-oss-date:$date\nx-oss-user-agent:$agent\n/$bucket/$key?partNumber=${index + 1}&uploadId=$uploadId")
            val url = address.newBuilder().addQueryParameter("partNumber", (index + 1).toString()).addQueryParameter("uploadId", uploadId).build()
            val etag = source.send(
                client,
                url.toString(),
                index * chunk,
                minOf(source.size, (index + 1) * chunk),
                mapOf(
                    "Authorization" to auth,
                    "Content-Type" to source.mimeType,
                    "Referer" to origin,
                    "x-oss-date" to date,
                    "x-oss-user-agent" to agent,
                ),
            )
            check(etag.isNotBlank()) { "网盘未确认上传分段" }
            etags.add(etag)
        }
        val xml = completeXml(etags)
        val date = httpDate()
        val hash = base64(MessageDigest.getInstance("MD5").digest(xml.toByteArray()))
        val callback = base64(pre.get("callback").toString().toByteArray())
        val auth = authorize("POST\n$hash\napplication/xml\n$date\nx-oss-callback:$callback\nx-oss-date:$date\nx-oss-user-agent:$agent\n/$bucket/$key?uploadId=$uploadId")
        raw(
            Request.Builder().url(address.newBuilder().addQueryParameter("uploadId", uploadId).build())
                .post(xml.toRequestBody("application/xml".toMediaType()))
                .header("Authorization", auth).header("Content-MD5", hash).header("Referer", origin)
                .header("x-oss-callback", callback).header("x-oss-date", date).header("x-oss-user-agent", agent).build(),
        )
        val finished = post("file/upload/finish", jsonObject("obj_key" to key, "task_id" to task))
        return finished.optString("fid").ifBlank { pre.optString("fid") }
    }

    private suspend fun tianyi(parent: String, source: CloudUploadSource): String {
        val session = features.get("portal/v2/getUserBriefInfo.action", emptyMap()).requiredString("sessionKey")
        check(session.isNotBlank()) { "天翼上传会话已失效，请重新登录" }
        val rsaInfo = features.get("security/generateRsaKey.action", emptyMap())
        val publicKey = rsaInfo.requiredString("pubKey").replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replace(Regex("\\s"), "")
        val rsa = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey.decodeBase64()?.toByteArray() ?: error("天翼返回的加密公钥无效")))
        val pkId = rsaInfo.requiredString("pkId")
        suspend fun call(path: String, params: Map<String, String>): JSONObject {
            val date = System.currentTimeMillis().toString()
            val secret = ByteArray(12).also { SecureRandom().nextBytes(it) }.hex()
            val plain = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret.take(16).toByteArray(), "AES")) }
            val encrypted = cipher.doFinal(plain.toByteArray()).hex()
            val signature = hmac("HmacSHA1", secret.toByteArray(), "SessionKey=$session&Operate=GET&RequestURI=$path&Date=$date&params=$encrypted".toByteArray()).hex()
            val encryptionText = base64(Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, rsa) }.doFinal(secret.toByteArray()))
            val request = Request.Builder().url(("https://upload.cloud.189.cn$path").toHttpUrl().newBuilder().addQueryParameter("params", encrypted).build())
                .header("Accept", "application/json;charset=UTF-8").header("User-Agent", com.fluxplayer.app.core.data.cloud189.C189AuthProvider.userAgent).header("SessionKey", session).header("Signature", signature)
                .header("X-Request-Date", date).header("X-Request-ID", UUID.randomUUID().toString()).header("EncryptionText", encryptionText).header("PkId", pkId).build()
            val result = JSONObject(raw(request))
            check(result.optString("code") == "SUCCESS") { "天翼上传失败：${result.optString("code")}" }
            return result
        }
        val chunk = 10L * 1024 * 1024
        val count = maxOf(1, ((source.size + chunk - 1) / chunk).toInt())
        val hash = source.hash("MD5")
        val hashes = (0 until count).map { source.digest("MD5", it * chunk, minOf(source.size, (it + 1) * chunk)) }
        val sliceHash = if (source.size <= chunk) hash else MessageDigest.getInstance("MD5").digest(hashes.joinToString("\n") { it.hex().uppercase(Locale.US) }.toByteArray()).hex()
        val pre = features.data(
            call(
                "/person/initMultiUpload",
                linkedMapOf(
                    "parentFolderId" to parent,
                    "fileName" to URLEncoder.encode(source.name, "UTF-8"),
                    "fileSize" to source.size.toString(),
                    "sliceSize" to chunk.toString(),
                    "fileMd5" to hash,
                    "sliceMd5" to sliceHash,
                ),
            ),
        )
        val uploadId = pre.requiredString("uploadFileId")
        if (pre.optInt("fileDataExists") != 1) {
            repeat(count) { index ->
                val urls = call("/person/getMultiUploadUrls", mapOf("partInfo" to "${index + 1}-${base64(hashes[index])}", "uploadFileId" to uploadId))
                val part = urls.getJSONObject("uploadUrls").getJSONObject("partNumber_${index + 1}")
                val headers = java.net.URLDecoder.decode(part.optString("requestHeader"), "UTF-8").split('&').mapNotNull { pair ->
                    val position = pair.indexOf('=')
                    if (position > 0) pair.take(position) to pair.substring(position + 1) else null
                }.toMap()
                source.send(client, part.requiredString("requestURL"), index * chunk, minOf(source.size, (index + 1) * chunk), headers)
            }
        }
        val completed = call("/person/commitMultiUploadFile", mapOf("uploadFileId" to uploadId, "fileMd5" to hash, "sliceMd5" to sliceHash, "lazyCheck" to "1", "opertype" to "3"))
        return features.data(completed).requiredString("fileId")
    }

    private suspend fun s3(pre: JSONObject, source: CloudUploadSource) {
        val endpoint = pre.requiredString("EndPoint").let { if (it.contains("://")) it else "https://$it" }.toHttpUrl()
        val url = endpoint.newBuilder().encodedPath("/").addPathSegment(pre.requiredString("Bucket")).addPathSegments(pre.requiredString("Key")).build()
        val token = pre.optString("SessionToken")
        suspend fun request(method: String, query: Map<String, String>, text: String = "", start: Long? = null, end: Long = source.size): String {
            val address = url.newBuilder().apply { query.toSortedMap().forEach { (key, value) -> addQueryParameter(key, value) } }.build()
            val date = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val shortDate = date.take(8)
            val hash = if (start != null) source.hash("SHA-256", start, end) else MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).hex()
            val host = address.host + if (address.port == 443 && address.scheme == "https" || address.port == 80 && address.scheme == "http") "" else ":${address.port}"
            val canonicalHeaders = "host:$host\nx-amz-content-sha256:$hash\nx-amz-date:$date\n" + if (token.isBlank()) "" else "x-amz-security-token:$token\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date" + if (token.isBlank()) "" else ";x-amz-security-token"
            val canonical = "$method\n${address.encodedPath}\n${address.encodedQuery.orEmpty()}\n$canonicalHeaders\n$signedHeaders\n$hash"
            val scope = "$shortDate/123pan/s3/aws4_request"
            val toSign = "AWS4-HMAC-SHA256\n$date\n$scope\n${MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).hex()}"
            var key = ("AWS4" + pre.requiredString("SecretAccessKey")).toByteArray()
            listOf(shortDate, "123pan", "s3", "aws4_request").forEach { key = hmac("HmacSHA256", key, it.toByteArray()) }
            val signature = hmac("HmacSHA256", key, toSign.toByteArray()).hex()
            val headers = mutableMapOf("Authorization" to "AWS4-HMAC-SHA256 Credential=${pre.requiredString("AccessKeyId")}/$scope, SignedHeaders=$signedHeaders, Signature=$signature", "x-amz-content-sha256" to hash, "x-amz-date" to date)
            if (token.isNotBlank()) headers["x-amz-security-token"] = token
            if (start != null) return source.send(client, address.toString(), start, end, headers)
            val builder = Request.Builder().url(address).method(method, if (method == "DELETE") null else text.toRequestBody("application/xml".toMediaType()))
            headers.forEach { (key, value) -> builder.header(key, value) }
            return raw(builder.build())
        }
        if (source.size == 0L) {
            request("PUT", emptyMap(), start = 0)
            return
        }
        val initialized = request("POST", mapOf("uploads" to ""))
        val uploadId = Regex("<UploadId>([^<]+)</UploadId>").find(initialized)?.groupValues?.get(1) ?: error("123 未创建分段上传任务")
        var completed = false
        try {
            val chunk = maxOf(16L * 1024 * 1024, (source.size + 8999) / 9000)
            val etags = mutableListOf<String>()
            var start = 0L
            while (start < source.size) {
                val etag = request("PUT", mapOf("partNumber" to (etags.size + 1).toString(), "uploadId" to uploadId), start = start, end = minOf(source.size, start + chunk))
                check(etag.isNotBlank()) { "123 未确认上传分段" }
                etags.add(etag)
                start += chunk
            }
            request("POST", mapOf("uploadId" to uploadId), completeXml(etags))
            completed = true
        } finally {
            if (!completed) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        request("DELETE", mapOf("uploadId" to uploadId))
                    } catch (_: Exception) { /* 中止失败时由网盘回收未完成分段。 */ }
                }
            }
        }
    }

    private suspend fun raw(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful && !text.contains("<Error>")) { "上传服务请求失败（HTTP ${response.code}）" }
            text
        }
    }

    private fun httpDate(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())
    private fun base64(bytes: ByteArray): String = bytes.toByteString().base64()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun hmac(algorithm: String, key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance(algorithm).apply { init(SecretKeySpec(key, algorithm)) }.doFinal(value)
    private fun completeXml(etags: List<String>): String = "<CompleteMultipartUpload>" + etags.mapIndexed { index, etag ->
        "<Part><PartNumber>${index + 1}</PartNumber><ETag>${etag.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")}</ETag></Part>"
    }.joinToString("") + "</CompleteMultipartUpload>"
}
