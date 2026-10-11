package voice.core.extension.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceContractTest {
    @Test
    fun searchPaginationKeepsLegacyArraysAndExtras() {
        val item = """[{"id":"book","bookTitle":"书名","token":"keep"}]"""
        assertEquals(1, SourceContract.parseSearchPage(item).totalPages)
        assertEquals(emptyList(), SourceContract.parseSearchPage(item, 2).items)
        val page = SourceContract.parseSearchPage("""{"items":$item,"totalPages":3}""", 2)
        assertEquals(3, page.totalPages)
        assertEquals("keep", page.items.single().extra["token"]?.toString()?.trim('"'))
        assertEquals(3, SourceContract.parseSearchPage("""{"items":[],"nextPage":3}""", 2).totalPages)
        assertEquals(2, SourceContract.parseSearchPage("""{"items":[],"nextPage":null}""", 2).totalPages)
        assertFailsWith<SourceContractException> { SourceContract.parseSearchPage("""{"items":[],"nextPage":1}""", 2) }
    }

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
