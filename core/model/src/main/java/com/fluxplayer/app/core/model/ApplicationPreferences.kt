package com.fluxplayer.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ApplicationPreferences(
    val sortBy: Sort.By = Sort.By.TITLE,
    val sortOrder: Sort.Order = Sort.Order.ASCENDING,
    val themeConfig: ThemeConfig = ThemeConfig.SYSTEM,
    val useHighContrastDarkTheme: Boolean = false,
    val useDynamicColors: Boolean = true,
    val markLastPlayedMedia: Boolean = true,
    val excludeFolders: List<String> = emptyList(),
    val mediaViewMode: MediaViewMode = MediaViewMode.FOLDERS,
    val mediaLayoutMode: MediaLayoutMode = MediaLayoutMode.LIST,

    // Fields
    val showDurationField: Boolean = true,
    val showExtensionField: Boolean = false,
    val showPathField: Boolean = true,
    val showResolutionField: Boolean = false,
    val showSizeField: Boolean = false,
    val showThumbnailField: Boolean = true,
    val showPlayedProgress: Boolean = true,

    // Thumbnail generation
    val thumbnailGenerationStrategy: ThumbnailGenerationStrategy = ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE,
    val thumbnailFramePosition: Float = DEFAULT_THUMBNAIL_FRAME_POSITION,

    // 每目录最新播放足迹 (key=目录路径, value=最新项目路径/URI)
    val latestFootprintPerDir: Map<String, String> = emptyMap(),

    // 启动页与标签页可见性
    val startupPage: StartupPage = StartupPage.VIDEOS,
    val showVideosTab: Boolean = true,
    val showBrowseTab: Boolean = true,
    val showHistoryTab: Boolean = true,

    // 浏览页 provider 排序（空列表表示使用默认顺序）
    val providerOrder: List<String> = emptyList(),

    // 悬浮底栏
    val useFloatingBottomBar: Boolean = false,

    // 液态玻璃效果（仅在悬浮底栏开启时生效）
    val useLiquidGlass: Boolean = false,

    // 模糊效果
    val enableBlur: Boolean = true,
    val enableProgressiveBlur: Boolean = false,
    val topBarBlurRadius: Int = 24,
    val topBarBlurAlpha: Int = 73,
    val bottomBarBlurRadius: Int = 25,
    val bottomBarBlurAlpha: Int = 73,

    // 主题引擎
    val composeEngine: ComposeEngine = ComposeEngine.MATERIAL,

    // 下载存储路径
    val downloadPath: String = "/storage/emulated/0/Download/",
) {

    companion object {
        const val DEFAULT_THUMBNAIL_FRAME_POSITION = 0.33f
    }
}
