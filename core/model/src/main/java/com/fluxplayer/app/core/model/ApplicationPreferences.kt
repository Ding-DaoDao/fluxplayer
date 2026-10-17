package com.fluxplayer.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ApplicationPreferences(
    val sortBy: Sort.By = Sort.By.TITLE,
    val sortOrder: Sort.Order = Sort.Order.ASCENDING,
    val themeConfig: ThemeConfig = ThemeConfig.SYSTEM,
    val useHighContrastDarkTheme: Boolean = false,
    val accentPreset: AccentPreset = AccentPreset.Default,
    val themeColorSource: ThemeColorSource = ThemeColorSource.SYSTEM,
    val themeSeedArgb: Int = 0xFFD02020.toInt(),
    val themePaletteStyle: ThemePaletteStyle = ThemePaletteStyle.TONAL_SPOT,
    // 液态玻璃参数与导航、顶栏共同使用。
    val glassControlsEnabled: Boolean = true,
    val glassBlurDp: Float = 20f,
    val glassDistortionDp: Float = 30f,
    val glassTranslucency: Float = 0.5f,
    val glassDispersion: Boolean = true,
    val glassSettingsVersion: Int = 0,
    val navAnimationsEnabled: Boolean = true,
    val navGesturesEnabled: Boolean = true,
    val hideStatusBar: Boolean = false,
    val historyMergeEnabled: Boolean = false,
    val videoIncognito: Boolean = false,
    val historyLimit: Int = 200,
    // 零表示自动适配宽度，显式列数同时用于视频与书架网格。
    val mediaGridColumns: Int = 0,
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

    // 启动页与标签页可见性
    val startupPage: StartupPage = StartupPage.VIDEOS,
    val showVideosTab: Boolean = true,
    val showBrowseTab: Boolean = true,
    val showHistoryTab: Boolean = true,
    val showAudiobookTab: Boolean = true,

    // 听书根目录 URI（SAF 目录选择结果）
    val audiobookRootUri: String = "",
    // 只有用户重新选择目录时递增，用于持久标识需要执行的一次扫描。
    val audiobookLibraryRevision: Long = 0L,

    // 听书片头片尾跳过设置 (key=书籍文件夹路径, value="introSeconds,outroSeconds")
    val audiobookSkipSettings: Map<String, String> = emptyMap(),

    // 旧版迁移与备份兼容字段，实时续播进度存入数据库。
    val audiobookResumeState: Map<String, String> = emptyMap(),

    // 旧版章节进度备份格式：key="bookPath|chapterIndex"，value="positionMs|durationMs"。
    val audiobookChapterProgress: Map<String, String> = emptyMap(),

    // 听书倍速（独立于视频播放的全局倍速设置）
    val audiobookPlaybackSpeed: Float = 1.0f,

    // 旧版最近播放时间备份格式：key=书籍文件夹路径，value=epochMillis。
    val audiobookLastPlayedAt: Map<String, Long> = emptyMap(),

    // 浏览页 provider 排序（空列表表示使用默认顺序）
    val providerOrder: List<String> = emptyList(),

    // 悬浮底栏
    val useFloatingBottomBar: Boolean = true,

    // 底栏样式（悬浮舱 / 通栏）。useFloatingBottomBar=false 时此值不生效
    val navStyle: NavStyle = NavStyle.Default,

    // 界面质感（玻璃 / 扁平）
    val surfaceStyle: SurfaceStyle = SurfaceStyle.Default,

    // 液态玻璃效果（仅在悬浮底栏开启时生效）
    val useLiquidGlass: Boolean = true,

    // 下载存储路径
    val downloadPath: String = "/storage/emulated/0/Download/",
) {

    companion object {
        const val DEFAULT_THUMBNAIL_FRAME_POSITION = 0.33f
    }
}
