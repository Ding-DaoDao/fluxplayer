package com.fluxplayer.app.feature.tingshu

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.tingshu.TingshuRepository
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 两条网页登录入口（设置页、听书首页面板）共用的测试准备。
 *
 * 断言口径：点「打开网页登录」后 `pendingLogin` 必须被清空——只有配置面板里的
 * [WebLoginHost] 会消费它。原来宿主只挂在设置页组件上，另一条入口点了只会把
 * `pendingLogin` 写进 state 且永远不清，界面上就是「点了没反应」。
 */
internal object WebLoginTestSupport {
    const val QUARK_ENTRY = "sources_by_quark"
    const val QUARK_NAME = "夸克网盘"

    fun instrumentation(): Instrumentation = InstrumentationRegistry.getInstrumentation()

    fun context(): Context = instrumentation().targetContext

    fun repository(): TingshuRepository = TingshuRepository.get(context())

    /** 导入夸克 jar（它实现了 ILogin，配置面板里才有「打开网页登录」）。 */
    fun importQuark() {
        val jar = File(context().cacheDir, "sources_by_quark.jar")
        instrumentation().context.assets.open("sources_by_quark.jar").use { input ->
            jar.outputStream().use(input::copyTo)
        }
        runBlocking { repository().importSource(Uri.fromFile(jar)) }
    }

    fun removeQuark() {
        runBlocking { runCatching { repository().remove(QUARK_ENTRY) } }
        File(context().cacheDir, "sources_by_quark.jar").delete()
    }

    fun hasNode(nodes: SemanticsNodeInteractionCollection): Boolean = nodes.fetchSemanticsNodes().isNotEmpty()
}
