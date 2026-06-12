package com.fluxplayer.app.feature.videopicker.composables

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DownloadProgressOverlay(
    progress: Float,
    fileName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("下载中", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(fileName, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("${(progress * 100).toInt()}%")
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}
