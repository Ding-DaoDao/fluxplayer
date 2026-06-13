package com.fluxplayer.app.core.ui.components

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
import com.fluxplayer.app.core.model.ComposeEngine
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.core.ui.theme.LocalHazeState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as MiuixSmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Engine-aware settings scaffold. Uses MiuixScaffold + MiuixSmallTopAppBar in Miuix mode,
 * standard Scaffold + NextTopAppBar in Material mode.
 * Provides LocalHazeState for downstream blur-aware components.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FluxSettingsScaffold(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    enableBlur: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val isMiuix = FluxTheme.engine == ComposeEngine.MIUIX
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(
        LocalHazeState provides if (enableBlur) hazeState else null,
    ) {
        if (isMiuix) {
            MiuixScaffold(
                modifier = modifier,
                topBar = {
                    MiuixSmallTopAppBar(
                        title = title,
                        navigationIcon = {
                            FluxIconButton(onClick = onNavigateUp) {
                                FluxIcon(
                                    imageVector = NextIcons.ArrowBack,
                                    contentDescription = stringResource(id = R.string.navigate_up),
                                )
                            }
                        },
                    )
                },
                containerColor = MiuixTheme.colorScheme.surface,
                content = content,
            )
        } else {
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
                content = content,
            )
        }
    }
}
