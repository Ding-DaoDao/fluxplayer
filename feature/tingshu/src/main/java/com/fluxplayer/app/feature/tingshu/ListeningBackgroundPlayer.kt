package com.fluxplayer.app.feature.tingshu

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.fluxplayer.app.core.tingshu.ListeningErrors
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** 续播由服务完成；控制器跟随首页窗口，解析期间不会因临时连接释放而停止。 */
internal class ListeningBackgroundPlayer : ViewModel() {
    private val connections = mutableMapOf<ComponentName, ListenableFuture<MediaController>>()
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun dismissError() {
        error = null
    }

    fun start(context: Context, component: ComponentName, command: SessionCommand, args: Bundle) {
        if (starting) return
        starting = true
        error = null
        viewModelScope.launch {
            try {
                val app = context.applicationContext
                val executor = ContextCompat.getMainExecutor(app)
                connections[component]?.let { previous ->
                    if (previous.isDone && runCatching { !previous.get().isConnected }.getOrDefault(true)) {
                        connections.remove(component)
                        MediaController.releaseFuture(previous)
                    }
                }
                val future = connections.getOrPut(component) {
                    MediaController.Builder(app, SessionToken(app, component)).buildAsync()
                }
                val controller = future.await(executor)
                connections.filterKeys { it != component }.values.forEach { other ->
                    if (other.isDone && !other.isCancelled) runCatching { other.get().pause() }
                }
                val result = controller.sendCustomCommand(command, args).await(executor)
                check(result.resultCode == SessionResult.RESULT_SUCCESS) { "无法恢复播放，请重新选择书籍" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = ListeningErrors.describe(failure, "续播失败")
                connections.remove(component)?.let(MediaController::releaseFuture)
            } finally {
                starting = false
            }
        }
    }

    override fun onCleared() {
        connections.values.forEach(MediaController::releaseFuture)
        connections.clear()
    }
}

private suspend fun <T> ListenableFuture<T>.await(executor: java.util.concurrent.Executor): T = suspendCancellableCoroutine { continuation ->
    addListener({
        if (continuation.isActive) {
            try {
                continuation.resume(get())
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    }, executor)
}
