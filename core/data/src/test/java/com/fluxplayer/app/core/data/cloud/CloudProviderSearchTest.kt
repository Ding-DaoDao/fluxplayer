package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.yun139.Yun139ApiClient
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudProviderSearchTest {
    @After
    fun clear() {
        C189AuthProvider.clear()
        Yun139AuthProvider.clear()
    }

    @Test fun aliyunSearchUsesNestedDirectoryAndCategory() = runBlocking { verify("alipan") }

    @Test fun quarkSearchUsesNestedDirectoryAndCategory() = runBlocking { verify("quark") }

    @Test fun ucSearchUsesNestedDirectoryAndCategory() = runBlocking { verify("uc") }

    @Test fun pan123SearchUsesNestedDirectoryAndCategory() = runBlocking { verify("pan123") }

    @Test fun mobileSearchUsesNestedDirectoryAndCategory() = runBlocking { verify("yun139") }

    @Test fun tianyiSearchUsesNestedDirectoryAndCategory() = runBlocking { verify("cloud189") }

    @Test
    fun tianyiUsesActualReceivedCountWhenServerCapsPageSize() = runBlocking {
        val pages = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val page = chain.request().url.queryParameter("pageNum")!!
            pages.add(page)
            val files = if (page == "1") """[{"id":"1","name":"a.mp4"},{"id":"2","name":"b.mp4"}]""" else """[{"id":"3","name":"c.mp4"}]"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"fileListAO":{"count":3,"folderList":[],"fileList":$files}}""".toResponseBody()).build()
        }.build()
        C189AuthProvider.accessToken = "fixture"
        val files = CloudProviderFeatures.from(C189ApiClient(http)).listPersonalFiles("-11")
        assertEquals(listOf("1", "2", "3"), files.map { it.id })
        assertEquals(listOf("1", "2"), pages)
    }

    private suspend fun verify(provider: String) {
        val parents = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val body = runCatching { JSONObject(buffer.readUtf8()) }.getOrElse { JSONObject() }
            val parent = request.url.queryParameter("pdir_fid") ?: request.url.queryParameter("parentFileId")
                ?: request.url.queryParameter("folderId") ?: body.optString("parent_file_id").ifBlank { body.optString("parentFileId") }
            parents.add(parent)
            val nested = parent == "10"
            val response = when (provider) {
                "alipan" -> if (nested) """{"items":[{"file_id":"20","name":"电影.MKV","type":"file"},{"file_id":"21","name":"电影.pdf","type":"file"}]}""" else """{"items":[{"file_id":"10","name":"影库","type":"folder"}]}"""
                "quark", "uc" -> if (nested) """{"status":200,"code":0,"data":{"list":[{"fid":"20","file_name":"电影.MKV","dir":false},{"fid":"21","file_name":"电影.pdf","dir":false}]}}""" else """{"status":200,"code":0,"data":{"list":[{"fid":"10","file_name":"影库","dir":true}]}}"""
                "pan123" -> if (nested) """{"code":0,"data":{"InfoList":[{"FileId":20,"FileName":"电影.MKV","Type":0,"etag":"etag","S3KeyFlag":"s3"},{"FileId":21,"FileName":"电影.pdf","Type":0}],"Next":"-1"}}""" else """{"code":0,"data":{"InfoList":[{"FileId":10,"FileName":"影库","Type":1}],"Next":"-1"}}"""
                "yun139" -> if (nested) """{"success":true,"data":{"items":[{"fileId":"20","name":"电影.MKV","type":"file"},{"fileId":"21","name":"电影.pdf","type":"file"}]}}""" else """{"success":true,"data":{"items":[{"fileId":"10","name":"影库","type":"folder"}]}}"""
                else -> if (nested) """{"fileListAO":{"count":2,"folderList":[],"fileList":[{"id":"20","name":"电影.MKV"},{"id":"21","name":"电影.pdf"}]}}""" else """{"fileListAO":{"count":1,"folderList":[{"id":"10","name":"影库"}],"fileList":[]}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(response.toResponseBody()).build()
        }.build()
        val features = when (provider) {
            "alipan" -> CloudProviderFeatures.from(
                AliyunApiClient(http).apply {
                    authorization = "token"
                    driveId = "drive"
                },
            )
            "quark", "uc" -> CloudProviderFeatures.from(QuarkApiClient(http, provider).apply { setCookie("__uid=test") })
            "pan123" -> CloudProviderFeatures.from(Pan123ApiClient(http).apply { setToken("fixture") })
            "yun139" -> CloudProviderFeatures.from(Yun139ApiClient(http))
            else -> {
                C189AuthProvider.accessToken = "fixture"
                CloudProviderFeatures.from(C189ApiClient(http))
            }
        }
        val cache = CloudDirectorySearchCache()
        val result = searchCloudTree("电影", features.rootId, "网盘", features::listPersonalFiles, {}, cache, CloudSearchCategory.Video)
        assertEquals(listOf(features.rootId, "10"), parents)
        assertEquals(listOf("20"), result.hits.map { it.file.id })
        assertEquals("10", result.hits.single().file.parentId)
        val second = searchCloudTree("电影", "10", "当前文件夹", features::listPersonalFiles, {}, cache, CloudSearchCategory.Document)
        assertEquals(listOf("21"), second.hits.map { it.file.id })
        assertEquals(2, parents.size)
    }
}
