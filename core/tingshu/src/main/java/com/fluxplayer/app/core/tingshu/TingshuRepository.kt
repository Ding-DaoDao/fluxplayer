package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.github.eprendre.tingshu.sources.AudioUrlExtraHeaders
import com.github.eprendre.tingshu.sources.ConfigurableSource
import com.github.eprendre.tingshu.sources.CoverUrlExtraHeaders
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.BookDetail
import com.github.eprendre.tingshu.utils.Category
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.ConfigItem
import com.github.eprendre.tingshu.utils.Episode
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SourcePackage(
    val entry: String,
    val file: String,
    val enabled: Boolean = true,
    val error: String? = null,
)

data class ListeningSource(val id: String, val name: String, val description: String, val packageEntry: String)

data class ListeningBook(
    val key: String,
    val sourceId: String,
    val url: String,
    val title: String,
    val coverUrl: String,
    val intro: String,
    val episodes: List<Episode>,
)

data class ListeningProgress(val episodeUrl: String = "", val position: Long = 0, val duration: Long = 0)

data class ListeningResource(val url: String, val headers: Map<String, String>)

/** 所有书源调用在同一后台线程执行，隔离旧接口的共享单例和同步请求。 */
class TingshuRepository private constructor(private val context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val directory = File(context.filesDir, "tingshu/packages").apply { mkdirs() }
    private val bookDirectory = File(context.filesDir, "tingshu/books").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("tingshu_library", Context.MODE_PRIVATE)
    private val loaded = linkedMapOf<String, TingShu>()
    private val _packages = MutableStateFlow<List<SourcePackage>>(emptyList())
    val packages = _packages.asStateFlow()
    private val _sources = MutableStateFlow<List<ListeningSource>>(emptyList())
    val sources = _sources.asStateFlow()
    private val _progresses = MutableStateFlow<Map<String, ListeningProgress>>(emptyMap())
    val progresses = _progresses.asStateFlow()
    private val _recentBooks = MutableStateFlow<List<ListeningBook>>(emptyList())
    val recentBooks = _recentBooks.asStateFlow()

    init {
        SourceHost.initialize(context)
        scope.launch {
            reload(readPackages())
            readRecentKeys().forEach { key ->
                runCatching { book(key) }.getOrNull()?.let { _recentBooks.value += it }
            }
        }
    }

    suspend fun importJar(uri: Uri): Unit = withContext(dispatcher) {
        val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val entry = filename.removeSuffix(".jar")
        require(filename.endsWith(".jar") && entry.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "请选择保留原文件名的书源 JAR，例如 sources_by_pan123.jar"
        }
        val destination = File(directory, "$entry-${UUID.randomUUID()}.jar")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选文件" }
                FileOutputStream(destination).use { output ->
                    check(destination.setReadOnly()) { "无法设置书源文件的只读属性" }
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_JAR_SIZE) { "书源包不能超过 20 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            validateDex(destination)
            val imported = loadPackage(SourcePackage(entry, destination.name))
            val otherIds = _sources.value.filter { it.packageEntry != entry }.map { it.id }.toSet()
            require(imported.none { it.first.id in otherIds }) { "书源 ID 与已导入的其他包重复" }
            val previous = readPackages()
            val replacement = previous.filter { it.entry != entry } + SourcePackage(entry, destination.name)
            persistPackages(replacement)
            reload(replacement)
            previous.filter { it.entry == entry }.forEach { File(directory, it.file).delete() }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    suspend fun setEnabled(entry: String, enabled: Boolean) = withContext(dispatcher) {
        val updated = readPackages().map { if (it.entry == entry) it.copy(enabled = enabled) else it }
        persistPackages(updated)
        reload(updated)
    }

    suspend fun remove(entry: String) = withContext(dispatcher) {
        val previous = readPackages()
        val updated = previous.filter { it.entry != entry }
        persistPackages(updated)
        reload(updated)
        previous.filter { it.entry == entry }.forEach { File(directory, it.file).delete() }
    }

    suspend fun menus(sourceId: String): List<CategoryMenu> = withContext(dispatcher) {
        source(sourceId).getCategoryMenus()
    }

    suspend fun category(sourceId: String, url: String): Category = withContext(dispatcher) {
        source(sourceId).getCategoryList(url)
    }

    suspend fun search(sourceId: String, keywords: String, page: Int): Pair<List<Book>, Int> = withContext(dispatcher) {
        val selected = source(sourceId)
        require(selected.isSearchable()) { "该书源不支持搜索" }
        selected.search(keywords, page)
    }

    suspend fun detail(sourceId: String, book: Book): ListeningBook = withContext(dispatcher) {
        val selected = source(sourceId)
        SourceHost.currentBook.set(book)
        try {
            val detail: BookDetail = selected.getBookDetailInfo(book.bookUrl, true, true)
            val snapshot = ListeningBook(
                key = digest("$sourceId\u0000${book.bookUrl}"),
                sourceId = sourceId,
                url = book.bookUrl,
                title = detail.title.ifBlank { book.title },
                coverUrl = detail.coverUrl.ifBlank { book.coverUrl },
                intro = detail.intro.orEmpty().ifBlank { book.intro },
                episodes = detail.playList,
            )
            writeBook(snapshot)
            snapshot
        } finally {
            SourceHost.currentBook.remove()
        }
    }

    suspend fun book(key: String): ListeningBook = withContext(dispatcher) {
        require(key.matches(Regex("[a-f0-9]{64}"))) { "无效的书籍标识" }
        val data = JSONObject(File(bookDirectory, "$key.json").readText())
        ListeningBook(
            key,
            data.getString("sourceId"),
            data.getString("url"),
            data.getString("title"),
            data.getString("coverUrl"),
            data.getString("intro"),
            data.getJSONArray("episodes").let { list ->
                List(list.length()) { index ->
                    val item = list.getJSONObject(index)
                    Episode(item.getString("title"), item.getString("url"))
                }
            },
        )
    }

    suspend fun resolve(book: ListeningBook, index: Int): ListeningResource = withContext(dispatcher) {
        val selected = source(book.sourceId)
        val episode = book.episodes[index]
        SourceHost.currentBook.set(
            Book(book.coverUrl, book.url, book.title, "", "").apply {
                sourceId = book.sourceId
                currentEpisodeUrl = episode.url
                currentEpisodeName = episode.title
            },
        )
        SourceHost.extractedUrl.remove()
        try {
            selected.getAudioUrlExtractor().extract(episode.url, true, false, false)
            val url = checkNotNull(SourceHost.extractedUrl.get()) { "该书源的解析方式暂不支持" }
            require(Uri.parse(url).scheme in setOf("http", "https")) { "书源返回的地址不是 HTTP 音频地址" }
            ListeningResource(url, (selected as? AudioUrlExtraHeaders)?.headers(url).orEmpty())
        } finally {
            SourceHost.currentBook.remove()
            SourceHost.extractedUrl.remove()
        }
    }

    suspend fun coverHeaders(sourceId: String, url: String): Map<String, String> = withContext(dispatcher) {
        val result = mutableMapOf<String, String>()
        (source(sourceId) as? CoverUrlExtraHeaders)?.coverHeaders(url, result)
        result
    }

    suspend fun playbackHeaders(sourceId: String, url: String): Map<String, String> = withContext(dispatcher) {
        (source(sourceId) as? AudioUrlExtraHeaders)?.headers(url).orEmpty()
    }

    fun lastPlayedAt(key: String): Long = prefs.getLong("playedAt.$key", 0L)

    suspend fun markPlayed(book: ListeningBook) = withContext(dispatcher) {
        prefs.edit().putLong("playedAt.${book.key}", System.currentTimeMillis()).apply()
        val books = (listOf(book) + _recentBooks.value.filter { it.key != book.key }).take(20)
        prefs.edit().putString("recentBooks", JSONArray(books.map { it.key }).toString()).apply()
        _recentBooks.value = books
    }

    private fun readRecentKeys(): List<String> {
        val keys = JSONArray(prefs.getString("recentBooks", "[]"))
        return List(keys.length()) { keys.getString(it) }
    }

    suspend fun config(sourceId: String): List<ConfigItem> = withContext(dispatcher) {
        (source(sourceId) as? ConfigurableSource)?.getCustomConfigItems().orEmpty()
    }

    suspend fun configAction(action: () -> Unit) = withContext(dispatcher) { action() }

    suspend fun saveConfig(sourceId: String, values: Map<String, String>) = withContext(dispatcher) {
        source(sourceId).reset()
        val editor = context.getSharedPreferences("tingshu_source_config", Context.MODE_PRIVATE).edit()
        values.forEach { (key, value) -> editor.putString("$sourceId.$key", value) }
        check(editor.commit()) { "书源配置保存失败" }
    }

    fun progress(key: String): ListeningProgress {
        val raw = prefs.getString("progress.$key", null) ?: return ListeningProgress()
        val value = JSONObject(raw)
        return ListeningProgress(value.optString("url"), value.optLong("position"), value.optLong("duration"))
    }

    fun chapterProgress(book: ListeningBook): Map<Int, Pair<Long, Long>> {
        val values = JSONObject(prefs.getString("chapters.${book.key}", "{}") ?: "{}")
        val latest = progress(book.key)
        return book.episodes.mapIndexedNotNull { index, episode ->
            val value = values.optJSONObject(episode.url)
            when {
                value != null -> index to (value.optLong("position") to value.optLong("duration"))
                episode.url == latest.episodeUrl -> index to (latest.position to latest.duration)
                else -> null
            }
        }.toMap()
    }

    fun saveProgress(key: String, progress: ListeningProgress) {
        val chapters = JSONObject(prefs.getString("chapters.$key", "{}") ?: "{}")
        chapters.put(progress.episodeUrl, JSONObject().put("position", progress.position.coerceAtLeast(0)).put("duration", progress.duration.coerceAtLeast(0)))
        prefs.edit().putString("chapters.$key", chapters.toString()).apply()
        prefs.edit().putString(
            "progress.$key",
            JSONObject().put("url", progress.episodeUrl).put("position", progress.position.coerceAtLeast(0))
                .put("duration", progress.duration.coerceAtLeast(0)).toString(),
        ).apply()
        _progresses.value = _progresses.value + (key to progress)
    }

    private fun source(id: String): TingShu = checkNotNull(loaded[id]) { "书源已禁用、已删除或加载失败" }

    private fun loadPackage(pkg: SourcePackage): List<Pair<ListeningSource, TingShu>> {
        val file = File(directory, pkg.file)
        require(file.canonicalFile.parentFile == directory.canonicalFile && file.exists()) { "书源文件不存在" }
        require(!file.canWrite()) { "书源文件必须是只读文件，请重新导入" }
        val loader = DexClassLoader(file.absolutePath, context.codeCacheDir.absolutePath, null, context.classLoader)
        val entry = loader.loadClass("com.github.eprendre.${pkg.entry}.SourceEntry")
        val result = entry.getMethod("getSources").invoke(null) as? List<*>
            ?: error("书源入口没有返回源列表")
        require(result.isNotEmpty()) { "书源包没有包含可用书源" }
        val sources = result.map { item ->
            val source = item as? TingShu ?: error("该 JAR 不兼容我的听书接口")
            val id = source.getSourceId()
            require(id.isNotBlank()) { "书源 ID 不能为空" }
            ListeningSource(id, source.getName(), source.getDesc(), pkg.entry) to source
        }
        require(sources.map { it.first.id }.distinct().size == sources.size) { "包内存在重复书源 ID" }
        return sources
    }

    private fun reload(packages: List<SourcePackage>) {
        loaded.clear()
        val sources = mutableListOf<ListeningSource>()
        _packages.value = packages.map { pkg ->
            if (!pkg.enabled) return@map pkg
            try {
                val entries = loadPackage(pkg)
                require(entries.none { it.first.id in loaded }) { "书源 ID 重复" }
                entries.forEach { (metadata, source) ->
                    loaded[metadata.id] = source
                    sources.add(metadata)
                }
                pkg.copy(error = null)
            } catch (error: LinkageError) {
                pkg.copy(error = "缺少兼容接口：${error.javaClass.simpleName}")
            } catch (error: Exception) {
                pkg.copy(error = error.cause?.message ?: error.message ?: "加载失败")
            }
        }
        _sources.value = sources
    }

    private fun readPackages(): List<SourcePackage> {
        val data = JSONArray(prefs.getString("packages", "[]"))
        return List(data.length()) { index ->
            val item = data.getJSONObject(index)
            SourcePackage(item.getString("entry"), item.getString("file"), item.optBoolean("enabled", true))
        }
    }

    private fun persistPackages(packages: List<SourcePackage>) {
        val data = JSONArray()
        packages.forEach {
            data.put(JSONObject().put("entry", it.entry).put("file", it.file).put("enabled", it.enabled))
        }
        check(prefs.edit().putString("packages", data.toString()).commit()) { "书源列表保存失败" }
    }

    private fun writeBook(book: ListeningBook) {
        val episodes = JSONArray()
        book.episodes.forEach { episodes.put(JSONObject().put("title", it.title).put("url", it.url)) }
        val data = JSONObject().put("sourceId", book.sourceId).put("url", book.url).put("title", book.title)
            .put("coverUrl", book.coverUrl).put("intro", book.intro).put("episodes", episodes)
        val file = File(bookDirectory, "${book.key}.json")
        val temporary = File(bookDirectory, "${book.key}.tmp")
        temporary.writeText(data.toString())
        check(temporary.renameTo(file)) { "书籍保存失败" }
    }

    companion object {
        private const val MAX_JAR_SIZE = 20L * 1024 * 1024

        @Volatile
        private var instance: TingshuRepository? = null

        fun get(context: Context): TingshuRepository = instance ?: synchronized(this) {
            instance ?: TingshuRepository(context.applicationContext).also { instance = it }
        }

        internal fun validateDex(file: File) {
            ZipFile(file).use { archive ->
                val dex = requireNotNull(archive.getEntry("classes.dex")) { "JAR 不包含 classes.dex，请先使用 D8 转换" }
                require(dex.size in 8..MAX_JAR_SIZE) { "无效或过大的 DEX 文件" }
                archive.getInputStream(dex).use { input ->
                    val magic = ByteArray(8)
                    require(input.read(magic) == 8 && magic.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) {
                        "JAR 中的 DEX 格式无效"
                    }
                }
            }
        }
    }
}
