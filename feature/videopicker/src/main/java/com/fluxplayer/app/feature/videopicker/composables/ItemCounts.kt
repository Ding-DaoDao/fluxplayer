package com.fluxplayer.app.feature.videopicker.composables

import com.fluxplayer.app.core.model.WebDavResource

data class ItemCounts(
    val folders: Int,
    val files: Int,
) {
    companion object {
        fun from(items: List<WebDavResource>): ItemCounts {
            val folderCount = items.count { it.isDirectory }
            val fileCount = items.count { !it.isDirectory }
            return ItemCounts(folderCount, fileCount)
        }
    }

    fun toSummaryText(): String? {
        if (folders == 0 && files == 0) return null
        val parts = buildList {
            if (folders > 0) add("${folders}个文件夹")
            if (files > 0) add("${files}个文件")
        }
        return parts.joinToString(" ")
    }
}
