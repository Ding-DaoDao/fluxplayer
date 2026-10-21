package com.fluxplayer.app.feature.tingshu

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.fluxplayer.app.core.tingshu.ListeningResource
import com.fluxplayer.app.core.ui.cache.BookCoverCache
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookCoverCacheTest {
    @Test
    fun initiallyMissingCoverCanRecoverWithoutReopeningPage(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        val image = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image)))
        server.start()
        val attempts = mutableListOf<Boolean>()
        try {
            val request = ImageRequest.Builder(context).data(SourceCover("initially-missing-cover"))
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
                .fetcherFactory(
                    SourceCoverFetcher { fresh ->
                        attempts.add(fresh)
                        if (fresh) ListeningResource(server.url("/cover.png").toString(), emptyMap()) else null
                    },
                ).build()
            assertTrue(BookCoverCache.imageLoader(context).execute(request) is SuccessResult)
            assertEquals(listOf(false, true), attempts)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun transientFailureRetriesWithFreshResolutionAndCachesSuccessfulImage(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        val image = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image)))
        server.start()
        val enabled = BookCoverCache.enabled(context).value
        val resolutions = mutableListOf<Boolean>()
        try {
            BookCoverCache.clear(context)
            BookCoverCache.setEnabled(context, true)
            val loader = BookCoverCache.imageLoader(context)
            val factory = SourceCoverFetcher { fresh ->
                resolutions.add(fresh)
                ListeningResource(server.url(if (fresh) "/new.png" else "/expired.png").toString(), emptyMap())
            }
            val request = ImageRequest.Builder(context).data(SourceCover("expired-image-test"))
                .fetcherFactory(factory).diskCacheKey("retry-image-test").memoryCachePolicy(CachePolicy.DISABLED).build()
            assertTrue(loader.execute(request) is SuccessResult)
            assertEquals(listOf(false, true), resolutions)
            assertEquals("/expired.png", server.takeRequest().path)
            val retry = server.takeRequest()
            assertEquals("/new.png", retry.path)
            assertEquals("no-cache", retry.getHeader("Cache-Control"))
            assertTrue(loader.execute(request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()) is SuccessResult)
            assertEquals(2, resolutions.size)
        } finally {
            BookCoverCache.clear(context)
            BookCoverCache.setEnabled(context, enabled)
            server.shutdown()
        }
    }

    @Test
    fun resolutionFailureRetriesOnceAndPermanentFailureStops(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = BookCoverCache.imageLoader(context)
        val attempts = mutableListOf<Boolean>()
        val request = ImageRequest.Builder(context).data(SourceCover("permanent-failure"))
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
            .fetcherFactory(
                SourceCoverFetcher { fresh ->
                    attempts.add(fresh)
                    throw IOException("模拟暂时不可用")
                },
            ).build()
        assertTrue(loader.execute(request) is ErrorResult)
        assertEquals(listOf(false, true), attempts)
    }

    @Test
    fun manualRefreshReplacesCoverAtSameAddressAndKeepsOfflineCache(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        for (color in listOf(android.graphics.Color.RED, android.graphics.Color.BLUE)) {
            val bytes = ByteArrayOutputStream().apply {
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                    .compress(Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
        }
        server.start()
        val enabled = BookCoverCache.enabled(context).value
        val calls = AtomicInteger()
        try {
            BookCoverCache.clear(context)
            BookCoverCache.setEnabled(context, true)
            val loader = BookCoverCache.imageLoader(context)
            val factory = SourceCoverFetcher {
                calls.incrementAndGet()
                ListeningResource(server.url("/cover.png").toString(), emptyMap())
            }
            val request = ImageRequest.Builder(context).data(SourceCover("same-cover"))
                .fetcherFactory(factory).diskCacheKey("refresh-cover").memoryCachePolicy(CachePolicy.DISABLED).build()
            val first = loader.execute(request) as SuccessResult
            assertEquals(android.graphics.Color.RED, first.image.toBitmap().copy(Bitmap.Config.ARGB_8888, false).getPixel(0, 0))
            server.takeRequest()
            val refresh = request.newBuilder().data(SourceCover("same-cover", forceRefresh = true))
                .diskCachePolicy(CachePolicy.WRITE_ONLY).build()
            val updated = loader.execute(refresh) as SuccessResult
            assertEquals(android.graphics.Color.BLUE, updated.image.toBitmap().copy(Bitmap.Config.ARGB_8888, false).getPixel(0, 0))
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
            val offline = loader.execute(request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()) as SuccessResult
            assertEquals(android.graphics.Color.BLUE, offline.image.toBitmap().copy(Bitmap.Config.ARGB_8888, false).getPixel(0, 0))
            assertEquals(2, calls.get())
            assertEquals(2, server.requestCount)
        } finally {
            BookCoverCache.clear(context)
            BookCoverCache.setEnabled(context, enabled)
            server.shutdown()
        }
    }

    @Test
    fun cachedSourceCoverSkipsResolutionAndWorksWithExpiredCredentials(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        val image = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image)))
        server.start()
        val enabled = BookCoverCache.enabled(context).value
        val calls = AtomicInteger()
        try {
            BookCoverCache.clear(context)
            BookCoverCache.setEnabled(context, true)
            val loader = BookCoverCache.imageLoader(context)
            val factory = SourceCoverFetcher {
                check(calls.incrementAndGet() == 1) { "已缓存封面不应重新解析源" }
                ListeningResource(server.url("/cover.png").toString(), mapOf("X-Cover-Test" to "source"))
            }
            val request = ImageRequest.Builder(context).data(SourceCover("jdr-cover://book?id=42"))
                .fetcherFactory(factory).diskCacheKey("lazy-cover-test").memoryCacheKey("lazy-cover-test")
                .memoryCachePolicy(CachePolicy.DISABLED).build()
            assertTrue(loader.execute(request) is SuccessResult)
            assertEquals("source", server.takeRequest().getHeader("X-Cover-Test"))
            val offline = request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
            BookCoverCache.setEnabled(context, false)
            assertTrue(BookCoverCache.imageLoader(context, false).execute(offline) is SuccessResult)
            assertEquals(1, calls.get())
            assertEquals(1, server.requestCount)
            BookCoverCache.clear(context)
            assertTrue(loader.execute(offline) !is SuccessResult)
            assertEquals(1, calls.get())
        } finally {
            BookCoverCache.clear(context)
            BookCoverCache.setEnabled(context, enabled)
            server.shutdown()
        }
    }

    @Test
    fun sharedCoverCachePreservesHeadersReadsOfflineAndClears(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        val image = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image)))
        server.start()
        val enabled = BookCoverCache.enabled(context).value
        try {
            BookCoverCache.clear(context)
            BookCoverCache.setEnabled(context, true)
            val loader = BookCoverCache.imageLoader(context)
            val request = ImageRequest.Builder(context).data(server.url("/cover.png").toString())
                .diskCacheKey("cover-test")
                .memoryCachePolicy(CachePolicy.DISABLED)
                .httpHeaders(NetworkHeaders.Builder().set("X-Cover-Test", "fixture").build()).build()
            assertTrue(loader.execute(request) is SuccessResult)
            assertEquals("fixture", server.takeRequest().getHeader("X-Cover-Test"))
            assertTrue(BookCoverCache.size(context) > 0)
            val offline = request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
            BookCoverCache.setEnabled(context, false)
            assertTrue(BookCoverCache.imageLoader(context, false).execute(offline) is SuccessResult)
            assertEquals(1, server.requestCount)
            BookCoverCache.clear(context)
            assertEquals(0L, BookCoverCache.size(context))
            assertTrue(loader.execute(offline) !is SuccessResult)
        } finally {
            BookCoverCache.clear(context)
            BookCoverCache.setEnabled(context, enabled)
            server.shutdown()
        }
    }
}
