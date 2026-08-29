package com.fluxplayer.app.core.model

import kotlinx.serialization.Serializable

/**
 * 墨 · 极简风格下的强调色预设（界面唯一的彩色来源）。
 *
 * 日夜各给一个 ARGB 值：深色底上需要更亮的强调色才够对比。
 * 颜色以 Int ARGB 存储（不依赖 Compose），由 core:ui 转成 [androidx.compose.ui.graphics.Color]。
 */
@Serializable
enum class AccentPreset(
    val lightArgb: Int,
    val darkArgb: Int,
) {
    INK(0xFF1F1F1F.toInt(), 0xFFE8E8E8.toInt()),
    SEAL(0xFFB0442B.toInt(), 0xFFD9755A.toInt()),
    AZURE(0xFF1F5FA9.toInt(), 0xFF87B4E8.toInt()),
    VIOLET(0xFF6246A8.toInt(), 0xFFB9A5E8.toInt()),
    AMBER(0xFF9A5B10.toInt(), 0xFFE0AB63.toInt()),
    CYAN(0xFF126B70.toInt(), 0xFF79C8CC.toInt()),
    GRAPHITE(0xFF5A5F63.toInt(), 0xFFB0B6BA.toInt()),
    ;

    companion object {
        val Default = INK
    }
}
