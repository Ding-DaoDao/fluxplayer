package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.yun139.Yun139ApiClient
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 响应结构与签名向量取自文析原有测试，避免用迁移代码自己的假设构造响应。 */
class ProviderParityRegressionTest {
    private fun client(handler: (Request, String) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val buffer = Buffer()
        chain.request().body?.writeTo(buffer)
        val body = handler(chain.request(), buffer.readUtf8())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
    }.build()

    @After
    fun clearCredentials() {
        C189AuthProvider.clear()
        Yun139AuthProvider.clear()
    }

    @Test
    fun mobileSignatureMatchesWenxiKnownAnswer() {
        assertEquals("37671010D4036E42486B7EDC6BC52965", MobileCloudProtocol.calculateSign("{\"a\":1}", "2026-08-25 12:34:56", "AbCdEf0123456789"))
        val plain = "{\"name\":\"电影 !*'()\"}"
        assertEquals(plain, MobileCloudProtocol.decrypt(MobileCloudProtocol.encrypt(plain, ByteArray(16))))
    }

    @Test
    fun mobileWebAndMobileShareUrlsUseAnonymousEncryptedRequestsAndNestedResponses() = runBlocking {
        val http = client { request, encrypted ->
            assertEquals(null, request.header("Authorization"))
            assertEquals(null, request.header("mcloud-sign"))
            val body = JSONObject(MobileCloudProtocol.decrypt(encrypted))
            when {
                request.url.encodedPath.endsWith("getOutLinkGeneral") -> {
                    assertEquals("Mobile123", body.getJSONObject("getOutLinkGeneralReq").getString("linkID"))
                    """{"code":0,"data":{"getOutLinkGeneralResp":{"outLinkGeneral":[{"passwd":"old1"}]}}}"""
                }
                request.url.encodedPath.endsWith("getOutLinkInfoV6") -> {
                    val input = body.getJSONObject("getOutLinkInfoReq")
                    assertEquals("m123", input.getString("passwd"))
                    assertEquals("root", input.getString("pCaID"))
                    MobileCloudProtocol.encrypt("""{"code":0,"data":{"getOutLinkInfoResp":{"caLst":[],"coLst":[{"coID":"video","coName":"假期.mp4","coSize":100}]}}}""")
                }
                else -> error("不允许访问其他接口")
            }
        }
        val features = CloudProviderFeatures.from(Yun139ApiClient(http))
        for (url in listOf("https://caiyun.139.com/m/i?Mobile123&pass=m123", "https://yun.139.com/shareweb/#/w/i/Mobile123?pwd=m123")) {
            val session = features.openShare(url, "")
            assertEquals("Mobile123", session.id)
            assertEquals("video", features.listShare(session, session.rootId).single().id)
        }
    }

    @Test
    fun mobileFamilyAcceptsOfficialRootPathAndSignsTheCompleteIdentityBody() = runBlocking {
        Yun139AuthProvider.authorization = "Basic fixture"
        Yun139AuthProvider.cookie = "ud_id=domain;skey=fixture-key"
        Yun139AuthProvider.userDomainId = "domain"
        val http = client { request, text ->
            assertEquals("2", request.header("x-yun-svc-type"))
            assertEquals("fixture-key", request.header("mcloud-skey"))
            assertEquals("1", request.header("mcloud-userid-flag"))
            val sign = request.header("mcloud-sign")!!.split(',')
            assertEquals(MobileCloudProtocol.calculateSign(text, sign[0], sign[1]), sign[2])
            assertEquals("domain", JSONObject(text).getJSONObject("commonAccountInfo").getString("userDomainId"))
            """{"success":true,"data":{"result":{"resultCode":"0000"},"path":"root:/family-root","totalCount":1,"cloudCatalogList":[],"cloudContentList":[{"contentID":"movie","contentName":"电影.mp4","contentSize":100}]}}"""
        }
        val features = CloudProviderFeatures.from(Yun139ApiClient(http))
        val file = features.listSpace(CloudSpace("family", "家庭"), "").single()
        assertEquals("root:/family-root", file.token)
        assertEquals("movie", file.id)
    }

    @Test
    fun tianyiFamilyExchangesWebSessionForItsOwnTokenBeforeListing() = runBlocking {
        C189AuthProvider.setTokens("personal-token", "personal-session", "secret")
        var exchanges = 0
        val paths = mutableListOf<String>()
        val http = client { request, _ ->
            paths.add(request.url.encodedPath)
            when (request.url.encodedPath) {
                "/api/portal/v2/getUserBriefInfo.action" -> """{"res_code":0,"sessionKey":"web-session"}"""
                "/api/open/oauth2/getAccessTokenBySsKey.action" -> {
                    assertEquals("600100422", request.header("AppKey"))
                    assertEquals("web-session", request.url.queryParameter("sessionKey"))
                    val values = mapOf("AppKey" to "600100422", "Timestamp" to request.header("Timestamp")!!, "sessionKey" to "web-session")
                    assertEquals(md5(values.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }), request.header("Signature"))
                    exchanges++
                    """{"accessToken":"family-access"}"""
                }
                "/open/family/manage/getFamilyList.action" -> {
                    assertEquals("api.cloud.189.cn", request.url.host)
                    assertEquals("family-access", request.header("AccessToken"))
                    """{"familyInfoResp":[{"familyId":12001,"remarkName":"我们的家庭"}]}"""
                }
                "/open/family/file/listFiles.action" -> {
                    assertEquals("family-access", request.header("AccessToken"))
                    assertEquals("", request.url.queryParameter("folderId"))
                    """{"fileListAO":{"count":1,"folderList":[],"fileList":[{"id":"video","name":"假期.mp4","size":100}]}}"""
                }
                else -> error("请求走错接口：${request.url.encodedPath}")
            }
        }
        val features = CloudProviderFeatures.from(C189ApiClient(http))
        val space = features.listSpaces().single()
        assertEquals("video", features.listSpace(space, "").single().id)
        assertEquals(1, exchanges)
        assertEquals(4, paths.size)
    }

    @Test
    fun tianyiUploadUsesWebSessionAndBase64RsaEnvelope() = runBlocking {
        C189AuthProvider.setTokens("personal-token", "wrong-session", "secret")
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val file = File.createTempFile("parity-upload", ".txt").apply { writeText("fixture") }
        var mutations = 0
        try {
            val http = client { request, _ ->
                when (request.url.encodedPath) {
                    "/api/portal/v2/getUserBriefInfo.action" -> """{"sessionKey":"web-upload-session"}"""
                    "/api/security/generateRsaKey.action" -> JSONObject().put("pubKey", key.public.encoded.toByteString().base64()).put("pkId", "key").toString()
                    "/person/initMultiUpload", "/person/commitMultiUploadFile" -> {
                        assertEquals("web-upload-session", request.header("SessionKey"))
                        val ciphertext = request.header("EncryptionText")!!.decodeBase64()!!.toByteArray()
                        val secret = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, key.private) }.doFinal(ciphertext).toString(Charsets.UTF_8)
                        val encrypted = request.url.queryParameter("params")!!.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        val plain = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(secret.take(16).toByteArray(), "AES")) }.doFinal(encrypted).toString(Charsets.UTF_8)
                        assertTrue(plain.contains("fileMd5=${md5("fixture")}"))
                        mutations++
                        if (request.url.encodedPath.endsWith("initMultiUpload")) """{"code":"SUCCESS","data":{"uploadFileId":"upload","fileDataExists":1}}""" else """{"code":"SUCCESS","data":{"fileId":"file"}}"""
                    }
                    else -> error("意外接口")
                }
            }
            val features = CloudProviderFeatures.from(C189ApiClient(http))
            assertEquals("file", CloudProviderUpload(features, http).upload("-11", CloudUploadSource(file, "测试.txt")))
            assertEquals(2, mutations)
        } finally { file.delete() }
    }

    @Test
    fun pan123UploadUsesCanonicalWebIdentityAndPresignedPutBeforeCommit() = runBlocking {
        val file = File.createTempFile("parity-upload", ".txt").apply { writeText("fixture") }
        val paths = mutableListOf<String>()
        try {
            val http = client { request, text ->
                paths.add(request.url.encodedPath)
                if (request.url.host == "upload.example.com") {
                    assertEquals("PUT", request.method)
                    assertEquals("fixture", text)
                    ""
                } else {
                    assertEquals("yun.123pan.cn", request.url.host)
                    assertEquals("Bearer fixture", request.header("Authorization"))
                    assertTrue(request.header("User-Agent")!!.contains("Chrome/127"))
                    assertNotNull(request.header("auth-key"))
                    when {
                        request.url.encodedPath.endsWith("upload_request") -> """{"code":0,"data":{"FileId":123,"Key":"key","Bucket":"bucket","UploadId":"upload","StorageNode":"node","Reuse":false}}"""
                        request.url.encodedPath.endsWith("/auth") -> """{"code":0,"data":{"presignedUrls":{"1":"https://upload.example.com/part"}}}"""
                        request.url.encodedPath.endsWith("/v2") -> """{"code":0,"data":{"file_info":{"FileId":123}}}"""
                        else -> error("意外接口")
                    }
                }
            }
            val api = Pan123ApiClient(http).apply { setToken("fixture") }
            assertEquals("123", CloudProviderUpload(CloudProviderFeatures.from(api), http).upload("0", CloudUploadSource(file, "测试.txt")))
            assertEquals(4, paths.size)
        } finally { file.delete() }
    }

    @Test
    fun quarkShareAcceptsIdOnlyResultAndRejectsEitherFailureStatus() = runBlocking {
        val http = client { request, _ -> when {
            request.url.encodedPath.endsWith("/share") -> """{"data":{"task_id":"task"}}"""
            request.url.encodedPath.endsWith("/task") -> """{"data":{"status":0,"task_status":2,"share_id":"id"}}"""
            else -> """{"data":{"passcode":"1234"}}"""
        } }
        val features = CloudProviderFeatures.from(QuarkApiClient(http), "quark")
        assertEquals("https://pan.quark.cn/s/id", features.createShare(listOf(CloudRemoteFile("file", "文件", false)), CloudShareOptions("文件")).url)
        val failed = client { _, _ -> """{"data":{"status":0,"task_status":3}}""" }
        assertTrue(runCatching { CloudProviderFeatures.from(QuarkApiClient(failed), "quark").pollTask("task") }.isFailure)
    }

    @Test
    fun directorySearchVisitsNestedFoldersOnceAndPreservesParentMetadata() = runBlocking {
        val calls = mutableListOf<String>()
        val result = searchCloudTree("movie", "root", "网盘", read = { parent ->
            calls.add(parent)
            if (parent == "root") listOf(CloudRemoteFile("folder", "电影", true)) else listOf(CloudRemoteFile("file", "My Movie.mp4", false, 100, "metadata"), CloudRemoteFile("root", "环", true))
        }, onProgress = {})
        assertEquals(listOf("root", "folder"), calls)
        assertEquals("folder", result.hits.single().file.parentId)
        assertEquals("metadata", result.hits.single().file.token)
        assertFalse(result.limited)
    }

    @Test
    fun tianyiProtectedShareValidatesAccessCodeBeforeBrowsing() = runBlocking {
        val paths = mutableListOf<String>()
        val http = client { request, _ ->
            paths.add(request.url.encodedPath)
            assertEquals("cloud.189.cn", request.url.host)
            when {
                request.url.encodedPath.endsWith("getShareInfoByCodeV2.action") -> {
                    assertEquals("GET", request.method)
                    assertEquals("Abc123", request.url.queryParameter("shareCode"))
                    """{"fileId":"folder","isFolder":1,"needAccessCode":true,"shareMode":1}"""
                }
                request.url.encodedPath.endsWith("checkAccessCode.action") -> {
                    assertEquals("p123", request.url.queryParameter("accessCode"))
                    """{"shareId":12345}"""
                }
                else -> {
                    assertEquals("folder", request.url.queryParameter("shareDirFileId"))
                    assertEquals("share_12345=p123", request.header("Cookie"))
                    """{"fileListAO":{"count":1,"folderList":[],"fileList":[{"id":"file","name":"电影.mp4","size":100}]}}"""
                }
            }
        }
        val features = CloudProviderFeatures.from(C189ApiClient(http))
        val session = features.openShare("https://cloud.189.cn/web/share?code=Abc123&accessCode=p123", "")
        assertEquals("file", features.listShare(session, session.rootId).single().id)
        assertEquals(3, paths.size)
    }

    @Test
    fun tianyiFolderRenameAndBatchMoveUseCorrectKindsAndWaitForEveryItem() = runBlocking {
        val http = client { request, text ->
            val params = text.split('&').associate { pair -> val fields = pair.split('=', limit = 2); fields.first() to java.net.URLDecoder.decode(fields.getOrElse(1) { "" }, "UTF-8") }
            when {
                request.url.encodedPath.endsWith("renameFolder.action") -> {
                    assertEquals("folder", params["folderId"])
                    assertEquals("新名称", params["destFolderName"])
                    """{"id":"folder","name":"新名称"}"""
                }
                request.url.encodedPath.endsWith("createBatchTask.action") -> {
                    assertEquals("MOVE", params["type"])
                    assertEquals("target", params["targetFolderId"])
                    val files = org.json.JSONArray(params.getValue("taskInfos"))
                    assertEquals(2, files.length())
                    assertEquals(1, files.getJSONObject(0).getInt("isFolder"))
                    assertEquals(0, files.getJSONObject(1).getInt("isFolder"))
                    """{"taskId":"task"}"""
                }
                else -> """{"taskStatus":4,"failedCount":0,"successedCount":2,"subTaskCount":2}"""
            }
        }
        val api = C189ApiClient(http)
        assertTrue(api.renameFile("folder", "新名称", true).getOrThrow())
        assertTrue(api.moveFiles(listOf(com.fluxplayer.app.core.data.cloud189.C189FileItem("folder", "目录", true), com.fluxplayer.app.core.data.cloud189.C189FileItem("file", "电影", false)), "target").getOrThrow())
    }

    @Test
    fun mobileAndAliyunDoNotReportFailedAsyncMovesAsSuccess() = runBlocking {
        val mobile = client { request, _ ->
            if (request.url.encodedPath.endsWith("batchMove")) """{"code":0,"data":{"taskId":"task"}}""" else """{"code":0,"data":{"taskInfo":{"status":"failed"}}}"""
        }
        assertTrue(Yun139ApiClient(mobile).moveFiles(listOf("file"), "target").isFailure)
        val ali = client { request, text ->
            if (request.url.encodedPath.endsWith("/move")) {
                assertEquals("drive", JSONObject(text).getString("to_drive_id"))
                """{"async_task_id":"task"}"""
            } else """{"state":"failed"}"""
        }
        assertTrue(com.fluxplayer.app.core.data.aliyun.AliyunApiClient(ali).apply { driveId = "drive" }.moveFile("file", "target").isFailure)
    }

    @Test
    fun ucPersonalDirectoriesKeepReadingAfterFiftyFiles() = runBlocking {
        val pages = mutableListOf<String>()
        val http = client { request, _ ->
            assertEquals("50", request.url.queryParameter("_size"))
            val page = request.url.queryParameter("_page")!!
            pages.add(page)
            val files = if (page == "1") org.json.JSONArray((1..50).map { JSONObject().put("fid", "$it").put("file_name", "文件$it") }) else org.json.JSONArray().put(JSONObject().put("fid", "last").put("file_name", "最后文件"))
            JSONObject().put("code", 0).put("status", 200).put("data", JSONObject().put("list", files)).toString()
        }
        val api = QuarkApiClient(http).apply { setDriveType("uc") }
        assertEquals(51, CloudProviderFeatures.from(api, "uc").listPersonalFiles("0").size)
        assertEquals(listOf("1", "2"), pages)
    }

    @Test
    fun pan123FileManagementUsesSignedWebEndpointsAndChecksBusinessErrors() = runBlocking {
        val http = client { request, text ->
            assertEquals("yun.123pan.cn", request.url.host)
            assertNotNull(request.header("auth-value"))
            val body = JSONObject(text)
            when {
                request.url.encodedPath.endsWith("upload_request") -> {
                    assertEquals(1, body.getInt("type"))
                    """{"code":0,"data":{"Info":{"FileId":123}}}"""
                }
                request.url.encodedPath.endsWith("rename") -> """{"code":1,"message":"拒绝重命名"}"""
                else -> {
                    assertEquals(0L, body.getLong("parentFileId"))
                    assertEquals(123L, body.getJSONArray("fileIdList").getJSONObject(0).getLong("FileId"))
                    """{"code":0}"""
                }
            }
        }
        val api = Pan123ApiClient(http).apply { setToken("fixture") }
        assertTrue(api.createFolder("目录", "0").getOrThrow())
        assertTrue(api.renameFile("123", "新名称").isFailure)
        assertTrue(api.moveFile("123", "0").getOrThrow())
    }

    @Test
    fun quarkMoveUsesActionTypeOneAndDeleteUsesTwo() = runBlocking {
        val seen = mutableListOf<Pair<String, Int>>()
        val http = client { request, text ->
            val path = request.url.encodedPath
            if (path.endsWith("/file/move") || path.endsWith("/file/delete")) {
                seen += path.substringAfterLast("/") to JSONObject(text).optInt("action_type")
                """{"status":200,"code":0,"data":{}}"""
            } else {
                """{"status":200,"code":0,"data":{"list":[]}}"""
            }
        }
        val api = QuarkApiClient(http).apply { setCookie("__uid=user;__pus=a;__puus=b") }
        assertTrue(api.moveFiles(listOf("a"), "0").getOrThrow())
        assertTrue(api.deleteFiles(listOf("a")).getOrThrow())
        assertEquals(listOf("move" to 1, "delete" to 2), seen)
    }

    @Test
    fun pan123PersonalListingPagesWithLowercasePageAndUsesCursorWhenGiven() = runBlocking {
        val calls = mutableListOf<Pair<String?, String?>>()
        val http = client { request, _ ->
            val page = request.url.queryParameter("page")
            val cursor = request.url.queryParameter("next")
            calls += page to cursor
            // 自己网盘列表只认小写 page；大写 Page 属于分享接口，出现即回归
            assertEquals(null, request.url.queryParameter("Page"))
            when {
                cursor == null && page == "1" ->
                    """{"code":0,"data":{"InfoList":[{"FileId":1,"FileName":"a","Type":0,"Size":1}],"Next":"cursor-2"}}"""
                cursor == "cursor-2" ->
                    """{"code":0,"data":{"InfoList":[{"FileId":2,"FileName":"b","Type":0,"Size":1}],"Next":"-1"}}"""
                else -> """{"code":0,"data":{"InfoList":[],"Next":"-1"}}"""
            }
        }
        val api = Pan123ApiClient(http).apply { setToken("fixture") }
        val first = api.listFiles(parentFileId = "0").getOrThrow()
        assertEquals(1, first.items.size)
        assertEquals("cursor-2", first.nextCursor)
        assertTrue(first.hasMore)
        val second = api.listFiles(parentFileId = "0", next = checkNotNull(first.nextCursor)).getOrThrow()
        assertEquals(1, second.items.size)
        assertFalse(second.hasMore)
        // 首次请求不带 next（不能发 next=0，否则服务端按游标从头开始）；
        // 拿到游标后才带上，并按 wenxi 口径把 page 固定为 1、只推进游标
        assertEquals(listOf("1" to null, "1" to "cursor-2"), calls)
    }

    @Test
    fun quarkShareListingKeepsQuarkParamsAndIgnoresUcOnlyOnes() = runBlocking {
        val http = client { request, _ ->
            when {
                request.url.encodedPath.endsWith("share/sharepage/token") ->
                    """{"status":200,"code":0,"data":{"stoken":"st","first_fid":"root"}}"""
                request.url.encodedPath.endsWith("share/sharepage/detail") -> {
                    assertEquals("2", request.url.queryParameter("ver"))
                    assertEquals("file_type:asc,file_name:asc", request.url.queryParameter("_sort"))
                    assertEquals("100", request.url.queryParameter("_size"))
                    assertEquals(null, request.url.queryParameter("entry"))
                    assertEquals(null, request.url.queryParameter("fetch_file_list"))
                    assertEquals(null, request.url.queryParameter("passcode"))
                    """{"status":200,"code":0,"data":{"list":[{"fid":"f1","file_name":"x","dir":0,"size":1,"share_fid_token":"t"}]}}"""
                }
                else -> """{"status":200,"code":0,"data":{}}"""
            }
        }
        val features = CloudProviderFeatures.from(QuarkApiClient(http).apply { setCookie("__uid=u;__pus=a;__puus=b") }, "quark")
        val session = features.openShare("https://pan.quark.cn/s/abc", "1234")
        assertEquals(1, features.listShare(session, session.rootId).size)
    }

    @Test
    fun quarkOriginalPlaybackFollowsFastTransferChainAndRejectsForeignFid() = runBlocking {
        val paths = mutableListOf<String>()
        fun api(downloadFid: String = "f1"): QuarkApiClient {
            val http = client { request, _ ->
                val path = request.url.encodedPath
                paths += path.substringAfterLast("/")
                when {
                    path.endsWith("/msg/batch_send") ->
                        """{"status":200,"code":0,"data":{"send_msg_list":[{"fid":"f1","store_msg_id":"m1","conversation_id":"3000008641494773"}]}}"""
                    path.endsWith("/file/acquire_dl_token") -> """{"status":200,"code":0,"data":{"token":"tok"}}"""
                    path.endsWith("/file/download") ->
                        """{"status":200,"code":0,"data":[{"fid":"$downloadFid","download_url":"https://cdn.example.test/a.mp4"}]}"""
                    else -> """{"status":200,"code":0,"data":{}}"""
                }
            }
            return QuarkApiClient(http).apply { setCookie("__uid=user;__pus=a;__puus=b") }
        }
        // 快传三步：batch_send → acquire_dl_token → file/download
        assertEquals("https://cdn.example.test/a.mp4", api().getOriginalPlaybackUrl("f1", "a.mp4").getOrThrow())
        assertEquals(listOf("batch_send", "acquire_dl_token", "download"), paths)
        // 返回的文件不是所选文件时必须失败，不能把别人的地址交给播放器
        assertTrue(api(downloadFid = "other").getOriginalPlaybackUrl("f1", "a.mp4").isFailure)
    }

    @Test
    fun ucTvAnonymousHostDoesNotBlockQuarkPlaybackOnSameHost() {
        // 夸克与 UC 共用阿里 CDN 域名：UC TV 的预签名地址不能把域名永久标成匿名
        CloudPlayHeaders.clear()
        CloudPlayHeaders.activeProvider = "uc"
        CloudPlayHeaders.registerAnonymous("cdn.example.test", "uc")
        assertTrue(CloudPlayHeaders.isExplicitlyAnonymous("cdn.example.test"))

        // 换到夸克播放同一域名时必须重新注入夸克的头
        CloudPlayHeaders.activeProvider = "quark"
        assertFalse(CloudPlayHeaders.isExplicitlyAnonymous("cdn.example.test"))

        // 回到 UC 播放时匿名标记仍然生效
        CloudPlayHeaders.activeProvider = "uc"
        assertTrue(CloudPlayHeaders.isExplicitlyAnonymous("cdn.example.test"))
        CloudPlayHeaders.clear()
        assertFalse(CloudPlayHeaders.isExplicitlyAnonymous("cdn.example.test"))
    }

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
