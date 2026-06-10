package dev.anilbeesetti.nextplayer.feature.videopicker.screens.webdav

import dev.anilbeesetti.nextplayer.core.model.WebDavResource

data class ItemCounts(
    val folders: Int,
    val videos: Int,
    val others: Int,
) {
    companion object {
        fun from(items: List<WebDavResource>): ItemCounts {
            val folderCount = items.count { it.isDirectory }
            val videoCount = items.count { it.isVideo }
            val otherCount = items.count { !it.isDirectory && !it.isVideo }
            return ItemCounts(folderCount, videoCount, otherCount)
        }
    }

    fun toSummaryText(): String? {
        val parts = buildList {
            if (folders > 0) add("$folders 个文件夹")
            if (videos > 0) add("$videos 个视频")
            if (others > 0) add("$others 个文件")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
