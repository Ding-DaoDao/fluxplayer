package com.fluxplayer.app.core.data.cloud189

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 应用有可见页面时保活，进入后台后停止，避免额外的后台耗电。 */
class C189SessionKeeper(private val scope: CoroutineScope) : Application.ActivityLifecycleCallbacks {
    private var visibleActivities = 0
    private var keepAliveJob: Job? = null

    override fun onActivityStarted(activity: Activity) {
        visibleActivities++
        if (keepAliveJob != null) return
        keepAliveJob = scope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                if (C189AuthProvider.isActive) {
                    val client = C189ApiClient()
                    if (!C189AuthProvider.hasValidSession() || C189AuthProvider.isTokenExpired()) {
                        if (!client.refreshAccessToken()) {
                            Log.w("C189SessionKeeper", "天翼会话暂时无法恢复，保留凭证等待下次重试")
                            continue
                        }
                    }
                    val result = client.keepAlive()
                    ensureActive()
                    result.onFailure { Log.w("C189SessionKeeper", "天翼会话保活失败，保留登录凭证", it) }
                }
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        visibleActivities--
        if (visibleActivities == 0) {
            keepAliveJob?.cancel()
            keepAliveJob = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
