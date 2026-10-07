package com.fluxplayer.app.feature.tingshu

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningHomeGridTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun repeatedSwitchesKeepScrolledBooksVisibleWithoutReusingOldPlacement() {
        var grid by mutableStateOf(true)
        var books by mutableStateOf((0 until 60).toList())
        compose.setContent {
            Column {
                TextButton(onClick = { grid = !grid }, modifier = Modifier.testTag("switch")) {
                    Text("切换网格/列表")
                }
                ListeningHomeGrid(grid, Modifier.weight(1f).testTag("books")) { layoutIsGrid ->
                    item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                        Text("最近听过")
                    }
                    items(
                        books,
                        key = { "book-$it" },
                        contentType = { if (layoutIsGrid) "grid" else "row" },
                        span = { GridItemSpan(if (layoutIsGrid) 1 else maxLineSpan) },
                    ) { book ->
                        Column(
                            Modifier.fillMaxWidth().height(if (layoutIsGrid) 140.dp else 72.dp)
                                .testTag("book-$book"),
                        ) {
                            Text(
                                "第 $book 本书的长标题，用于触发跑马灯与条目尺寸变化",
                                modifier = Modifier.fillMaxWidth().basicMarquee(),
                                maxLines = 1,
                            )
                            if (layoutIsGrid) Text("网格封面区域")
                        }
                    }
                }
            }
        }
        repeat(10) { compose.onNodeWithTag("switch").performClick() }
        compose.onNodeWithTag("books").performScrollToIndex(21)
        compose.onNodeWithTag("book-20").assertIsDisplayed()
        repeat(10) {
            compose.onNodeWithTag("switch").performClick()
            compose.onNodeWithTag("book-20").assertIsDisplayed()
        }
        // A playback/history update can arrive immediately after switching layout.
        compose.runOnIdle { books = books + 60 }
        compose.onNodeWithTag("switch").performClick()
        compose.onNodeWithTag("book-20").assertIsDisplayed()
    }
}
