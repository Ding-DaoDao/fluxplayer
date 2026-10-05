package com.fluxplayer.app.core.tingshu

import android.content.Context
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.Episode
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import voice.core.extension.engine.ExtensionSourceMeta
import voice.core.extension.engine.JdrArchive
import voice.core.extension.engine.JsSourceEngine
import voice.core.extension.engine.OkHttpSandboxHttp
import voice.core.extension.engine.SourceContract
import voice.core.extension.engine.SourceFeatures
import voice.core.extension.engine.SourceLogin
import voice.core.extension.engine.SourceSetting

/** Confined to the repository dispatcher; each script has its own QuickJS thread. */
data class JdrConfiguration(val fields: List<SourceSetting>, val values: Map<String, String>, val canLogin: Boolean, val canBrowse: Boolean, val initialDirectory: String)
data class JdrFolder(val id: String, val name: String)
data class JdrBrowserPage(val directoryId: String, val folders: List<JdrFolder>, val books: List<Book>, val nextPage: Int?)

internal class JdrSourceBackend(private val context: Context) {
    private data class Source(val archive: JdrArchive, val metadata: ExtensionSourceMeta)

    private val sources = linkedMapOf<String, Source>()
    private val engines = linkedMapOf<String, JsSourceEngine>()
    private val client = OkHttpClient()
    private val mutex = Mutex()

    fun handles(id: String): Boolean = id.startsWith("jdr:")

    suspend fun load(entry: String, bytes: ByteArray): List<ListeningSource> = mutex.withLock {
        val archive = JdrArchive.parse(bytes)
        require(entry == "jdr:${archive.manifest.id}") { "JDR 包标识不匹配，请重新导入" }
        val entries = archive.manifest.sources.map { metadata ->
            ListeningSource("jdr:${metadata.id}", metadata.name, archive.manifest.description, entry, metadata.capabilities.toSet())
        }
        require(entries.none { it.id in sources }) { "书源 ID 重复" }
        entries.zip(archive.manifest.sources).forEach { (entry, metadata) ->
            sources[entry.id] = Source(archive, metadata)
        }
        entries
    }

    suspend fun clear() = mutex.withLock {
        engines.values.forEach { it.close() }
        engines.clear()
        sources.clear()
    }

    suspend fun search(id: String, keyword: String, page: Int): Pair<List<Book>, Int> {
        // Timbre's contract has no total-pages field; use the same single search request.
        if (page != 1) return emptyList<Book>() to 1
        val result = invoke(id, "search", params(mapOf("keyword" to keyword, "page" to 1, "limit" to 30)), 15_000)
        return SourceContract.parseSearchResults(result).map { item ->
            Book(item.cover, item.id, item.title, item.author, "").apply {
                sourceId = id
                intro = item.intro
                jdrExtras = item.extra.toString()
            }
        } to 1
    }

    suspend fun detail(id: String, book: Book, key: String): ListeningBook {
        val result = invoke(
            id,
            "chapters",
            params(mapOf("bookId" to book.bookUrl, "page" to 1, "size" to 0), book.jdrExtras),
            20_000,
        )
        val chapters = SourceContract.parseChapters(result)
        return ListeningBook(
            key, id, book.bookUrl, book.title, book.coverUrl, book.intro,
            chapters.map { Episode(it.title, it.id) },
            book.jdrExtras,
            chapters.associate { it.id to it.extra.toString() },
        )
    }

    suspend fun resolve(book: ListeningBook, index: Int): ListeningResource {
        val chapter = book.episodes[index]
        val result = invoke(
            book.sourceId,
            "audio",
            params(mapOf("bookId" to book.url, "chapterId" to chapter.url), book.jdrChapterExtras[chapter.url] ?: "{}"),
            30_000,
        )
        val audio = SourceContract.parseAudio(result)
        return ListeningResource(audio.url, audio.headers)
    }

    private suspend fun invoke(id: String, stage: String, params: String, timeout: Long): String = mutex.withLock {
        val source = checkNotNull(sources[id]) { "书源已禁用、已删除或加载失败" }
        require(stage in source.metadata.capabilities) { "该书源不支持 $stage" }
        val engine = engines[id] ?: JsSourceEngine.create(
            source.metadata.id,
            source.archive.scriptFor(source.metadata),
            source.metadata.script,
            OkHttpSandboxHttp(httpClient(source.archive.manifest.allowInsecure)),
            host = preferences(source),
        ).also { engines[id] = it }
        try {
            engine.invoke(stage, params, timeout)
        } catch (error: Exception) {
            // A timed-out/cancelled sandbox must not be reused by the next request.
            if (error is CancellationException) {
                engines.remove(id)?.close()
            }
            throw error
        }
    }

    suspend fun configuration(id: String): JdrConfiguration = mutex.withLock {
        val source = requireSource(id)
        JdrConfiguration(source.metadata.settings, preferences(source).values(), "login" in source.metadata.capabilities, "browse" in source.metadata.capabilities, source.metadata.initialDirectory)
    }

    suspend fun saveConfiguration(id: String, values: Map<String, String>) = mutex.withLock {
        preferences(requireSource(id)).save(values)
        engines.remove(id)?.close()
    }

    suspend fun login(id: String, action: String, state: JsonObject = JsonObject(emptyMap()), cookies: String = ""): SourceLogin {
        require(action in setOf("status", "login", "poll", "webComplete", "logout")) { "未知登录操作" }
        val params = JsonObject(mapOf("action" to JsonPrimitive(action), "state" to state, "cookies" to JsonPrimitive(cookies))).toString()
        val result = SourceFeatures.parseLogin(invoke(id, "login", params, 30_000))
        if (action == "logout" && result.authenticated == false) {
            mutex.withLock {
                preferences(requireSource(id)).clear()
                engines.remove(id)?.close()
            }
        }
        return result
    }

    suspend fun browse(id: String, directory: String?, page: Int): JdrBrowserPage {
        val root = mutex.withLock {
            val source = requireSource(id)
            source.metadata.settings.firstOrNull { it.type == "directory" }?.let { preferences(source).values()[it.key] }
                ?.takeIf { it.isNotBlank() } ?: source.metadata.initialDirectory
        }
        val selected = directory ?: root
        val result = SourceFeatures.parseDirectory(invoke(id, "browse", params(mapOf("directoryId" to selected, "page" to page, "limit" to 100)), 30_000), page)
        val books = result.items.filterNot { it.directory }.map { item ->
            val extra = item.extra + mapOf("bookTitle" to JsonPrimitive(item.name))
            val parsed = SourceContract.parseSearchResults(JsonArray(listOf(JsonObject(extra))).toString()).single()
            Book(parsed.cover, parsed.id, parsed.title, parsed.author, "").apply {
                sourceId = id
                intro = parsed.intro
                jdrExtras = parsed.extra.toString()
            }
        }
        return JdrBrowserPage(selected, result.items.filter { it.directory }.map { JdrFolder(it.id, it.name) }, books, result.nextPage)
    }

    suspend fun clearMetadataCache(id: String) = mutex.withLock { preferences(requireSource(id)).clearCache() }

    private fun requireSource(id: String): Source = checkNotNull(sources[id]) { "书源已禁用、已删除或加载失败" }
    private fun preferences(source: Source) = JdrSourcePreferences(context, source.archive.manifest.id + ":" + source.metadata.id, source.metadata.settings)

    private fun params(base: Map<String, Any>, extras: String = "{}"): String {
        val merged = Json.parseToJsonElement(extras) as? JsonObject ?: error("无效的 JDR 附加数据")
        return JsonObject(
            merged + base.mapValues { (_, value) ->
                if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString())
            },
        ).toString()
    }

    /** Match Timbre's explicit per-package allowInsecure flag; default uses normal TLS. */
    private fun httpClient(allowInsecure: Boolean): OkHttpClient {
        if (!allowInsecure) return client
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        return client.newBuilder().sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
    }
}
