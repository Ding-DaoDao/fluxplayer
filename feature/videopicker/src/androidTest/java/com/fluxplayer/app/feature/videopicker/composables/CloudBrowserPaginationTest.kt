package com.fluxplayer.app.feature.videopicker.composables

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.feature.videopicker.DirectoryStackEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CloudBrowserPaginationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun flatListDoesNotRetryOnLoadingOrErrorUpdates() = checkPagination(false, false)

    @Test
    fun directoryStackDoesNotRetryOnLoadingOrErrorUpdates() = checkPagination(true, false)

    @Test
    fun flatListContinuesWhenNewItemsRemainNearTheBottom() = checkPagination(false, true)

    @Test
    fun directoryStackContinuesWhenNewItemsRemainNearTheBottom() = checkPagination(true, true)

    private fun checkPagination(useStack: Boolean, appendItems: Boolean) {
        val items = mutableStateOf(listOf(resource(1)))
        val loadingMore = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var attempts = 0

        compose.setContent {
            val currentItems = items.value
            val currentLoadingMore = loadingMore.value
            val currentError = error.value
            MaterialTheme {
                CloudBrowserPanel(
                    items = currentItems,
                    breadcrumbs = listOf("root"),
                    isLoading = false,
                    isConfigured = true,
                    error = currentError,
                    isLoadingMore = currentLoadingMore,
                    onItemClick = {},
                    onBreadcrumbClick = {},
                    onRefresh = {},
                    // Capture changing state to reproduce callback replacement on recomposition.
                    onLoadMore = {
                        if (!currentLoadingMore && currentError == null) attempts++
                    },
                    breadcrumbLabel = { it },
                    loginContent = {},
                    navigationStack = if (useStack) {
                        listOf(
                            DirectoryStackEntry(
                                fileId = "root",
                                label = "root",
                                items = currentItems,
                                isLoading = false,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                )
            }
        }

        compose.runOnIdle {
            assertEquals(1, attempts)
            loadingMore.value = true
        }
        compose.runOnIdle {
            assertEquals(1, attempts)
            loadingMore.value = false
            if (appendItems) {
                items.value = items.value + resource(2)
            } else {
                error.value = "加载更多失败"
            }
        }
        compose.runOnIdle {
            assertEquals(if (appendItems) 2 else 1, attempts)
            error.value = null
        }
        compose.runOnIdle {
            assertEquals(if (appendItems) 2 else 1, attempts)
        }
    }

    private fun resource(index: Int) = WebDavResource(
        name = "Folder $index",
        path = "/$index",
        isDirectory = true,
    )
}
