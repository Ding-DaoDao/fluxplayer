package com.fluxplayer.app.feature.videopicker.composables

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.data.cloud.CloudSearchCategory
import com.fluxplayer.app.core.data.cloud.CloudSearchHistory
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SearchHistoryState(private val store: CloudSearchHistory, private val key: String, private val scope: CoroutineScope) {
    var entries by mutableStateOf(emptyList<String>())
        private set
    suspend fun load() {
        entries = withContext(Dispatchers.IO) { store.read(key) }
    }
    fun remember(term: String) = scope.launch {
        withContext(Dispatchers.IO) { store.remember(key, term) }
        load()
    }
    fun remove(term: String) = scope.launch {
        withContext(Dispatchers.IO) { store.remove(key, term) }
        load()
    }
    fun clear() = scope.launch {
        withContext(Dispatchers.IO) { store.clear(key) }
        load()
    }
}

@Composable
internal fun rememberSearchHistory(key: String): SearchHistoryState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember(key) { SearchHistoryState(CloudSearchHistory.from(context), key, scope) }
    LaunchedEffect(history) { history.load() }
    return history
}

/** 范围和类型只占一行，历史仅在准备输入时展开。 */
@Composable
internal fun CloudSearchControls(
    category: CloudSearchCategory,
    onCategory: (CloudSearchCategory) -> Unit,
    history: SearchHistoryState,
    showHistory: Boolean,
    onHistory: (String) -> Unit,
    entireDrive: Boolean? = null,
    onScope: (Boolean) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entireDrive != null) {
                FilterChip(selected = entireDrive, onClick = { onScope(true) }, label = { Text("全部文件") })
                FilterChip(selected = !entireDrive, onClick = { onScope(false) }, label = { Text("当前文件夹") })
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("类型：${category.label}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    CloudSearchCategory.entries.forEach { value ->
                        DropdownMenuItem(text = { Text(value.label) }, onClick = {
                            menu = false
                            onCategory(value)
                        })
                    }
                }
            }
        }
        if (showHistory && history.entries.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("最近搜索")
                TextButton(onClick = { history.clear() }) { Text("清空历史") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                history.entries.forEach { term ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onHistory(term) }, modifier = Modifier.widthIn(max = 240.dp).semantics { contentDescription = "搜索历史：$term" }) {
                                Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { history.remove(term) }, modifier = Modifier.size(40.dp)) { Icon(NextIcons.Close, "删除搜索历史：$term", Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }
    }
}
