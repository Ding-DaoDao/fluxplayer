package com.fluxplayer.app.feature.tingshu

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.fluxplayer.app.core.ui.cache.BookCoverCache
import java.io.ByteArrayOutputStream
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
