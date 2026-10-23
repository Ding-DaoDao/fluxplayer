package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.yun139.Yun139ApiClient
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBatchProviderTest {
    private val files = listOf(CloudRemoteFile("11", "文件.mp4", false, 7, parentId = "source"), CloudRemoteFile("12", "目录", true, parentId = "source"))

    private fun http(handler: (Request, String) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val text = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(handler(chain.request(), text).toResponseBody()).build()
    }.build()

    @After fun clear() {
        C189AuthProvider.clear()
        Yun139AuthProvider.clear()
    }

    @Test fun quarkSendsBothIdsAndWaitsForTask() = runBlocking { verifyQuark("quark") }

    @Test fun ucSendsBothIdsAndWaitsForTask() = runBlocking { verifyQuark("uc") }

    private suspend fun verifyQuark(provider: String) {
        var writes = 0
        var polls = 0
        val api = QuarkApiClient(
            http { request, text ->
                if (request.url.encodedPath.endsWith("/task")) {
                    polls++
                    """{"status":200,"code":0,"data":{"status":2}}"""
                } else {
                    writes++
                    val body = JSONObject(text)
                    assertEquals(listOf("11", "12"), body.getJSONArray("filelist").let { (0 until it.length()).map(it::getString) })
                    assertEquals(if (writes == 1) 1 else 2, body.getInt("action_type"))
                    if (writes == 1) assertEquals("target", body.getString("to_pdir_fid"))
                    assertEquals(provider == "quark", request.url.queryParameterNames.contains("uc_param_str"))
                    """{"status":200,"code":0,"data":{"task_id":"task"}}"""
                }
            },
            provider,
        )
        val features = CloudProviderFeatures.from(api)
        features.manageFiles(files, "target")
        features.manageFiles(files, null)
        assertEquals(2, writes)
        assertEquals(2, polls)
    }

    @Test fun aliyunBatchValidatesRepliesAndWaitsForAsyncTasks() = runBlocking {
        var batches = 0
        var polls = 0
        val api = AliyunApiClient(
            http { request, text ->
                if (request.url.encodedPath.endsWith("async_task/get")) {
                    polls++
                    """{"state":"Succeed"}"""
                } else {
                    batches++
                    assertEquals("/adrive/v4/batch", request.url.encodedPath)
                    val body = JSONObject(text)
                    val entries = body.getJSONArray("requests")
                    assertEquals(2, entries.length())
                    assertEquals(if (batches == 1) "/file/move" else "/recyclebin/trash", entries.getJSONObject(0).getString("url"))
                    assertEquals("drive", entries.getJSONObject(0).getJSONObject("body").getString("drive_id"))
                    if (batches == 1) assertEquals("refuse", entries.getJSONObject(0).getJSONObject("body").getString("check_name_mode"))
                    """{"responses":[{"id":"1","status":200,"body":{"async_task_id":"task"}},{"id":"0","status":200,"body":{"code":0}}]}"""
                }
            },
        ).apply {
            authorization = "fixture"
            driveId = "drive"
        }
        val features = CloudProviderFeatures.from(api)
        features.manageFiles(files, "target")
        features.manageFiles(files, null)
        assertEquals(2, batches)
        assertEquals(2, polls)
    }

    @Test fun aliyunPartialFailureIsNotReportedAsSuccess() = runBlocking {
        val api = AliyunApiClient(http { _, _ -> """{"responses":[{"id":"0","status":200,"body":{"file_id":"11"}},{"id":"1","status":403,"body":{"code":"Forbidden"}}]}""" }).apply {
            authorization = "fixture"
            driveId = "drive"
        }
        assertTrue(api.manageFiles(files.map { it.id }, null).isFailure)
    }

    @Test fun pan123KeepsFullRawEntriesForDeletionAndUsesNumericIdsForMove() = runBlocking {
        var writes = 0
        val api = Pan123ApiClient(
            http { request, text ->
                when {
                    request.url.encodedPath.endsWith("/list/new") || request.method == "GET" ->
                        """{"code":0,"data":{"InfoList":[{"FileId":11,"FileName":"文件.mp4","Type":0,"Size":7,"Pid":99,"Status":1,"Category":2,"Etag":"digest","S3KeyFlag":"key"}],"Next":"-1"}}"""
                    request.url.encodedPath.endsWith("mod_pid") -> {
                        writes++
                        val body = JSONObject(text)
                        assertEquals(2, body.getJSONArray("fileIdList").length())
                        assertTrue(body.getJSONArray("fileIdList").getJSONObject(0).get("FileId") is Number)
                        assertEquals(20, body.getInt("parentFileId"))
                        """{"code":0}"""
                    }
                    else -> {
                        writes++
                        val item = JSONObject(text).getJSONArray("fileTrashInfoList").getJSONObject(0)
                        assertEquals(99, item.getInt("Pid"))
                        assertEquals(2, item.getInt("Category"))
                        assertEquals("digest", item.getString("Etag"))
                        """{"code":0}"""
                    }
                }
            },
        ).apply { setToken("fixture") }
        val features = CloudProviderFeatures.from(api)
        val listed = features.listPersonalFiles("99")
        features.manageFiles(listed, null)
        features.manageFiles(files, "20")
        assertEquals(2, writes)
    }

    @Test fun mobileSubmitsBothIdsAndPollsBothOperations() = runBlocking {
        var writes = 0
        var polls = 0
        val features = CloudProviderFeatures.from(
            Yun139ApiClient(
                http { request, text ->
                    if (request.url.encodedPath.endsWith("task/get")) {
                        polls++
                        """{"success":true,"data":{"taskInfo":{"status":"success","progress":100},"batchFileResults":[{"errCode":"0"},{"errCode":"0"}]}}"""
                    } else {
                        writes++
                        val body = JSONObject(text)
                        assertEquals(2, body.getJSONArray("fileIds").length())
                        if (writes == 1) assertEquals("target", body.getString("toParentFileId"))
                        """{"success":true,"data":{"taskId":"task"}}"""
                    }
                },
            ),
        )
        features.manageFiles(files, "target")
        features.manageFiles(files, null)
        assertEquals(2, writes)
        assertEquals(2, polls)
    }

    @Test fun tianyiUsesMixedFileFolderTaskAndChecksCompletedCount() = runBlocking {
        var writes = 0
        var polls = 0
        val features = CloudProviderFeatures.from(
            C189ApiClient(
                http { request, text ->
                    val params = text.split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                    if (request.url.encodedPath.endsWith("checkBatchTask.action")) {
                        polls++
                        """{"taskStatus":4,"failedCount":0,"successedCount":2,"subTaskCount":2}"""
                    } else {
                        writes++
                        assertEquals(if (writes == 1) "MOVE" else "DELETE", params["type"])
                        val tasks = org.json.JSONArray(params.getValue("taskInfos"))
                        assertEquals(2, tasks.length())
                        assertEquals(0, tasks.getJSONObject(0).getInt("isFolder"))
                        assertEquals(1, tasks.getJSONObject(1).getInt("isFolder"))
                        """{"taskId":"task"}"""
                    }
                },
            ),
        )
        features.manageFiles(files, "target")
        features.manageFiles(files, null)
        assertEquals(2, writes)
        assertEquals(2, polls)
    }
}
