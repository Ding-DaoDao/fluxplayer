package com.fluxplayer.app.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.LocalHazeState
import com.fluxplayer.app.core.ui.theme.LocalMediaBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState

/**
 * 设置页骨架：Scaffold + NextTopAppBar，并向内部 provide [LocalHazeState] 供下游毛玻璃组件消费。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FluxSettingsScaffold(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    enableBlur: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val hazeState = remember { HazeState() }
    val backdrop = rememberLayerBackdrop()

    CompositionLocalProvider(
        LocalHazeState provides if (enableBlur) hazeState else null,
        LocalMediaBackdrop provides backdrop,
    ) {
        Scaffold(
            modifier = modifier,
            topBar = {
                NextTopAppBar(
                    title = title,
                    navigationIcon = {
                        FilledTonalIconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = NextIcons.ArrowBack,
                                contentDescription = stringResource(id = R.string.navigate_up),
                            )
                        }
                    },
                )
            },
            containerColor = containerColor,
            content = { padding ->
                Box(Modifier.layerBackdrop(backdrop)) {
                    // 被录入的内容不能再采样同一背景，否则绘图层会循环引用。
                    CompositionLocalProvider(LocalMediaBackdrop provides null) {
                        content(padding)
                    }
                }
            },
        )
    }
}
