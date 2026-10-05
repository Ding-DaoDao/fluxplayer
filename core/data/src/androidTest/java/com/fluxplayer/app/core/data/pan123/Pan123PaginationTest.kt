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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 回归测试：防止再次把分享接口的 `Page`/`next` 参数误用到自己网盘列目录接口。
 *
 * 背景：`listFiles` 曾写成 `Page`（大写）+ `next=0`，服务端静默忽略 `Page`，
 * page=2 仍返回第 1 页数据，「加载更多」表现为转圈后列表不动。
 */
@RunWith(AndroidJUnit4::class)
class Pan123PaginationTest {

    @Test
    fun listFilesUsesLowercasePageParameter() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            assertEquals("folder", url.queryParameter("parentFileId"))
            assertEquals("标题 & 第二集", url.queryParameter("SearchData"))
            // 大写 Page 属于分享接口，出现即视为回归
            assertNull("自己网盘列目录不应下发分享接口的 Page 参数", url.queryParameter("Page"))
            // Emulate an API that only honors lowercase page.
            val page = url.queryParameter("page")?.toInt() ?: 1
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(listResponse(listOf(page)).toResponseBody())
                .build()
        }.build()
        val api = Pan123ApiClient(client)
        val first = api.listFiles("folder", page = 1, searchData = "标题 & 第二集").getOrThrow()
        val second = api.listFiles("folder", page = 2, searchData = "标题 & 第二集").getOrThrow()
        assertEquals("1", first.items.single().fileId)
        assertEquals("2", second.items.single().fileId)
    }

    @Test
    fun noCursorParameterIsSentForOwnDriveListing() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            // next 是分享接口（shareFileDetails）的游标，自己网盘列目录不应携带
            assertNull("自己网盘列目录不应下发分享接口的 next 参数", url.queryParameter("next"))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(listResponse((1..100).toList()).toResponseBody())
                .build()
        }.build()
        Pan123ApiClient(client).listFiles().getOrThrow()
    }

    @Test
    fun fullPageCanLoadMore() = runBlocking {
        val result = apiWithResponse(listResponse((1..100).toList())).listFiles().getOrThrow()
        assertEquals(100, result.items.size)
        assertTrue("满 100 条应判定为还有下一页", result.hasMore)
    }

    @Test
    fun shortPageMeansNoMore() = runBlocking {
        val result = apiWithResponse(listResponse(listOf(1))).listFiles().getOrThrow()
        assertFalse("不足一页应判定为已到末页", result.hasMore)
    }

    @Test
    fun emptyPageStopsPagination() = runBlocking {
        val result = apiWithResponse(listResponse(emptyList())).listFiles().getOrThrow()
        assertTrue(result.items.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test
    fun cursorFieldDoesNotAffectHasMore() = runBlocking {
        // 即使响应异常带上下游标，分页判断也只依据 items.size，避免游标语义污染自己网盘
        val fullWithCursor = apiWithResponse(listResponse((1..100).toList(), next = "0")).listFiles().getOrThrow()
        assertTrue(fullWithCursor.hasMore)
        val shortWithCursor = apiWithResponse(listResponse(listOf(1), next = "0")).listFiles().getOrThrow()
        assertFalse(shortWithCursor.hasMore)
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

    private fun listResponse(ids: List<Int>, next: String? = null): String {
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
