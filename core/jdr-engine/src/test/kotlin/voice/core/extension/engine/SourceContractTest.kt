package voice.core.extension.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceContractTest {
    @Test
    fun audioObjectPreservesHeadersAndDropsNewlines() {
        val audio = SourceContract.parseAudio(
            """{"url":"https://example.com/audio.m3u8","headers":{
                "Referer":"https://example.com/","Cookie":"session=ok",
                "Bad":"value\r\nInjected: bad","Bad:Name":"value"
            }}""",
        )
        assertEquals("https://example.com/audio.m3u8", audio.url)
        assertEquals(mapOf("Referer" to "https://example.com/", "Cookie" to "session=ok"), audio.headers)
        assertEquals("https://example.com/1.mp3", SourceContract.parseAudio("\"https://example.com/1.mp3\"").url)
    }

    @Test
    fun rejectsNonHttpAudioAndMalformedResults() {
        assertFailsWith<SourceContractException> { SourceContract.parseAudio("\"file:///private\"") }
        assertFailsWith<SourceContractException> { SourceContract.parseSearchResults("{}") }
        assertFailsWith<SourceContractException> { SourceContract.parseChapters("[{\"title\":\"missing id\"}]") }
    }
}
