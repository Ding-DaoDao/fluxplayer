package dev.anilbeesetti.nextplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import dev.anilbeesetti.nextplayer.core.common.di.ApplicationScope
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListManager
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListManagerProvider
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.crash.CrashActivity
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListService
import dev.anilbeesetti.nextplayer.crash.GlobalExceptionHandler
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
