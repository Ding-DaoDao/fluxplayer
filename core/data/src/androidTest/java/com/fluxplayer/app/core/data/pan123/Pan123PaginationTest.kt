package com.fluxplayer.app.core.data.pan123

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Pan123PaginationTest {
    @Test
    fun secondPageUsesCaseSensitivePageParameter() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            assertEquals("0", url.queryParameter("next"))
            assertEquals("folder", url.queryParameter("parentFileId"))
            assertEquals("标题 & 第二集", url.queryParameter("SearchData"))
            assertEquals(null, url.queryParameter("page"))
            // Emulate an API that falls back to page 1 when Page is missing.
            val page = url.queryParameter("Page")?.toInt() ?: 1
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(listResponse(listOf(page), "-1").toResponseBody())
                .build()
        }.build()
        val api = Pan123ApiClient(client)
        val first = api.listFiles("folder", page = 1, searchData = "标题 & 第二集").getOrThrow()
        val second = api.listFiles("folder", page = 2, searchData = "标题 & 第二集").getOrThrow()
        assertEquals("1", first.items.single().fileId)
        assertEquals("2", second.items.single().fileId)
    }

    @Test
    fun fullLastPageHonorsEndMarker() = runBlocking {
        val result = apiWithResponse(listResponse((1..100).toList(), "-1")).listFiles().getOrThrow()
        assertEquals(100, result.items.size)
        assertEquals("-1", result.nextCursor)
        assertFalse(result.hasMore)
    }

    @Test
    fun shortPageWithContinuationMarkerCanLoadMore() = runBlocking {
        val result = apiWithResponse(listResponse(listOf(1), "0")).listFiles().getOrThrow()
        assertTrue(result.hasMore)
    }

    @Test
    fun missingMarkerFallsBackToPageSizeAndEmptyPagesStop() = runBlocking {
        assertTrue(apiWithResponse(listResponse((1..100).toList(), null)).listFiles().getOrThrow().hasMore)
        assertFalse(apiWithResponse(listResponse(listOf(1), null)).listFiles().getOrThrow().hasMore)
        assertFalse(apiWithResponse(listResponse(emptyList(), "0")).listFiles().getOrThrow().hasMore)
    }

    private fun apiWithResponse(body: String): Pan123ApiClient {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody())
                .build()
        }.build()
        return Pan123ApiClient(client)
    }

    private fun listResponse(ids: List<Int>, next: String?): String {
        val items = JSONArray()
        ids.forEach { id ->
            items.put(
                JSONObject()
                    .put("FileId", id.toString())
                    .put("FileName", "$id.mp4")
                    .put("Type", 0),
            )
        }
        val data = JSONObject().put("InfoList", items)
        if (next != null) data.put("Next", next)
        return JSONObject().put("code", 0).put("data", data).toString()
    }
}
