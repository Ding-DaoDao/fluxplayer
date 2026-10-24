package com.fluxplayer.app.core.data.cloud

import android.content.Context
import com.fluxplayer.app.core.common.CloudAccountIdentity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** 后台操作只覆盖协程内的账号，不改变界面当前选择。 */
object CloudAccountScope {
    suspend fun <T> withAccount(context: Context, provider: String, id: String, block: suspend () -> T): T {
        require(CloudAccountStore.contains(context, provider, id)) { "文件所属账号已移除，请重新添加账号" }
        return withContext(CloudAccountIdentity.bindings.asContextElement(CloudAccountIdentity.bindings.get().orEmpty() + (provider to id))) { block() }
    }
}

/** 每个账号拥有独立的内存凭证、家庭会话及刷新互斥锁。 */
internal class CloudAccountState<T : Any>(private val provider: String, private val create: () -> T) {
    private val states = ConcurrentHashMap<String, T>()
    val current: T get() = states.computeIfAbsent(CloudAccountIdentity.active(provider)) { create() }
    fun clearAll() = states.clear()
}
