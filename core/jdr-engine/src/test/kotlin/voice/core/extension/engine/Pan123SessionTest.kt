package voice.core.extension.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pan123SessionTest {
    @Test
    fun coversReuseLoginAndDownloadFailureDoesNotBreakDirectory(): Unit = runBlocking {
        val archive = JdrArchive.parse(checkNotNull(javaClass.getResourceAsStream("/netdisk/netdisk-1.1.4.jdr")).use { it.readBytes() })
        val metadata = archive.manifest.sources.single { it.id == "pan123" }
        val script = Pan123ScriptCompatibility.script(archive.scriptFor(metadata))
        val host = MemorySourceHost()
        var loginCount = 0
        val http = SandboxHttp { _, url, _ ->
            val body = when {
                url.contains("getconfig") -> """{"data":{"interfaceapi":{"login":"https://fixture.example/sign_in"}}}"""
                url.contains("sign_in") -> {
                    loginCount++
                    """{"message":"success","data":{"token":"fixture-token"}}"""
                }
                url.contains("download_info") -> """{"code":401,"message":"下载接口拒绝访问"}"""
                else -> """{"code":0,"data":{"InfoList":[{"FileId":1,"Type":0,"FileName":"01.mp3","Etag":"hash","S3KeyFlag":"bucket","Size":123},{"FileId":2,"Type":0,"FileName":"cover.jpg","Thumbnail":"https://fixture.example/cover.jpg"}]}}"""
            }
            val encodedBody = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), body)
            """{"status":200,"headers":{},"body":$encodedBody}"""
        }
        val parameters = """{"bookId":"D_book","chapterId":"1","config":{"passport":"fixture-user","password":"fixture-password"}}"""
        JsSourceEngine.create("pan123", script, "pan123.js", http, host = host).use { main ->
            assertEquals(1, SourceContract.parseChapters(main.invoke("chapters", parameters, 5_000)).size)
            assertNotNull(host.get("login"))
            repeat(4) {
                JsSourceEngine.create("pan123", script, "pan123.js", http, host = host).use { cover ->
                    assertEquals("https://fixture.example/cover.jpg", SourceContract.parseAudio(cover.invoke("cover", parameters, 5_000)).url)
                }
            }
            assertEquals("封面沙箱应复用会话", 1, loginCount)
            val failure = runCatching { main.invoke("audio", parameters, 5_000) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("401"))
            assertNotNull("下载被拒绝不应删除登录态", host.get("login"))
            assertEquals(1, SourceContract.parseChapters(main.invoke("chapters", parameters, 5_000)).size)
            assertEquals("下载失败后目录仍复用原会话", 1, loginCount)
            // 模拟离开再进入书源，新沙箱也应恢复会话。
            JsSourceEngine.create("pan123", script, "pan123.js", http, host = host).use { reopened ->
                assertEquals(1, SourceContract.parseChapters(reopened.invoke("chapters", parameters, 5_000)).size)
            }
            assertEquals(1, loginCount)
        }
    }
}
