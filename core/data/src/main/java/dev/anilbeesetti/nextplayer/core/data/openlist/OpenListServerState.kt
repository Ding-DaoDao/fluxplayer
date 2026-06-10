package dev.anilbeesetti.nextplayer.core.data.openlist

/**
 * OpenList 本地服务器运行状态。
 */
sealed class OpenListServerState {
    /** 已停止 */
    data object Stopped : OpenListServerState()

    /** 启动中 */
    data class Starting(val port: Int = 5244) : OpenListServerState()

    /** 运行中 */
    data class Running(
        val port: Int = 5244,
        val webUiUrl: String = "http://127.0.0.1:$port",
        val pid: Long = 0,
    ) : OpenListServerState()

    /** 异常 */
    data class Error(val message: String, val cause: Throwable? = null) : OpenListServerState()
}
