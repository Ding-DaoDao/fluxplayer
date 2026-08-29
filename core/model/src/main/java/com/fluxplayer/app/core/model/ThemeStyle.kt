package com.fluxplayer.app.core.model

/**
 * 主题风格：与「明暗模式」「动态取色」「组件引擎」正交的独立维度。
 *
 * - [TONAL]：现有 Material3 tonal 体系（蓝紫 seed / 壁纸动态色 / 自定义 seed），
 *   primary / secondary / tertiary 均带色相。
 * - [INK]：墨 · 极简。中性灰阶打底，强调色是界面上唯一的彩色来源
 *   （借鉴 MoRead 的「墨」主题哲学），默认墨色 = 纯黑白。
 */
enum class ThemeStyle {
    TONAL,
    INK,
}
