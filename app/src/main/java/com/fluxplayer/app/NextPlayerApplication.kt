package com.fluxplayer.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import com.fluxplayer.app.core.common.di.ApplicationScope
import com.fluxplayer.app.core.data.openlist.OpenListManager
import com.fluxplayer.app.core.data.openlist.OpenListManagerProvider
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.crash.CrashActivity
import com.fluxplayer.app.core.data.openlist.OpenListService
import com.fluxplayer.app.crash.GlobalExceptionHandler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

@HiltAndroidApp
class NextPlayerApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

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
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
