package com.fluxplayer.app.feature.tingshu

import androidx.annotation.DrawableRes
import com.fluxplayer.app.core.tingshu.ListeningSource
import com.fluxplayer.app.core.ui.R as UiR

/** 优先按书源标识匹配平台，兼容旧版 JAR 书源使用的平台名称。 */
@DrawableRes
internal fun listeningSourceIcon(source: ListeningSource): Int {
    val identifiers = source.id.lowercase().split(Regex("[^a-z0-9]+"))
    val name = source.name.lowercase()
    fun matches(vararg aliases: String): Boolean = aliases.any { it in identifiers || name.contains(it) }
    return when {
        matches("fanqie", "番茄") -> R.drawable.ic_source_fanqie
        matches("bilibili", "b站", "哔哩哔哩") -> R.drawable.ic_source_bilibili
        matches("alipan", "aliyun", "阿里云盘", "阿里网盘") -> UiR.drawable.ic_provider_alipan
        matches("pan123", "123pan", "123云盘", "123网盘") -> UiR.drawable.ic_provider_pan123
        matches("quark", "夸克") -> UiR.drawable.ic_provider_quark
        matches("yun139", "139", "移动云盘", "移动网盘", "和彩云") -> UiR.drawable.ic_provider_yun139
        matches("cloud189", "189", "天翼") -> UiR.drawable.ic_provider_cloud189
        matches("baidu", "百度") -> UiR.drawable.ic_provider_baidu
        matches("openlist", "alist") -> UiR.drawable.ic_provider_openlist
        matches("webdav") -> UiR.drawable.ic_provider_webdav
        "uc" in identifiers || name.contains("uc网盘") || name.contains("uc云盘") -> UiR.drawable.ic_provider_uc
        else -> UiR.drawable.ic_provider_default
    }
}
