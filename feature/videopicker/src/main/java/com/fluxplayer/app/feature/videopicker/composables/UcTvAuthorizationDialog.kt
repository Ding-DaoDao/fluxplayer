package com.fluxplayer.app.feature.videopicker.composables

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.data.quark.UcTvService
import com.fluxplayer.app.core.ui.components.NextDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun UcTvAuthorizationDialog(service: UcTvService, onDismiss: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    var authorization by remember { mutableStateOf<UcTvService.Authorization?>(null) }
    var message by remember { mutableStateOf("正在获取二维码") }
    var complete by remember { mutableStateOf(false) }
    LaunchedEffect(retry) {
        authorization = null
        message = "正在获取二维码"
        try {
            val qr = service.beginAuthorization()
            authorization = qr
            message = "请使用当前网页登录的 UC 账号扫码确认"
            while (!service.pollAuthorization(qr)) delay(2000)
            complete = true
            authorization = null
            message = "授权完成，播放时将优先选择 TV 最高可用画质"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message = error.message ?: "授权失败，请重试"
        }
    }
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text("UC TV 播放授权") },
        content = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                authorization?.let { qr ->
                    val bitmap = remember(qr) { BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)?.asImageBitmap() }
                    bitmap?.let { Surface(color = Color.White, shape = MaterialTheme.shapes.medium) { Image(it, "UC TV 授权二维码", Modifier.padding(16.dp).size(208.dp)) } }
                }
                Text(message)
                Text("通过 Extscreen 服务完成授权，可在播放设置中随时撤销。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (complete) "完成" else "关闭") } },
        dismissButton = { if (!complete) TextButton(onClick = { retry++ }) { Text("重新获取") } },
    )
}
