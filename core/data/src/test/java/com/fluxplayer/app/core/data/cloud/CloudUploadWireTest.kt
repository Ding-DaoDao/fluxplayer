package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.yun139.Yun139ApiClient
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 控制接口使用样例响应，文件和合并请求实际经过本地 HTTP，检查最终发出的头和字节。 */
class CloudUploadWireTest {
    @After fun clear() {
        C189AuthProvider.clear()
        Yun139AuthProvider.clear()
    }

    @Test fun quarkWireContentTypeMatchesAuthorizedMeta() = runBlocking { quark("quark") }

    @Test fun ucWireContentTypeMatchesAuthorizedMeta() = runBlocking { quark("uc") }

    @Test fun quarkMerge403ReportsStageAndCodeWithoutLeakingSecrets() = runBlocking { quark("quark", deny = true) }

    @Test
    fun aliyunSignedUrlStillUploadsAndCompletes() = runBlocking {
        file { source ->
            val completed = AtomicInteger()
            WireServer { wire ->
                assertEquals("PUT", wire.method)
                assertEquals("text/plain", wire.headers["content-type"])
                assertEquals(null, wire.headers["cookie"])
                assertEquals(null, wire.headers["authorization"])
                assertEquals("fixture", String(wire.bytes))
                Reply()
            }.use { server ->
                val http = transport(server) { request, _ ->
                    when {
                        request.url.encodedPath.endsWith("createWithFolders") -> """{"file_id":"file","upload_id":"upload","part_info_list":[{"part_number":1,"upload_url":"https://upload.example.test/blob?signature=test"}]}"""
                        request.url.encodedPath.endsWith("/complete") -> {
                            completed.incrementAndGet()
                            "{}"
                        }
                        else -> null
                    }
                }
                val api = AliyunApiClient(http).apply {
                    authorization = "token"
                    driveId = "drive"
                }
                assertEquals("file", CloudProviderUpload(CloudProviderFeatures.from(api), http).upload("root", source))
                server.rethrow()
                assertEquals(1, completed.get())
            }
        }
    }

    private suspend fun quark(provider: String, deny: Boolean = false) = file { source ->
        val authorizations = ConcurrentHashMap<String, String>()
        val finished = AtomicInteger()
        val wireCalls = AtomicInteger()
        val key = "folder/测试 + file.txt"
        val origin = if (provider == "uc") "https://drive.uc.cn/" else "https://pan.quark.cn/"
        WireServer { wire ->
            wireCalls.incrementAndGet()
            assertEquals(null, wire.headers["cookie"])
            assertEquals(source.mimeType.takeIf { wire.method == "PUT" } ?: "application/xml", wire.headers["content-type"])
            assertEquals(origin, wire.headers["referer"])
            val date = wire.headers["x-oss-date"]!!
            val agent = wire.headers["x-oss-user-agent"]!!
            val callback = wire.headers["x-oss-callback"]?.let { "x-oss-callback:$it\n" }.orEmpty()
            val expected = "${wire.method}\n${wire.headers["content-md5"].orEmpty()}\n${wire.headers["content-type"]}\n$date\n${callback}x-oss-date:$date\nx-oss-user-agent:$agent\n/bucket/$key?${if (wire.method == "PUT") "partNumber=1&" else ""}uploadId=upload"
            assertEquals(expected, authorizations[wire.headers["authorization"]])
            if (wire.method == "PUT") {
                assertArrayEquals("fixture".toByteArray(), wire.bytes)
                Reply(headers = mapOf("ETag" to "\"etag\""))
            } else {
                assertEquals(md5(wire.bytes).toByteString().base64(), wire.headers["content-md5"])
                if (deny) Reply(403, "<Error><Code>SignatureDoesNotMatch</Code><RequestId>request-123</RequestId><StringToSign>secret-value</StringToSign></Error>") else Reply(body = "<CompleteMultipartUploadResult/>")
            }
        }.use { server ->
            val http = transport(server) { request, body ->
                when {
                    request.url.encodedPath.endsWith("/pre") -> JSONObject().put("status", 200).put("code", 0).put(
                        "data",
                        JSONObject()
                            .put("task_id", "task").put("fid", "file").put("bucket", "bucket").put("obj_key", key)
                            .put("upload_id", "upload").put("upload_url", "https://oss.example.test").put("auth_info", "info")
                            .put("callback", JSONObject().put("callbackUrl", "https://example.test/callback")),
                    ).toString()
                    request.url.encodedPath.endsWith("/hash") -> """{"data":{"finish":false}}"""
                    request.url.encodedPath.endsWith("/auth") -> {
                        val auth = "OSS fixture:${authorizations.size}"
                        authorizations[auth] = body.getString("auth_meta")
                        JSONObject().put("data", JSONObject().put("auth_key", auth)).toString()
                    }
                    request.url.encodedPath.endsWith("/finish") -> {
                        finished.incrementAndGet()
                        """{"data":{"fid":"file"}}"""
                    }
                    else -> null
                }
            }
            val api = QuarkApiClient(http, provider).apply { setCookie("__uid=fixture") }
            val result = runCatching { CloudProviderUpload(CloudProviderFeatures.from(api), http).upload("0", source) }
            server.rethrow()
            if (deny) {
                val message = result.exceptionOrNull()!!.message!!
                assertTrue(message.contains("合并上传分段"))
                assertTrue(message.contains("HTTP 403"))
                assertTrue(message.contains("SignatureDoesNotMatch"))
                assertFalse(message.contains("secret-value"))
                assertEquals(0, finished.get())
            } else {
                assertEquals("file", result.getOrThrow())
                assertEquals(1, finished.get())
            }
            assertEquals(2, wireCalls.get())
        }
    }

    @Test
    fun pan123NullSecretUsesPresignedUploadWithoutRequiringUploadId() = runBlocking {
        for (secret in listOf(JSONObject.NULL, "null")) file { source ->
            val committed = AtomicInteger()
            WireServer { wire ->
                assertEquals("PUT", wire.method)
                assertEquals("text/plain", wire.headers["content-type"])
                assertEquals(null, wire.headers["authorization"])
                assertEquals("fixture", String(wire.bytes))
                Reply()
            }.use { server ->
                val http = transport(server) { request, _ ->
                    when {
                        request.url.encodedPath.endsWith("upload_request") -> JSONObject().put("code", 0).put(
                            "data",
                            JSONObject()
                                .put("FileId", 123).put("AccessKeyId", "access").put("SecretAccessKey", secret)
                                .put("Key", "key").put("Bucket", "bucket").put("Reuse", false).put("UploadId", JSONObject.NULL),
                        ).toString()
                        request.url.encodedPath.endsWith("/auth") -> """{"code":0,"data":{"presignedUrls":{"1":"https://upload.example.test/blob"}}}"""
                        request.url.encodedPath.endsWith("/v2") -> {
                            committed.incrementAndGet()
                            """{"code":0,"data":{"file_info":{"FileId":123}}}"""
                        }
                        else -> null
                    }
                }
                val api = Pan123ApiClient(http).apply { setToken("fixture") }
                assertEquals("123", CloudProviderUpload(CloudProviderFeatures.from(api), http).upload("0", source))
                server.rethrow()
                assertEquals(1, committed.get())
            }
        }
    }

    @Test fun pan123SmallFileUsesSignedPutWithStrictEscaping() = runBlocking { s3(false) }

    @Test fun pan123LargeFileUsesSignedMultipartRequests() = runBlocking { s3(true) }

    @Test
    fun signedUploadDoesNotFollowRedirectOrResendOn403() = runBlocking {
        file { source ->
            for (status in listOf(307, 403)) {
                val calls = AtomicInteger()
                WireServer {
                    calls.incrementAndGet()
                    Reply(status, headers = mapOf("Location" to "https://upload.example.test/other"))
                }.use { server ->
                    val http = transport(server) { _, _ -> null }
                    val failure = runCatching { source.send(http, "https://upload.example.test/blob", 0, source.size) }.exceptionOrNull()
                    assertTrue(failure!!.message!!.contains("HTTP $status"))
                    assertEquals(1, calls.get())
                }
            }
        }
    }

    @Test
    fun namespacedStorageErrorIsDetectedWithoutShowingSignatureBody() {
        val text = "<s3:Error xmlns:s3=\"urn:s3\"><s3:Code>AccessDenied</s3:Code><StringToSign>secret</StringToSign></s3:Error>"
        assertTrue(uploadXmlError(text))
        val response = Response.Builder().request(Request.Builder().url("https://example.test").build()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody()).build()
        response.use {
            val detail = uploadResponseError(it, text)
            assertTrue(detail.contains("AccessDenied"))
            assertFalse(detail.contains("secret"))
        }
    }

    private suspend fun s3(large: Boolean) = file(large) { source ->
        val methods = mutableListOf<String>()
        val objectKey = "dir/中文 +!*'().txt"
        WireServer { wire ->
            methods.add(wire.method)
            assertEquals("/bucket/dir/%E4%B8%AD%E6%96%87%20%2B%21%2A%27%28%29.txt", wire.path)
            assertEquals(if (large) "token+/=" else null, wire.headers["x-amz-security-token"])
            val date = wire.headers["x-amz-date"]!!
            val shortDate = date.take(8)
            val hash = sha256(wire.bytes)
            assertEquals(hash, wire.headers["x-amz-content-sha256"])
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date" + if (large) ";x-amz-security-token" else ""
            val tokenHeader = if (large) "x-amz-security-token:token+/=\n" else ""
            val canonical = "${wire.method}\n${wire.path}\n${wire.query}\nhost:${wire.headers["host"]}\nx-amz-content-sha256:$hash\nx-amz-date:$date\n$tokenHeader\n$signedHeaders\n$hash"
            val scope = "$shortDate/123pan/s3/aws4_request"
            var signingKey = "AWS4secret".toByteArray()
            listOf(shortDate, "123pan", "s3", "aws4_request").forEach { signingKey = hmac("HmacSHA256", signingKey, it.toByteArray()) }
            val signature = hmac("HmacSHA256", signingKey, "AWS4-HMAC-SHA256\n$date\n$scope\n${sha256(canonical.toByteArray())}".toByteArray()).hex()
            assertEquals("AWS4-HMAC-SHA256 Credential=access/$scope, SignedHeaders=$signedHeaders, Signature=$signature", wire.headers["authorization"])
            when {
                wire.query == "uploads=" -> Reply(body = "<InitiateMultipartUploadResult><UploadId>up+/=</UploadId></InitiateMultipartUploadResult>")
                wire.method == "PUT" -> Reply(headers = mapOf("ETag" to "\"etag\""))
                else -> {
                    assertEquals("uploadId=up%2B%2F%3D", wire.query)
                    Reply(body = "<CompleteMultipartUploadResult/>")
                }
            }
        }.use { server ->
            val http = transport(server) { request, _ ->
                when {
                    request.url.encodedPath.endsWith("upload_request") -> JSONObject().put("code", 0).put(
                        "data",
                        JSONObject().put("FileId", 123).put("Key", objectKey)
                            .put("Bucket", "bucket").put("EndPoint", "https://s3.example.test").put("AccessKeyId", "access").put("SecretAccessKey", "secret").put("SessionToken", if (large) "token+/=" else "null"),
                    ).toString()
                    request.url.encodedPath.endsWith("upload_complete") -> """{"code":0,"data":{}}"""
                    else -> null
                }
            }
            val api = Pan123ApiClient(http).apply { setToken("fixture") }
            assertEquals("123", CloudProviderUpload(CloudProviderFeatures.from(api), http).upload("0", source))
            server.rethrow()
            assertEquals(if (large) listOf("POST", "PUT", "PUT", "POST") else listOf("PUT"), methods)
        }
    }

    @Test
    fun mobileUploadUsesOctetStreamForBothTicketAndActualPut() = runBlocking {
        file { source ->
            WireServer { wire ->
                assertEquals("application/octet-stream", wire.headers["content-type"])
                assertEquals("https://yun.139.com", wire.headers["origin"])
                assertEquals("https://yun.139.com/", wire.headers["referer"])
                assertEquals("fixture", String(wire.bytes))
                Reply()
            }.use { server ->
                val http = transport(server) { request, body ->
                    when {
                        request.url.encodedPath.endsWith("/create") -> {
                            assertEquals("application/octet-stream", body.getString("contentType"))
                            """{"success":true,"data":{"fileId":"file","uploadId":"upload","partInfos":[{"partNumber":1,"uploadUrl":"https://upload.example.test/blob"}]}}"""
                        }
                        request.url.encodedPath.endsWith("/complete") -> """{"success":true,"data":{}}"""
                        else -> null
                    }
                }
                assertEquals("file", CloudProviderUpload(CloudProviderFeatures.from(Yun139ApiClient(http)), http).upload("/", source))
                server.rethrow()
            }
        }
    }

    @Test
    fun tianyiActualRequestsPreservePlusInSignedHeaders() = runBlocking { tianyi() }

    @Test
    fun tianyiRejectedCommitExplainsPolicyWithoutRetryingOrConfirmingSuccess() = runBlocking {
        tianyi(403)
        tianyi(200)
    }

    @Test
    fun tianyiMultipartChecksHashBeforeSendingPartsAndUsesOfficialCompletion() = runBlocking { tianyi(large = true) }

    private suspend fun tianyi(commitStatus: Int? = null, large: Boolean = false) {
        file(large) { source ->
            if (large) java.io.RandomAccessFile(source.file, "rw").use { it.setLength(10L * 1024 * 1024 + 17) }
            val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
            val paths = mutableListOf<String>()
            val expectedHash = source.hash("MD5")
            val expectedSlice = if (large) {
                val digests = listOf(source.hash("MD5", 0, 10L * 1024 * 1024), source.hash("MD5", 10L * 1024 * 1024, source.size))
                md5(digests.joinToString("\n") { it.uppercase() }.toByteArray()).hex()
            } else {
                expectedHash
            }
            val blobs = AtomicInteger()
            WireServer { wire ->
                paths.add(wire.path)
                if (wire.path == "/blob") {
                    assertEquals("Basic test+base64==", wire.headers["authorization"])
                    assertEquals("text/plain", wire.headers["content-type"])
                    if (large) {
                        assertEquals(if (blobs.getAndIncrement() == 0) 10 * 1024 * 1024 else 17, wire.bytes.size)
                    } else {
                        assertEquals("fixture", String(wire.bytes))
                    }
                    Reply()
                } else {
                    assertEquals("GET", wire.method)
                    assertTrue(wire.headers["user-agent"]!!.contains("Chrome/124"))
                    val encryptedSecret = wire.headers["encryptiontext"]!!.decodeBase64()!!.toByteArray()
                    val secret = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, rsa.private) }.doFinal(encryptedSecret)
                    val params = wire.query.substringAfter("params=")
                    val signature = hmac("HmacSHA1", secret, "SessionKey=web-session&Operate=GET&RequestURI=${wire.path}&Date=${wire.headers["x-request-date"]}&params=$params".toByteArray()).hex()
                    assertEquals(signature, wire.headers["signature"])
                    val encryptedParams = params.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val plain = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(secret.copyOfRange(0, 16), "AES")) }.doFinal(encryptedParams).toString(Charsets.UTF_8)
                    val fields = plain.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                    when (wire.path) {
                        "/person/initMultiUpload" -> {
                            assertEquals("-11", fields["parentFolderId"])
                            if (large) {
                                assertEquals("1", fields["lazyCheck"])
                                assertFalse(fields.containsKey("fileMd5"))
                            } else {
                                assertEquals(expectedHash, fields["fileMd5"])
                                assertEquals(expectedSlice, fields["sliceMd5"])
                                assertFalse(fields.containsKey("lazyCheck"))
                            }
                        }
                        "/person/checkTransSecond", "/person/commitMultiUploadFile" -> {
                            assertEquals(expectedHash, fields["fileMd5"])
                            assertEquals(expectedSlice, fields["sliceMd5"])
                            if (wire.path.endsWith("commitMultiUploadFile")) {
                                assertEquals(if (large) "1" else "0", fields["lazyCheck"])
                                assertFalse(fields.containsKey("opertype"))
                            }
                        }
                    }
                    val body = when (wire.path) {
                        "/person/initMultiUpload" -> """{"code":"SUCCESS","data":{"uploadFileId":"upload","fileDataExists":0}}"""
                        "/person/getMultiUploadUrls" -> """{"code":"SUCCESS","uploadUrls":{"partNumber_1":{"requestURL":"https://upload.example.test/blob","requestHeader":"Authorization=Basic test+base64==&content-type=text/plain"}}}""".replace("partNumber_1", "partNumber_${fields.getValue("partInfo").substringBefore('-')}")
                        "/person/getUploadedPartsInfo" -> """{"code":"SUCCESS","data":{"uploadedPartList":""}}"""
                        "/person/checkTransSecond" -> """{"code":"SUCCESS","data":{"uploadFileId":"upload","fileDataExists":0}}"""
                        else -> """{"code":"SUCCESS","file":{"userFileId":"file"}}"""
                    }
                    if (wire.path == "/person/commitMultiUploadFile" && commitStatus != null) {
                        Reply(commitStatus, """{"code":"InfoSecurityErrorCode","message":"internal-details-not-for-ui"}""")
                    } else {
                        Reply(body = body)
                    }
                }
            }.use { server ->
                val http = transport(server) { request, _ ->
                    when {
                        request.url.encodedPath.endsWith("getUserBriefInfo.action") -> """{"sessionKey":"web-session"}"""
                        request.url.encodedPath.endsWith("generateRsaKey.action") -> JSONObject().put("pubKey", rsa.public.encoded.toByteString().base64()).put("pkId", "key").toString()
                        else -> null
                    }
                }
                C189AuthProvider.setTokens("token", "session", "secret")
                val features = CloudProviderFeatures.from(C189ApiClient(http))
                if (commitStatus == null) {
                    assertEquals("file", CloudProviderUpload(features, http).upload("-11", source))
                } else {
                    val failure = runCatching { features.upload("-11", source) }.exceptionOrNull()
                    val message = failure!!.message!!
                    assertTrue(message.contains("commitMultiUploadFile"))
                    assertTrue(message.contains("InfoSecurityErrorCode"))
                    assertTrue(message.contains("安全校验拒绝"))
                    assertFalse(message.contains("签名校验失败"))
                    assertFalse(message.contains("internal-details-not-for-ui"))
                }
                server.rethrow()
                assertEquals(
                    if (large) {
                        listOf("/person/initMultiUpload", "/person/checkTransSecond", "/person/getUploadedPartsInfo", "/person/getMultiUploadUrls", "/blob", "/person/getMultiUploadUrls", "/blob", "/person/commitMultiUploadFile")
                    } else {
                        listOf("/person/initMultiUpload", "/person/getUploadedPartsInfo", "/person/getMultiUploadUrls", "/blob", "/person/commitMultiUploadFile")
                    },
                    paths,
                )
            }
        }
    }

    private suspend fun file(large: Boolean = false, action: suspend (CloudUploadSource) -> Unit) {
        val file = File.createTempFile("upload-wire", ".txt")
        try {
            if (large) java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024 + 17) } else file.writeText("fixture")
            action(CloudUploadSource(file, "测试文件.txt", "text/plain"))
        } finally {
            file.delete()
        }
    }

    private fun transport(server: WireServer, api: (Request, JSONObject) -> String?): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("should-not-leak").value("secret").hostOnlyDomain(url.host).build())
        })
        .addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            if (request.url.host !in setOf("bucket.oss.example.test", "s3.example.test", "upload.example.test", "upload.cloud.189.cn")) request.body?.writeTo(buffer)
            val json = runCatching { JSONObject(buffer.readUtf8()) }.getOrElse { JSONObject() }
            val result = api(request, json)
            if (result != null) {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(result.toResponseBody()).build()
            } else {
                val target = request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()
                chain.proceed(request.newBuilder().url(target).header("Host", request.url.host).build())
            }
        }.build()

    private data class Wire(val method: String, val path: String, val query: String, val headers: Map<String, String>, val bytes: ByteArray)
    private data class Reply(val status: Int = 200, val body: String = "", val headers: Map<String, String> = emptyMap())
    private class WireServer(handler: (Wire) -> Reply) : AutoCloseable {
        private val failure = AtomicReference<Throwable?>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        init {
            server.createContext("/") { exchange ->
                val reply = try {
                    handler(Wire(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestURI.rawQuery.orEmpty(), exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }, exchange.requestBody.readBytes()))
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    Reply(403, "<Error><Code>SignatureDoesNotMatch</Code></Error>")
                }
                reply.headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
                exchange.sendResponseHeaders(reply.status, if (reply.body.isEmpty()) -1 else reply.body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(reply.body.toByteArray()) }
                exchange.close()
            }
            server.start()
        }
        fun rethrow() {
            failure.get()?.let { throw it }
        }
        override fun close() {
            server.stop(0)
            rethrow()
        }
    }

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun hmac(name: String, key: ByteArray, bytes: ByteArray) = Mac.getInstance(name).apply { init(SecretKeySpec(key, name)) }.doFinal(bytes)
}
