package com.fluxplayer.app.core.data.cloud

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudUploadSourceTest {
    @Test
    fun sendsExactRangeWithoutLeakingOtherParts() = runBlocking {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val file = File.createTempFile("upload-test", ".bin")
        try {
            file.writeBytes(bytes)
            var sent = 0L
            val source = CloudUploadSource(file, "测试.bin", onProgress = { completed, total ->
                assertEquals(bytes.size.toLong(), total)
                sent = completed
            })
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                val body = checkNotNull(chain.request().body)
                assertEquals(80_000L, body.contentLength())
                body.writeTo(buffer)
                assertArrayEquals(bytes.copyOfRange(70_000, 150_000), buffer.readByteArray())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).header("ETag", "part-etag").build()
            }.build()
            assertEquals("part-etag", source.send(client, "https://upload.example/part", 70_000, 150_000))
            assertEquals(150_000L, sent)
            assertArrayEquals(MessageDigest.getInstance("MD5").digest(bytes.copyOfRange(70_000, 150_000)), source.digest("MD5", 70_000, 150_000))
        } finally {
            file.delete()
        }
    }

    @Test
    fun failsWhenSourceIsTruncated() = runBlocking {
        val file = File.createTempFile("upload-test", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val error = runCatching { CloudUploadSource(file, "test").digest("SHA-256", 0, 20) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
        } finally {
            file.delete()
        }
    }
}
