package com.fluxplayer.app.feature.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 在所属界面线程调用；即使底层忽略取消，也只接收最新请求的结果。 */
internal class LatestTask(private val scope: CoroutineScope) {
    private var generation = 0L
    private var job: Job? = null

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun <T> launch(load: suspend () -> T, onSuccess: (T) -> Unit, onFailure: (Exception) -> Unit) {
        cancel()
        val request = generation
        job = scope.launch {
            try {
                val result = load()
                currentCoroutineContext().ensureActive()
                if (request == generation) onSuccess(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == generation) onFailure(error)
            }
        }
    }
}
