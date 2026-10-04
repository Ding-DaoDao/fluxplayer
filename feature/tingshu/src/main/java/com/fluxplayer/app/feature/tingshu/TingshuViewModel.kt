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
    /** 播放确认弹窗预解析出的详情，就绪后可一键起播 */
    val pendingDetail: ListeningBook? = null,
    val title: String = "",
    val query: String = "",
    val page: Int = 0,
    val totalPages: Int = 0,
    val nextUrl: String = "",
    /** 当前分类的请求地址，下拉刷新时用它重新拉取 */
    val currentCategoryUrl: String = "",
    val loading: Boolean = false,
    /** 下拉刷新中（列表保持显示，不闪空） */
    val refreshing: Boolean = false,
    /** 触底自动加载下一页中 */
    val loadingMore: Boolean = false,
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

    fun importSource(uri: Uri) = operation { repository.importSource(uri) }

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
                        currentCategoryUrl = tabs.single().url,
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
                    currentCategoryUrl = url,
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
        if (state.loading || state.loadingMore) return
        if (!hasMore(state)) return
        if (state.query.isNotBlank()) {
            search(state.query, true)
        } else if (state.nextUrl.isNotBlank()) {
            category(state.nextUrl, state.title, true)
        }
    }

    /** 是否还有下一页：搜索看 totalPages，分类看 nextUrl。 */
    private fun hasMore(state: SourceBrowseState): Boolean =
        if (state.query.isNotBlank()) state.page < state.totalPages else state.nextUrl.isNotBlank()

    /** 供列表触底回调：返回是否真的发起了加载（已到末页或正在加载时为 false）。 */
    fun loadMoreIfNeeded(): Boolean {
        val state = state.value
        if (state.loading || state.loadingMore || !hasMore(state)) return false
        mutableState.update { it.copy(loadingMore = true) }
        nextPage()
        return true
    }

    /**
     * 下拉刷新：重载当前视图。
     * 书库首页 → 重新加载书源入口；分类/搜索子页 → 重新拉当前页（列表保持显示，不闪空）。
     */
    fun refresh() {
        val state = state.value
        val source = state.source ?: return
        if (state.refreshing) return
        mutableState.update { it.copy(refreshing = true, error = null) }
        operation {
            val refreshed = when {
                state.canGoBack && state.query.isNotBlank() ->
                    repository.search(source.id, state.query, 1)
                state.canGoBack -> repository.category(source.id, state.currentCategoryUrl).let { it.list to it.totalPage }
                else -> {
                    val menus = repository.menus(source.id)
                    val tabs = menus.flatMap { it.tabs }
                    if (source.isCloudLibrary && tabs.size == 1) {
                        val category = repository.category(source.id, tabs.single().url)
                        category.list to category.totalPage
                    } else {
                        mutableState.update { it.copy(menus = menus.filter { menu -> menu.tabs.isNotEmpty() }) }
                        return@operation
                    }
                }
            }
            mutableState.update {
                it.copy(
                    books = refreshed.first,
                    page = 1,
                    totalPages = refreshed.second,
                    nextUrl = if (state.canGoBack) it.nextUrl else "",
                    menus = if (state.canGoBack) it.menus else emptyList(),
                    canGoBack = state.canGoBack,
                )
            }
        }
    }

    /** 进入书籍详情页（保留给测试与未来可能的"查看详情"入口；列表点击已改走 [resolveForPlayback]）。 */
    fun detail(book: Book) {
        val source = state.value.source ?: return
        operation {
            val detail = repository.detail(source.id, book)
            mutableState.update { it.copy(detail = detail) }
        }
    }

    /**
     * 解析书籍详情并直接交给播放页，跳过详情页。
     * 不写入 state.detail —— 否则列表页会短暂切到详情页再被播放页盖住。
     * 解析失败时错误落在 state.error，由列表页的错误条展示。
     * [onReady] 在 viewModelScope（主线程）回调，可安全启动 Activity。
     */
    fun resolveForPlayback(book: Book, onReady: (ListeningBook) -> Unit) {
        val source = state.value.source ?: return
        operation {
            val detail = repository.detail(source.id, book)
            onReady(detail)
        }
    }

    /**
     * 解析书籍详情用于填充播放确认弹窗（章节数等），不启动播放。
     * 结果写入 [state.pendingDetail]，UI 侧监听它关闭弹窗并起播。
     * 弹窗打开时立刻调用：绝大多数情况几百毫秒内就返回，用户点击时已就绪。
     */
    fun resolvePending(book: Book) {
        val source = state.value.source ?: return
        operation { mutableState.update { it.copy(pendingDetail = repository.detail(source.id, book)) } }
    }

    fun consumePendingDetail() = mutableState.update { it.copy(pendingDetail = null) }

    fun openSavedBook(key: String) = operation {
        history.clear()
        mutableState.value = SourceBrowseState(loading = true)
        val book = repository.book(key)
        require(repository.sources.value.any { it.id == book.sourceId }) { "请先启用或重新导入这本书的书源" }
        mutableState.update { it.copy(detail = book) }
    }

    /**
     * 最近收听书籍直达播放页：解析出详情后立即回调，跳过详情页。
     * 只置 loading 标记，不重建 state —— 避免把 source 清空导致后续操作失效。
     */
    fun playSavedBook(key: String, onReady: (ListeningBook) -> Unit) = operation {
        history.clear()
        mutableState.update { it.copy(loading = true, error = null) }
        val book = repository.book(key)
        require(repository.sources.value.any { it.id == book.sourceId }) { "请先启用或重新导入这本书的书源" }
        onReady(book)
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
                mutableState.update { it.copy(loading = false, loadingMore = false, refreshing = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        refreshing = false,
                        error = error.cause?.message ?: error.message ?: "书源操作失败",
                    )
                }
            }
        }
    }
}
