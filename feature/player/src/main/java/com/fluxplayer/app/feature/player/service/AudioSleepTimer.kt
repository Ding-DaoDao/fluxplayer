package com.fluxplayer.app.feature.player.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 听书睡眠定时器的共享状态。
 *
 * 持有者是一个进程级单例而不是 Compose 的 remember 状态：定时逻辑由 PlayerService
 * 驱动（倒计时与暂停都在 Service 内执行），Activity 因旋转/息屏/进程回收重建后，
 * UI 重新收集该 StateFlow 即可恢复定时器的剩余时间显示。
 * Service 与播放 UI 在同一进程内，直接读写该单例即可，无需跨进程 IPC。
 */
object AudioSleepTimer {

    data class State(
        /** 分钟模式剩余秒数；0 表示未激活或已到时 */
        val remainingSeconds: Int = 0,
        /** 按集数模式剩余集数；0 表示未激活 */
        val remainingEpisodes: Int = 0,
        /** 按集数模式激活时的起始章节索引 */
        val startEpisodeIndex: Int = -1,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 按分钟启动（seconds 为总秒数）。 */
    fun startMinutes(seconds: Int) {
        _state.value = State(remainingSeconds = seconds)
    }

    /** 按集数启动，currentEpisodeIndex 为激活时的当前章节索引。 */
    fun startEpisodes(episodes: Int, currentEpisodeIndex: Int) {
        _state.value = State(remainingEpisodes = episodes, startEpisodeIndex = currentEpisodeIndex)
    }

    /** 取消定时。 */
    fun cancel() {
        _state.value = State()
    }

    /** 由 Service 的倒计时循环每秒调用（仅在播放中），返回是否已到时。 */
    fun tickSecond(): Boolean {
        val current = _state.value
        if (current.remainingSeconds <= 0) return false
        val next = current.remainingSeconds - 1
        _state.value = current.copy(remainingSeconds = next)
        return next <= 0
    }
}
