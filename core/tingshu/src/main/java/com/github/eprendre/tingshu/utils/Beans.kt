package com.github.eprendre.tingshu.utils

data class Book(
    // 封面链接
    var coverUrl: String,
    // 书籍链接
    val bookUrl: String,
    // 标题
    var title: String,
    // 作者
    var author: String,
    // 演播
    var artist: String,
) {
    /** JDR 搜索结果透传字段，保留到章节请求。 */
    var jdrExtras: String = "{}"
    var id: Int? = null
    var intro: String = ""
    var currentEpisodeUrl: String? = null
    var currentEpisodeName: String? = null
    var currentEpisodePosition: Long = 0
    var skipBeginning: Long = 0
    var skipEnd: Long = 0
    var volumeBoostLevel: Int = 0
    var playOrderType: Int = 0
    var playSpeed: Float = 1f
    var isFree: Boolean = true
    var isEpisodesReversed: Boolean = false
    var episodeList: List<Episode>? = null

    // 只有 source 的 isMultipleEpisodePages 为 true 时，这个属性才起作用。
    var hasFullEpisodes: Boolean = false
    var isShowBriefChapterTitle: Boolean = false
    var sourceId: String? = null
    var status: String = ""
    var episodesUpdateTime: Long = 0

    // 如果章节列表是动态变化的，把这个参数设置为true。在每次进入播放页时会自动刷新。将在1.8.6后加入
    var isTransientEpisodes: Boolean = false

    // 是否已完结，如果是，app里面将不再触发章节列表的自动刷新。
    var isCompleted: Boolean = false

    override fun equals(other: Any?): Boolean {
        return other is Book && bookUrl == other.bookUrl && sourceId == other.sourceId
    }

    override fun hashCode(): Int {
        return 31 * bookUrl.hashCode() + (sourceId?.hashCode() ?: 0)
    }

    fun copyFrom(book: Book) {
        coverUrl = book.coverUrl
        title = book.title
        author = book.author
        artist = book.artist
        intro = book.intro
    }
}

data class Episode(val title: String, val url: String) {
    var isFree: Boolean = true

    @Transient var isCached: Boolean = false
    var progress: Int = 0

    // 章节封面
    var coverUrl: String = ""

    // 文稿，可以传网址，将打开网页。也可以传文本，则打开一个文本框，文本可以用html标签格式。
    var transcript: String = ""
}

interface IMenu {
    fun getType(): Int
}

data class CategoryTab(
    val title: String,
    val url: String,
) : IMenu {

    override fun getType(): Int {
        return 1
    }
}

data class BookDetail(
    val playList: List<Episode>,
    val intro: String? = "",
    val artist: String = "",
    val author: String = "",
    val episodesCount: Int = 0,
    val coverUrl: String = "",
) {
    val title: String = ""
}

/**
 * 大分类
 */
data class CategoryMenu(
    // 大分类标题
    val title: String,
    // 子分类
    val tabs: List<CategoryTab>,
) : IMenu {

    override fun getType(): Int {
        return 0
    }

    override fun equals(other: Any?): Boolean {
        return other is CategoryMenu && title == other.title && tabs == other.tabs
    }

    override fun hashCode(): Int {
        return 31 * title.hashCode() + tabs.hashCode()
    }
}

data class Category(
    val list: List<Book>,
    val currentPage: Int,
    val totalPage: Int,
    val currentUrl: String,
    val nextUrl: String,
)

/**
 * 2.5.9 加入，供自定义源配置文件使用, 可通过ExternalSourcePrefs.getString("${getSourceId()}.${configItem.key}")读取对应的值
 * Switch 读取值为 "true" 或者 "false"
 * MultiSelect 读取值为 "," 分隔的字符串，比如"1,2,3"
 */
sealed class ConfigItem(
    val key: String,
    val label: String,
) {
    class Button(label: String, val click: () -> Unit) : ConfigItem("", label) // 2.6.0加入
    class Text(key: String, label: String, var default: String = "") : ConfigItem(key, label)
    class Select(key: String, label: String, val options: List<String>, var default: String) : ConfigItem(key, label)
    class Switch(key: String, label: String, var default: Boolean) : ConfigItem(key, label)
    class MultiSelect(key: String, label: String, val options: List<String>, var default: List<String> = listOf()) : ConfigItem(key, label)
}
