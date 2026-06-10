package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 弹幕源配置 —— 描述一个第三方弹幕 API 的来源。
 *
 * @property id 唯一标识（UUID 或自建源的哈希）
 * @property name 用户可见的名称，如"弹弹play"
 * @property baseUrl API 基础 URL，如 "https://api.dandanplay.net"
 * @property type 源类型（内置/自定义）
 * @property appId 弹弹play 开放平台 AppId（空则不发送认证头）
 * @property token 访问密钥（弹弹play 为 AppSecret；自建源可自定义）
 * @property enabled 是否在弹幕搜索时启用
 */
@Serializable
data class DanmakuSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val type: DanmakuSourceType = DanmakuSourceType.BUILT_IN,
    val appId: String = "",
    val token: String = "",
    val enabled: Boolean = true,
) {
    companion object {
        /** 弹弹 play 官方源 */
        val DANDANPLAY = DanmakuSource(
            id = "dandanplay",
            name = "弹弹play",
            baseUrl = "https://api.dandanplay.net",
            type = DanmakuSourceType.BUILT_IN,
        )

        /** B站弹幕平台源 */
        val BILIBILI = DanmakuSource(
            id = "platform:bilibili",
            name = "B站弹幕",
            baseUrl = "",
            type = DanmakuSourceType.PLATFORM,
            enabled = false,
        )

        /** 腾讯弹幕平台源 */
        val TENCENT = DanmakuSource(
            id = "platform:tencent",
            name = "腾讯弹幕",
            baseUrl = "",
            type = DanmakuSourceType.PLATFORM,
            enabled = false,
        )

        /** 芒果弹幕平台源 */
        val MGTV = DanmakuSource(
            id = "platform:mgtv",
            name = "芒果弹幕",
            baseUrl = "",
            type = DanmakuSourceType.PLATFORM,
            enabled = false,
        )

        /** 优酷弹幕平台源 */
        val YOUKU = DanmakuSource(
            id = "platform:youku",
            name = "优酷弹幕",
            baseUrl = "",
            type = DanmakuSourceType.PLATFORM,
            enabled = false,
        )

        /** 爱奇艺弹幕平台源 */
        val QIYI = DanmakuSource(
            id = "platform:qiyi",
            name = "爱奇艺弹幕",
            baseUrl = "",
            type = DanmakuSourceType.PLATFORM,
            enabled = false,
        )

        /** 人人视频弹幕平台源 */
        val RRSP = DanmakuSource(
            id = "platform:rrsp",
            name = "人人弹幕",
            baseUrl = "",
            type = DanmakuSourceType.PLATFORM,
            enabled = false,
        )

        /** 所有平台源列表 */
        val PLATFORM_SOURCES = listOf(BILIBILI, TENCENT, MGTV, YOUKU, QIYI, RRSP)

        /** 判断是否为平台源 */
        fun isPlatformSource(source: DanmakuSource): Boolean {
            return source.type == DanmakuSourceType.PLATFORM
        }
    }
}

@Serializable
enum class DanmakuSourceType {
    /** 内置预设源（弹弹 play 官方） */
    BUILT_IN,
    /** 用户自定义源（自建兼容 API） */
    CUSTOM,
    /** 平台源（B站、芒果TV等） */
    PLATFORM,
}
