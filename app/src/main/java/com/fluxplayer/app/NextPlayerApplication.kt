package com.fluxplayer.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.fluxplayer.app.core.common.di.ApplicationScope
import com.fluxplayer.app.core.data.backup.AutoBackupHelper
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.cloud189.C189SessionKeeper
import com.fluxplayer.app.core.data.openlist.OpenListManager
import com.fluxplayer.app.core.data.openlist.OpenListManagerProvider
import com.fluxplayer.app.core.data.openlist.OpenListService
import com.fluxplayer.app.core.data.repository.MediaRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.tingshu.ListeningAudioPreferencesProvider
import com.fluxplayer.app.crash.CrashActivity
import com.fluxplayer.app.crash.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class NextPlayerApplication : Application(), SingletonImageLoader.Factory, ListeningAudioPreferencesProvider {

    override val listeningRequireAudioFocus: Flow<Boolean>
        get() = preferencesRepository.persistedPlayerPreferences.map { it.requireAudioFocus }

    override val listeningPauseOnHeadsetDisconnect: Flow<Boolean>
        get() = preferencesRepository.persistedPlayerPreferences.map { it.pauseOnHeadsetDisconnect }

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var autoBackupHelper: AutoBackupHelper

    @Inject
    lateinit var mediaRepository: MediaRepository

    lateinit var openListManager: OpenListManager
        private set

    override fun onCreate() {
        super.onCreate()

        C189AuthProvider.initialize(this)
        registerActivityLifecycleCallbacks(C189SessionKeeper(applicationScope))

        // OpenList 管理器初始化
        openListManager = OpenListManager(this, applicationScope)
        OpenListManagerProvider.init(openListManager)

        // 自动启动 OpenList
        if (openListManager.getAutoStart()) {
            OpenListService.start(this)
        }

        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))

        // 如果启用自动检查，在后台检查新备份
        applicationScope.launch(Dispatchers.IO) {
            autoBackupHelper.checkNewBackupAvailable()
        }

        // 「打开应用后备份一次」的时机：进程启动即触发，间隔由备份设置控制
        applicationScope.launch(Dispatchers.IO) {
            autoBackupHelper.performAutoBackup(onLaunch = true)
        }

        // 清理超过 30 天未播放的 media_state 记录
        applicationScope.launch(Dispatchers.IO) {
            val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
            mediaRepository.deleteStaleState(System.currentTimeMillis() - thirtyDaysMs)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
