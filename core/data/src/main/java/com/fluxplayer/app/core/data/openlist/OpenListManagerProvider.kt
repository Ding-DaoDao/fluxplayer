package com.fluxplayer.app.core.data.openlist

/**
 * OpenListManager 的全局持有者，由 Application.onCreate() 初始化。
 *
 * ViewModel 通过此提供者获取 manager 实例，避免跨模块引用 app module。
 */
object OpenListManagerProvider {
    @Volatile
    private var instance: OpenListManager? = null

    fun get(): OpenListManager? = instance

    fun init(manager: OpenListManager) {
        instance = manager
    }
}
