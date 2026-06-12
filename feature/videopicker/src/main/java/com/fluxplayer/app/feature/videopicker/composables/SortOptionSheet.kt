package com.fluxplayer.app.feature.videopicker.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class SortOption(val key: String, val label: String) {
    NAME_ASC("name:asc", "名称 A-Z"),
    NAME_DESC("name:desc", "名称 Z-A"),
    TIME_DESC("time:desc", "时间 新→旧"),
    TIME_ASC("time:asc", "时间 旧→新"),
    SIZE_DESC("size:desc", "大小 大→小"),
    SIZE_ASC("size:asc", "大小 小→大"),
}

/**
 * 排序下拉菜单内容 —— 直接输出 DropdownMenuItem 列表，由外层 DropdownMenu 包裹
 */
@Composable
fun SortDropdownMenuContent(
    currentKey: String,
    onSelect: (SortOption) -> Unit,
    onDismiss: () -> Unit,
) {
    SortOption.entries.forEach { option ->
        val selected = option.key == currentKey
        DropdownMenuItem(
            text = {
                Text(
                    text = option.label,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            },
            onClick = {
                onSelect(option)
                onDismiss()
            },
        )
    }
}
