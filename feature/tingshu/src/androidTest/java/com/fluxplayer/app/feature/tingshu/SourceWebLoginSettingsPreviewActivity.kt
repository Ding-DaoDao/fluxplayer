package com.fluxplayer.app.feature.tingshu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fluxplayer.app.feature.player.AudioPlayerTheme

/**
 * 仅测试用：渲染「设置 → 听书配置」整页（[TingshuConfigContent]）。
 *
 * 这条入口要先点书源列表里的源、再点「打开网页登录」，用来确认它和听书首页那条
 * 走的是同一套登录接线。
 */
class SourceWebLoginSettingsPreviewActivity : ComponentActivity() {
    val viewModel: TingshuViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioPlayerTheme {
                Surface(Modifier.fillMaxSize()) {
                    TingshuConfigContent(viewModel = viewModel)
                }
            }
        }
    }
}
