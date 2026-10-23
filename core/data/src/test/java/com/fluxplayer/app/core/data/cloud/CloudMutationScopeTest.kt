package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.BaseCloudApiClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudMutationScopeTest {
    private class Probe(client: OkHttpClient) : BaseCloudApiClient(client) {
        suspend fun write(url: String): Int = executeRequestAndGetResponse(Request.Builder().url(url).post("{}".toRequestBody()).build()).use { it.code }
    }

    @Test fun serviceUnavailableWithZeroRetryAfterDoesNotReplayMutation() = runBlocking { verify(503) }

    @Test fun redirectDoesNotReplayMutationAtNewAddress() = runBlocking { verify(307) }

    private suspend fun verify(status: Int) {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            if (status == 503) exchange.responseHeaders.add("Retry-After", "0")
            if (status == 307) exchange.responseHeaders.add("Location", "/other")
            exchange.sendResponseHeaders(if (exchange.requestURI.path == "/other") 200 else status, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = cloudMutation { Probe(OkHttpClient()).write("http://127.0.0.1:${server.address.port}/operation") }
            assertEquals(status, result)
            assertEquals(1, calls.get())
        } finally {
            server.stop(0)
        }
    }
}
