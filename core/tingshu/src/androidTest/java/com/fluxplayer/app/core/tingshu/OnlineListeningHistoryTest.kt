package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineListeningHistoryTest {
    @Test
    fun historyAndProgressRestoreBeforeSourcesLoadAndSurviveUnavailableSources() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(base.cacheDir, "online-history-$id").apply { mkdirs() }
        val preferences = base.getSharedPreferences("online-history-$id", Context.MODE_PRIVATE)
        val loadingSources = CountDownLatch(1)
        val releaseSources = CountDownLatch(1)
        val gatedPreferences = object : SharedPreferences by preferences {
            override fun getString(key: String?, defValue: String?): String? {
                if (key == "packages") {
                    loadingSources.countDown()
                    check(releaseSources.await(5, TimeUnit.SECONDS)) { "测试书源加载未释放" }
                }
                return preferences.getString(key, defValue)
            }
        }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String?, mode: Int) = if (name == "tingshu_library") gatedPreferences else super.getSharedPreferences(name, mode)
        }
        val key = "a".repeat(64)
        val brokenKey = "b".repeat(64)
        val books = File(directory, "tingshu/books").apply { mkdirs() }
        File(books, "$key.json").writeText(
            JSONObject().put("sourceId", "unavailable-source").put("url", "book-url").put("title", "在线历史书籍")
                .put("coverUrl", "").put("intro", "")
                .put("episodes", JSONArray().put(JSONObject().put("title", "第一集").put("url", "chapter-one"))).toString(),
        )
        File(books, "$brokenKey.json").writeText("损坏的书籍快照")
        preferences.edit().putString("recentBooks", JSONArray(listOf(brokenKey, key)).toString())
            .putString("progress.$key", JSONObject().put("url", "chapter-one").put("position", 12_000).put("duration", 60_000).toString())
            .putLong("playedAt.$key", 100).commit()
        // 独立上下文与仓库实例只用于验证冷启动，不修改用户书源或收听记录。
        val constructor = TingshuRepository::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        try {
            val repository = constructor.newInstance(context)
            assertTrue(loadingSources.await(3, TimeUnit.SECONDS))
            // 此时书源加载被阻塞，历史和进度必须已经完整恢复。
            assertEquals(key, repository.recentBooks.value.single().key)
            assertEquals(ListeningProgress("chapter-one", 12_000, 60_000), repository.progresses.value[key])
            releaseSources.countDown()
            val book = repository.recentBooks.value.single()
            repository.markPlayed(book)
            repository.markPlayed(book)
            assertEquals(1, repository.recentBooks.value.size)
            repository.saveProgress(key, ListeningProgress("chapter-one", 20_000, 60_000))
            val reopened = constructor.newInstance(context)
            withTimeout(3_000) { reopened.recentBooks.first { it.isNotEmpty() } }
            assertEquals("在线历史书籍", reopened.recentBooks.value.single().title)
            assertEquals(20_000L, reopened.progresses.value[key]?.position)
        } finally {
            releaseSources.countDown()
        }
    }
}
