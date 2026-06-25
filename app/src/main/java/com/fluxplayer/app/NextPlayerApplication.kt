package com.fluxplayer.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import com.fluxplayer.app.core.common.di.ApplicationScope
import com.fluxplayer.app.core.data.backup.AutoBackupHelper
import com.fluxplayer.app.core.data.openlist.OpenListManager
import com.fluxplayer.app.core.data.openlist.OpenListManagerProvider
import com.fluxplayer.app.core.data.repository.MediaRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.crash.CrashActivity
import com.fluxplayer.app.core.data.openlist.OpenListService
import com.fluxplayer.app.crash.GlobalExceptionHandler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class NextPlayerApplication : Application(), SingletonImageLoader.Factory {

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

        // 清理超过 30 天未播放的 media_state 记录
        applicationScope.launch(Dispatchers.IO) {
            val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
            mediaRepository.deleteStaleState(System.currentTimeMillis() - thirtyDaysMs)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
