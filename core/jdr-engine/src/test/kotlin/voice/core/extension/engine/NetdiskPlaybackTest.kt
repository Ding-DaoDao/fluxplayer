package voice.core.extension.engine

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class NetdiskPlaybackTest {
    private fun archive(): JdrArchive = JdrArchive.parse(checkNotNull(javaClass.getResourceAsStream("/netdisk/netdisk-1.1.4.jdr")).use { it.readBytes() })

    @Test
    fun quarkChapterPaginationCanWaitAndContinue(): Unit = runBlocking {
        val archive = archive()
        val source = archive.manifest.sources.single { it.id == "quark" }
        val host = MemorySourceHost().apply { putSetting("quark_cookie", "fixture=ok") }
        var calls = 0
        val http = SandboxHttp { _, _, _ ->
            calls++
            """{"status":200,"headers":{},"body":"{\"code\":0,\"data\":{\"list\":[{\"fid\":\"$calls\",\"file_name\":\"$calls.mp3\",\"file\":true,\"format_type\":\"audio\"}]},\"metadata\":{\"_total\":2}}"}"""
        }
        JsSourceEngine.create(source.id, archive.scriptFor(source), source.script, http, host = host).use { engine ->
            val chapters = SourceContract.parseChapters(engine.invoke("chapters", """{"bookId":"D_book","config":{}}""", 5_000))
            assertEquals(listOf("1", "2"), chapters.map { it.id })
            assertEquals(2, calls)
        }
    }

    @Test
    fun pan123DecodesDownloadAddressAndResolves210Redirect(): Unit = runBlocking {
        val archive = archive()
        val source = archive.manifest.sources.single { it.id == "pan123" }
        val encoded = Base64.getEncoder().encodeToString("https://fixture.example/probe".toByteArray())
        var calls = 0
        val http = SandboxHttp { _, url, options ->
            calls++
            if (url.contains("download_info")) {
                val body = Json.parseToJsonElement(options).jsonObject["json"]!!.jsonObject
                assertEquals("hash", body["etag"]!!.jsonPrimitive.content)
                assertEquals("bucket", body["s3keyFlag"]!!.jsonPrimitive.content)
                assertEquals("1234", body["size"]!!.jsonPrimitive.content)
                """{"status":200,"headers":{},"body":"{\"code\":0,\"data\":{\"DownloadUrl\":\"https://fixture.example/download?params=$encoded\"}}"}"""
            } else {
                assertEquals("https://fixture.example/probe", url)
                assertEquals("probe", Json.parseToJsonElement(options).jsonObject["responseMode"]!!.jsonPrimitive.content)
                """{"status":210,"headers":{"content-type":"application/json"},"body":"{\"data\":{\"redirect_url\":\"https://fixture.example/final.mp3\"}}"}"""
            }
        }
        JsSourceEngine.create(source.id, archive.scriptFor(source), source.script, http).use { engine ->
            val audio = SourceContract.parseAudio(engine.invoke("audio", """{"chapterId":"12","etag":"hash","s3":"bucket","size":"1234","fname":"第1集.mp3","config":{"token":"fixture"}}""", 5_000))
            assertEquals("https://fixture.example/final.mp3", audio.url)
            assertEquals(2, calls)
        }
    }

    @Test
    fun httpProbeSkipsAudioBodyAndReturnsFinalRedirectUrl(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/redirect") {
                    MockResponse().setResponseCode(302).addHeader("Location", "/audio")
                } else {
                    MockResponse().addHeader("Content-Type", "audio/mpeg").setBody("audio".repeat(1000)).setBodyDelay(5, TimeUnit.SECONDS)
                }
            }
            server.start()
            val started = System.nanoTime()
            val response = Json.parseToJsonElement(OkHttpSandboxHttp(OkHttpClient()).request("GET", server.url("/redirect").toString(), """{"responseMode":"probe","timeoutMs":1000}""")).jsonObject
            assertEquals("", response["body"]!!.jsonPrimitive.content)
            assertEquals(server.url("/audio").toString(), response["url"]!!.jsonPrimitive.content)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
        }
    }

    @Test
    fun httpProbePreserves210JsonEvenWithoutContentType(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val body = """{"data":{"redirect_url":"https://fixture.example/audio.mp3"}}"""
            server.enqueue(MockResponse().setResponseCode(210).setBody(body))
            val response = Json.parseToJsonElement(OkHttpSandboxHttp(OkHttpClient()).request("GET", server.url("/probe").toString(), """{"responseMode":"probe"}""")).jsonObject
            assertEquals(body, response["body"]!!.jsonPrimitive.content)
            assertEquals("application/json", response["headers"]!!.jsonObject["content-type"]!!.jsonPrimitive.content)
        }
    }
}
