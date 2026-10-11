package com.fluxplayer.app.feature.tingshu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.fluxplayer.app.feature.player.AudioPlayerTheme

/** 仅在独立测试包中展示书源首页，供手机布局检查使用。 */
class JdrDiscoveryPreviewActivity : ComponentActivity() {
    lateinit var model: TingshuViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[TingshuViewModel::class.java]
        model.repository.sources.value.firstOrNull { it.id == "jdr:bilibili" }?.let(model::open)
        setContent {
            AudioPlayerTheme {
                Surface(Modifier.fillMaxSize()) {
                    TingshuSourceContent(Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()), viewModel = model)
                }
            }
        }
    }
}
