package com.fluxplayer.app.feature.tingshu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.fluxplayer.app.feature.player.AudioPlayerTheme

/**
 * 仅测试用：直接渲染听书首页那条入口的配置面板（长按书源弹出的那个）。
 *
 * 验证「打开网页登录」是否真的被消费：`pendingLogin` 只有 [WebLoginHost] 会清零，
 * 少了宿主就会一直挂在 state 里，表现为点了没反应。
 */
class SourceWebLoginPanelPreviewActivity : ComponentActivity() {
    val viewModel: TingshuViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = TingshuRepository.get(this)
        val source = repository.sources.value.firstOrNull { it.id == QUARK_ID }
        setContent {
            AudioPlayerTheme {
                Surface(Modifier.fillMaxSize()) {
                    TingshuSourceConfigPanel(source = source, viewModel = viewModel, onDismiss = {})
                }
            }
        }
    }

    companion object {
        const val QUARK_ID = "quark"
    }
}
