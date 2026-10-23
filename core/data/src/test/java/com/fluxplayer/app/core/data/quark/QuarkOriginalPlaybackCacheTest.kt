package com.fluxplayer.app.core.data.quark

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarkOriginalPlaybackCacheTest {
    @Test
    fun concurrentRequestsShareOneFastTransferAndAccountsStaySeparate() = runBlocking {
        withTimeout(3000) {
            val cache = QuarkOriginalPlaybackCache()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val started = CompletableDeferred<Unit>()
            val first = async {
                cache.resolve("account-a", "file") {
                    calls++
                    started.complete(Unit)
                    release.await()
                    "https://cdn/a"
                }
            }
            started.await()
            val second = async {
                cache.resolve("account-a", "file") {
                    calls++
                    "https://cdn/duplicate"
                }
            }
            yield()
            release.complete(Unit)
            assertEquals(listOf("https://cdn/a", "https://cdn/a"), listOf(first, second).awaitAll())
            assertEquals(1, calls)
            assertEquals(
                "https://cdn/b",
                cache.resolve("account-b", "file") {
                    calls++
                    "https://cdn/b"
                },
            )
            assertEquals(2, calls)
        }
    }

    @Test
    fun expiredUrlsAndFailuresAreNeverReused() = runBlocking {
        var now = 1000L
        val cache = QuarkOriginalPlaybackCache { now }
        var calls = 0
        assertTrue(
            runCatching {
                cache.resolve("account", "file") {
                    calls++
                    error("取址失败")
                }
            }.isFailure,
        )
        assertEquals(
            "first",
            cache.resolve("account", "file") {
                calls++
                "first"
            },
        )
        assertEquals(
            "first",
            cache.resolve("account", "file") {
                calls++
                "duplicate"
            },
        )
        assertEquals(2, calls)
        now += 60_000
        assertEquals(
            "renewed",
            cache.resolve("account", "file") {
                calls++
                "renewed"
            },
        )
        assertEquals(3, calls)
    }

    @Test
    fun cancellingOriginalPlaybackCancelsThePendingHttpCall() = runBlocking {
        withTimeout(3000) {
            val started = CompletableDeferred<okhttp3.Call>()
            val release = CountDownLatch(1)
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                started.complete(chain.call())
                release.await(3, TimeUnit.SECONDS)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{\"code\":0,\"data\":{}}".toResponseBody()).build()
            }.build()
            try {
                val request = async { QuarkApiClient(http).getOriginalPlaybackUrl("cancel-file") }
                val call = started.await()
                request.cancelAndJoin()
                assertTrue(call.isCanceled())
            } finally {
                release.countDown()
            }
        }
    }
}
