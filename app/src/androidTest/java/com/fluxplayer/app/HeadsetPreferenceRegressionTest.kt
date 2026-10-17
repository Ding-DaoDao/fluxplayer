package com.fluxplayer.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.feature.player.service.PlayerService
import com.fluxplayer.app.feature.tingshu.TingshuPlaybackService
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** 验证同一个偏好能实时更新两个服务，避免依赖设备实际拔插耳机。 */
class HeadsetPreferenceRegressionTest {
    @Test
    fun bothPlaybackServicesApplyHeadsetSettingAtCreationAndWhileRunning() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = (context.applicationContext as NextPlayerApplication).preferencesRepository
        val original = preferences.persistedPlayerPreferences.first().pauseOnHeadsetDisconnect
        val connections = mutableListOf<ServiceConnection>()
        val controllers = mutableListOf<MediaController>()
        val services = mutableListOf<MediaSessionService>()
        try {
            preferences.updatePlayerPreferences { it.copy(pauseOnHeadsetDisconnect = false) }
            for (type in listOf(PlayerService::class.java, TingshuPlaybackService::class.java)) {
                lateinit var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>
                instrumentation.runOnMainSync {
                    controller = MediaController.Builder(context, SessionToken(context, ComponentName(context, type))).buildAsync()
                }
                controllers += controller.get(15, TimeUnit.SECONDS)
                val ready = CompletableFuture<MediaSessionService>()
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        try {
                            val reference = binder.javaClass.getDeclaredField("serviceReference").apply { isAccessible = true }.get(binder) as WeakReference<*>
                            ready.complete(reference.get() as MediaSessionService)
                        } catch (error: Throwable) {
                            ready.completeExceptionally(error)
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName) = Unit
                }
                check(context.bindService(Intent(context, type).setAction(MediaSessionService.SERVICE_INTERFACE), connection, Context.BIND_AUTO_CREATE))
                connections += connection
                services += ready.get(10, TimeUnit.SECONDS)
            }
            suspend fun awaitEnabled(expected: Boolean) = withTimeout(5000) {
                while (true) {
                    var values = emptyList<Boolean>()
                    instrumentation.runOnMainSync {
                        values = services.map { service ->
                            val player = service.sessions.single().player
                            val manager = player.javaClass.getDeclaredField("audioBecomingNoisyManager").apply { isAccessible = true }.get(player)
                            manager.javaClass.getDeclaredField("isEnabled").apply { isAccessible = true }.getBoolean(manager)
                        }
                    }
                    if (values.all { it == expected }) break
                    delay(20)
                }
            }
            awaitEnabled(false)
            preferences.updatePlayerPreferences { it.copy(pauseOnHeadsetDisconnect = true) }
            awaitEnabled(true)
            preferences.updatePlayerPreferences { it.copy(pauseOnHeadsetDisconnect = false) }
            awaitEnabled(false)
        } finally {
            preferences.updatePlayerPreferences { it.copy(pauseOnHeadsetDisconnect = original) }
            instrumentation.runOnMainSync { controllers.forEach(MediaController::release) }
            connections.forEach(context::unbindService)
            context.stopService(Intent(context, PlayerService::class.java))
            context.stopService(Intent(context, TingshuPlaybackService::class.java))
        }
    }
}
