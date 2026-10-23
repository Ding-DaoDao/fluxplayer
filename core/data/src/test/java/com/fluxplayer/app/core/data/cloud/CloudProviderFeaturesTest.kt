package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderFeaturesTest {
    private fun client(handler: (Request, JSONObject) -> String): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val body = buffer.readUtf8().ifBlank { "{}" }
        val text = handler(request, JSONObject(body))
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody()).build()
    }.build()

    @Test
    fun createsQuarkShareOnlyAfterTaskConfirmation() = runBlocking {
        val paths = mutableListOf<String>()
        val http = client { request, body ->
            paths.add(request.url.encodedPath)
            when (request.url.encodedPath.substringAfter("/clouddrive/")) {
                "share" -> {
                    assertEquals("private", body.getString("passcode"))
                    assertEquals(3, body.getInt("expired_type"))
                    assertEquals(2, body.getInt("url_type"))
                    """{"status":200,"code":0,"data":{"task_id":"task"}}"""
                }
                "task" -> """{"status":200,"code":0,"data":{"status":2,"share_id":"share"}}"""
                "share/password" -> """{"status":200,"code":0,"data":{"share_url":"https://pan.quark.cn/s/share","passcode":"private"}}"""
                else -> error("意外的请求")
            }
        }
        val features = CloudProviderFeatures.from(QuarkApiClient(http), "quark")
        val share = features.createShare(listOf(CloudRemoteFile("file", "视频.mp4", false)), CloudShareOptions("视频", 7, "private"))
        assertEquals("https://pan.quark.cn/s/share", share.url)
        assertEquals(3, paths.size)
    }

    @Test
    fun doesNotReportFailedTaskAsCreatedShare() = runBlocking {
        var passwordRequested = false
        val http = client { request, _ ->
            when {
                request.url.encodedPath.endsWith("/share") -> """{"data":{"task_id":"task"}}"""
                request.url.encodedPath.endsWith("/task") -> """{"data":{"status":3}}"""
                else -> {
                    passwordRequested = true
                    "{}"
                }
            }
        }
        val features = CloudProviderFeatures.from(QuarkApiClient(http), "quark")
        val error = runCatching { features.createShare(listOf(CloudRemoteFile("file", "视频", false)), CloudShareOptions("视频")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(passwordRequested)
    }

    @Test
    fun ucShareUsesTransferCredentialsAndEscapesTokenInQuery() = runBlocking {
        val http = client { request, body ->
            if (request.url.encodedPath.endsWith("/token")) {
                assertTrue(body.getBoolean("share_for_transfer"))
                assertFalse(body.has("support_visit_limit_private_share"))
                """{"data":{"stoken":"a+b/=","first_fid":"0"}}"""
            } else {
                assertEquals("/1/clouddrive/transfer_share/detail", request.url.encodedPath)
                assertEquals("a+b/=", request.url.queryParameter("stoken"))
                """{"data":{"list":[{"fid":"f","file_name":"视频.mp4","size":100,"share_fid_token":"file-token"}]}}"""
            }
        }
        val api = QuarkApiClient(http).apply { setDriveType("uc") }
        val features = CloudProviderFeatures.from(api, "uc")
        val session = features.openShare("https://drive.uc.cn/s/abc", "pwd")
        val files = features.listShare(session, session.rootId)
        assertEquals("file-token", files.single().token)
        assertEquals(100L, files.single().size)
    }

    @Test
    fun refusesRepeatedSharePaginationInsteadOfReturningPartialSuccess() = runBlocking {
        // 夸克分享每页 100 条；喂两页完全相同的数据，应报「分页重复」而不是悄悄返回半份列表
        val array = JSONArray((1..100).map { jsonObject("fid" to it.toString(), "file_name" to "视频$it") })
        val http = client { _, _ -> jsonObject("data" to jsonObject("list" to array)).toString() }
        val features = CloudProviderFeatures.from(QuarkApiClient(http), "quark")
        val error = runCatching { features.listShare(CloudShareSession("share", "", "token"), "0") }.exceptionOrNull()
        assertTrue(error?.message?.contains("分页重复") == true)
    }

    @Test
    fun aliyunBusinessErrorsNeverBecomeEmptyListings() = runBlocking {
        val http = client { _, _ -> """{"code":"ShareLink.Expired","message":"分享已过期"}""" }
        val features = CloudProviderFeatures.from(AliyunApiClient(http))
        val error = runCatching { features.openShare("https://www.alipan.com/s/abc", "") }.exceptionOrNull()
        assertEquals("分享已过期", error?.message)
    }

    @Test
    fun aliyunExpiryIsAbsoluteUtcAndUsesSelectedDrive() = runBlocking {
        val before = Instant.now()
        val http = client { _, body ->
            assertEquals("album-drive", body.getString("drive_id"))
            val expiry = Instant.parse(body.getString("expiration"))
            assertTrue(expiry.isAfter(before.plusSeconds(86_390)))
            assertTrue(expiry.isBefore(before.plusSeconds(86_410)))
            """{"share_id":"share","share_url":"https://www.alipan.com/s/share"}"""
        }
        val features = CloudProviderFeatures.from(AliyunApiClient(http).apply { driveId = "album-drive" })
        features.createShare(listOf(CloudRemoteFile("file", "视频", false)), CloudShareOptions("分享", 1))
        Unit
    }

    @Test
    fun pan123ShareDoesNotTruncateLargeFileIds() = runBlocking {
        val http = client { request, body ->
            assertEquals(922337203685L, body.getLong("fileIdList"))
            assertNotNull(request.header("auth-key"))
            assertNotNull(request.header("auth-value"))
            """{"code":0,"data":{"ShareKey":"share"}}"""
        }
        val features = CloudProviderFeatures.from(Pan123ApiClient(http))
        val share = features.createShare(listOf(CloudRemoteFile("922337203685", "视频", false)), CloudShareOptions("分享"))
        assertEquals("https://www.123pan.com/s/share", share.url)
    }

    @Test
    fun quarkRapidUploadStillComputesBothHashes() = runBlocking {
        val file = File.createTempFile("upload-test", ".bin")
        try {
            file.writeText("abc")
            val http = client { request, body ->
                when {
                    request.url.encodedPath.endsWith("/upload/pre") -> """{"data":{"task_id":"task"}}"""
                    request.url.encodedPath.endsWith("/update/hash") -> {
                        assertEquals("900150983cd24fb0d6963f7d28e17f72", body.getString("md5"))
                        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", body.getString("sha1"))
                        """{"data":{"finish":true}}"""
                    }
                    request.url.encodedPath.endsWith("/file/sort") -> """{"data":{"list":[{"fid":"file","file_name":"abc.txt","size":3}]}}"""
                    else -> error("秒传不应发送文件数据")
                }
            }
            CloudProviderFeatures.from(QuarkApiClient(http), "quark").upload("0", CloudUploadSource(file, "abc.txt"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun uploadRejectsIncompleteAliyunPartList() = runBlocking {
        val file = File.createTempFile("upload-test", ".bin")
        try {
            file.writeText("abc")
            val http = client { _, _ -> """{"file_id":"f","upload_id":"u","part_info_list":[]}""" }
            val error = runCatching { CloudProviderFeatures.from(AliyunApiClient(http)).upload("root", CloudUploadSource(file, "abc.txt")) }.exceptionOrNull()
            assertTrue(error?.message?.contains("完整上传分段") == true)
        } finally {
            file.delete()
        }
    }
}
