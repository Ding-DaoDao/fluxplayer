package com.fluxplayer.app.feature.tingshu

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.ListeningSource
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.ConfigItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SourceBrowseState(
    val source: ListeningSource? = null,
    val menus: List<CategoryMenu> = emptyList(),
    val books: List<Book> = emptyList(),
    val detail: ListeningBook? = null,
    val title: String = "",
    val query: String = "",
    val page: Int = 0,
    val totalPages: Int = 0,
    val nextUrl: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val configItems: List<ConfigItem>? = null,
    val configRevision: Int = 0,
    val canGoBack: Boolean = false,
)

internal val ListeningSource.isCloudLibrary: Boolean
    get() = packageEntry in setOf("sources_by_pan123", "sources_by_quark", "sources_by_cloud189", "sources_by_yun139")

class TingshuViewModel(application: Application) : AndroidViewModel(application) {
    val repository = TingshuRepository.get(application)
    private val mutableState = MutableStateFlow(SourceBrowseState())
    val state = mutableState.asStateFlow()
    private val history = mutableListOf<SourceBrowseState>()
    private var loadJob: Job? = null

    fun importJar(uri: Uri) = operation { repository.importJar(uri) }

    fun enable(entry: String, enabled: Boolean) = operation { repository.setEnabled(entry, enabled) }

    fun remove(entry: String) = operation { repository.remove(entry) }

    fun open(source: ListeningSource) {
        history.clear()
        mutableState.value = SourceBrowseState(source = source, title = source.name)
        operation {
            val menus = repository.menus(source.id)
            val tabs = menus.flatMap { it.tabs }
            // 云盘源的单入口代表配置的根目录，直接加载，不把隐藏入口显示为书籍。
            if (source.isCloudLibrary && tabs.size == 1) {
                val category = repository.category(source.id, tabs.single().url)
                mutableState.update {
                    it.copy(
                        books = category.list,
                        page = category.currentPage,
                        totalPages = category.totalPage,
                        nextUrl = category.nextUrl,
                    )
                }
            } else {
                mutableState.update { it.copy(menus = menus.filter { menu -> menu.tabs.isNotEmpty() }) }
            }
        }
    }

    fun category(url: String, title: String, append: Boolean = false) {
        val source = state.value.source ?: return
        val previous = state.value
        operation {
            val category = repository.category(source.id, url)
            if (!append) history.add(previous.copy(loading = false, error = null))
            mutableState.update {
                it.copy(
                    books = if (append) it.books + category.list else category.list,
                    menus = emptyList(),
                    title = title,
                    query = "",
                    page = category.currentPage,
                    totalPages = category.totalPage,
                    nextUrl = category.nextUrl,
                    canGoBack = history.isNotEmpty(),
                )
            }
        }
    }

    fun search(query: String, append: Boolean = false) {
        val source = state.value.source ?: return
        if (query.isBlank()) return
        val previous = state.value
        val page = if (append) state.value.page + 1 else 1
        operation {
            val (books, total) = repository.search(source.id, query.trim(), page)
            if (!append) history.add(previous.copy(loading = false, error = null))
            mutableState.update {
                it.copy(
                    books = if (append) it.books + books else books,
                    menus = emptyList(),
                    title = "搜索：${query.trim()}",
                    query = query.trim(),
                    page = page,
                    totalPages = total,
                    nextUrl = "",
                    canGoBack = history.isNotEmpty(),
                )
            }
        }
    }

    fun nextPage() {
        val state = state.value
        if (state.query.isNotBlank()) {
            search(state.query, true)
        } else if (state.nextUrl.isNotBlank()) category(state.nextUrl, state.title, true)
    }

    fun detail(book: Book) {
        val source = state.value.source ?: return
        operation {
            val detail = repository.detail(source.id, book)
            mutableState.update { it.copy(detail = detail) }
        }
    }

    fun openSavedBook(key: String) = operation {
        history.clear()
        mutableState.value = SourceBrowseState(loading = true)
        val book = repository.book(key)
        require(repository.sources.value.any { it.id == book.sourceId }) { "请先启用或重新导入这本书的书源" }
        mutableState.update { it.copy(detail = book) }
    }

    fun configure(source: ListeningSource) {
        mutableState.value = SourceBrowseState(source = source)
        configure()
    }

    fun configure() {
        val source = state.value.source ?: return
        operation {
            val items = repository.config(source.id)
            mutableState.update { it.copy(configItems = items) }
        }
    }

    fun saveConfig(values: Map<String, String>) {
        val source = state.value.source ?: return
        operation {
            repository.saveConfig(source.id, values)
            mutableState.update { it.copy(configItems = null) }
        }
    }

    fun configAction(action: () -> Unit) = operation {
        repository.configAction(action)
        val source = state.value.source ?: return@operation
        val items = repository.config(source.id)
        mutableState.update { it.copy(configItems = items, configRevision = it.configRevision + 1) }
    }

    fun dismissConfig() = mutableState.update { it.copy(configItems = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    fun back() {
        loadJob?.cancel()
        when {
            state.value.detail != null -> mutableState.update { it.copy(detail = null, loading = false, error = null) }
            history.isNotEmpty() -> mutableState.value = history.removeAt(history.lastIndex)
            else -> mutableState.value = SourceBrowseState()
        }
    }

    private fun operation(action: suspend () -> Unit) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                action()
                mutableState.update { it.copy(loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(loading = false, error = error.cause?.message ?: error.message ?: "书源操作失败")
                }
            }
        }
    }
}
