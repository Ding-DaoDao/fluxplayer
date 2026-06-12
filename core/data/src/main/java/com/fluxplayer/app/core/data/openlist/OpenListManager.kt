package com.fluxplayer.app.core.data.openlist

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.TimeUnit

/**
 * OpenList 进程管理器 —— 启动 / 停止 / 健康检查 / CLI 密码管理。
 *
 * 使用示例：
 * ```kotlin
 * val manager = OpenListManager(context)
 * manager.start()
 * manager.state.collect { state -> ... }
 * ```
 */
class OpenListManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val apiClient: OpenListApiClient = OpenListApiClient(),
) {

    companion object {
        private const val TAG = "OpenListManager"
        private const val DEFAULT_PORT = 5244
        private const val MAX_RESTART_ATTEMPTS = 3
        private const val HEALTH_CHECK_INTERVAL_MS = 5000L
        private const val PREFS_NAME = "openlist_prefs"
        private const val PREF_PASSWORD = "admin_password"
        private const val PREF_AUTO_START = "openlist_auto_start"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val binaryExtractor = OpenListBinaryExtractor(context)

    // ---- 服务器状态 ----
    private val _state = MutableStateFlow<OpenListServerState>(OpenListServerState.Stopped)
    val state: StateFlow<OpenListServerState> = _state.asStateFlow()

    // ---- 密码 ----
    private val _initialPassword = MutableStateFlow<String?>(null)
    val initialPassword: StateFlow<String?> = _initialPassword.asStateFlow()

    private val _adminSetPassword = MutableStateFlow<String?>(getSavedPassword())
    val adminSetPassword: StateFlow<String?> = _adminSetPassword.asStateFlow()

    // ---- 内部状态 ----
    private var process: Process? = null
    private var binaryPath: String? = null
    private var healthJob: Job? = null
    private var restartCount = 0
    private var logReaderJob: Job? = null

    // ====================================================================
    // 启动 / 停止
    // ====================================================================

    /**
     * 启动 OpenList 服务器进程。
     */
    fun start() {
        if (_state.value is OpenListServerState.Starting ||
            _state.value is OpenListServerState.Running
        ) {
            Log.d(TAG, "start: 已在运行中，跳过")
            return
        }

        _state.value = OpenListServerState.Starting(DEFAULT_PORT)

        scope.launch {
            try {
                val binary = binaryExtractor.ensureBinaryExtracted().getOrThrow()
                binaryPath = binary

                val dataDir = context.filesDir.resolve("openlist_data").apply { mkdirs() }
                var savedPwd = getSavedPassword()

                // 如果没有保存的密码，删除旧数据库重新开始
                // （旧数据库的密码无法恢复，只能重建）
                if (savedPwd.isNullOrBlank()) {
                    val dbFile = java.io.File(dataDir, "data.db")
                    if (dbFile.exists()) {
                        Log.w(TAG, "无保存密码，删除旧数据库重新开始")
                        dbFile.delete()
                        java.io.File(dataDir, "data.db-wal").delete()
                        java.io.File(dataDir, "data.db-shm").delete()
                    }
                }

                val command = arrayOf(binary, "server", "--data", dataDir.absolutePath)
                val env = mutableMapOf(
                    "OPENLIST_DATA" to dataDir.absolutePath,
                    "OPENLIST_PORT" to DEFAULT_PORT.toString(),
                    "OPENLIST_LOG" to "info",
                )
                if (!savedPwd.isNullOrBlank()) {
                    env["OPENLIST_ADMIN_PASSWORD"] = savedPwd
                }

                val pb = ProcessBuilder(*command)
                pb.redirectErrorStream(true)  // 合并 stderr 到 stdout，确保能捕获初始密码
                pb.environment().putAll(env)
                pb.directory(context.filesDir)

                val proc = pb.start()
                process = proc

                // 读取 stdout / stderr
                logReaderJob = scope.launch {
                    launchLogReader(proc)
                }

                // 进程已启动，立即标记为运行中（不依赖 ping 检测）
                _state.value = OpenListServerState.Running(
                    port = DEFAULT_PORT,
                    pid = getPid(proc),
                )

                // 健康检查（监测进程存活 + ping）
                healthJob = scope.launch {
                    startHealthCheck(proc)
                }

            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                _state.value = OpenListServerState.Error("启动失败: ${e.message}", e)
            }
        }
    }

    /**
     * 停止 OpenList 服务器进程（异步，不等待完成）。
     */
    fun stop() {
        healthJob?.cancel()
        healthJob = null
        logReaderJob?.cancel()
        logReaderJob = null

        val proc = process
        process = null

        if (proc != null) {
            scope.launch {
                proc.destroy() // SIGTERM
                try {
                    proc.waitFor(3, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // ignore
                }
                if (proc.isAlive) {
                    proc.destroyForcibly() // SIGKILL
                }
                _state.value = OpenListServerState.Stopped
                restartCount = 0
            }
        } else {
            // 进程还没创建（Starting 阶段就点击停止），直接切回 Stopped
            _state.value = OpenListServerState.Stopped
            restartCount = 0
        }
    }

    /**
     * 同步停止服务器进程，等待完全终止后返回。
     * 用于 CLI 密码操作等需要确保进程已退出的场景。
     */
    private suspend fun stopAndWait() {
        healthJob?.cancel()
        healthJob = null
        logReaderJob?.cancel()
        logReaderJob = null

        val proc = process
        process = null

        if (proc != null) {
            proc.destroy()
            try {
                proc.waitFor(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // ignore
            }
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
        }
        _state.value = OpenListServerState.Stopped
        restartCount = 0
    }

    // ====================================================================
    // 健康检查
    // ====================================================================

    private suspend fun startHealthCheck(proc: Process) {
        while (currentCoroutineContext().isActive) {
            delay(HEALTH_CHECK_INTERVAL_MS)

            if (!proc.isAlive) {
                // 进程挂了
                if (restartCount < MAX_RESTART_ATTEMPTS) {
                    restartCount++
                    Log.w(TAG, "进程挂了，自动重启 (attempt $restartCount/$MAX_RESTART_ATTEMPTS)")
                    start()
                } else {
                    _state.value = OpenListServerState.Error("自动重启失败（已尝试 $MAX_RESTART_ATTEMPTS 次）")
                }
                return
            }

            // 检测端口可连接
            try {
                val pingOk = apiClient.ping().getOrDefault(false)
                if (pingOk && _state.value !is OpenListServerState.Running) {
                    val pid = getPid(proc)
                    _state.value = OpenListServerState.Running(
                        port = DEFAULT_PORT,
                        pid = pid,
                    )
                }
            } catch (_: Exception) {
                // 端口还没就绪，继续等
            }
        }
    }

    // ====================================================================
    // 日志读取 + 初始密码解析
    // ====================================================================

    private suspend fun launchLogReader(proc: Process) {
        try {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            while (currentCoroutineContext().isActive) {
                val text = reader.readLine() ?: break
                Log.d(TAG, "[stdout] $text")

                // 解析初始密码: "the initial password is: XXXXXXXX"
                val passwordPrefix = "the initial password is: "
                val idx = text.indexOf(passwordPrefix)
                if (idx >= 0) {
                    val pwd = text.substring(idx + passwordPrefix.length).trim()
                    _initialPassword.value = pwd
                    savePassword(pwd)  // 立即持久化，避免后续启动丢失
                    Log.i(TAG, "检测到初始密码: $pwd (已保存到 SharedPreferences)")
                }
            }
        } catch (e: Exception) {
            if (e !is java.io.IOException || process?.isAlive == true) {
                Log.e(TAG, "log reader error", e)
            }
        }
    }

    // ====================================================================
    // 密码修改（通过 CLI）
    // ====================================================================

    /**
     * 通过 CLI 修改管理员密码。
     * 流程：停止服务 → 执行 admin set → 保存密码 → 重启服务。
     */
    suspend fun setPasswordViaCli(newPassword: String, currentPassword: String? = null): Result<Unit> {
        val binary = binaryPath
            ?: return Result.failure(IllegalStateException("二进制文件未就绪"))

        Log.d(TAG, "setPasswordViaCli: newPwd=${newPassword.take(4)}...")

        // 停止服务
        stopAndWait()
        delay(500)

        val dataDir = context.filesDir.resolve("openlist_data")
        val proc = try {
            ProcessBuilder(binary, "admin", "set", newPassword, "--data", dataDir.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "CLI admin set 启动失败", e)
            start()
            return Result.failure(RuntimeException("CLI 执行失败: ${e.message}"))
        }

        // 等待完成
        val exited = try {
            proc.waitFor(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!exited) {
            proc.destroyForcibly()
            start()
            return Result.failure(RuntimeException("CLI 执行超时"))
        }

        val exitCode = proc.exitValue()
        // 读取输出（调试用）
        val output = try {
            proc.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "" }
        Log.d(TAG, "setPasswordViaCli: exitCode=$exitCode, output='$output'")

        if (exitCode == 0) {
            savePassword(newPassword)
            _adminSetPassword.value = newPassword
            Log.i(TAG, "密码已通过 CLI 设置")
        }

        // 重启服务
        start()

        return if (exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("CLI 执行失败(exit=$exitCode): $output"))
        }
    }

    /**
     * 通过 CLI 随机生成密码。
     * 流程：停止服务 → 执行 admin random → 解析输出 → 保存密码 → 重启服务。
     */
    suspend fun randomPasswordViaCli(currentPassword: String? = null): Result<String> {
        val binary = binaryPath
            ?: return Result.failure(IllegalStateException("二进制文件未就绪"))

        Log.d(TAG, "randomPasswordViaCli")

        // 停止服务
        stopAndWait()
        delay(500)

        val dataDir = context.filesDir.resolve("openlist_data")
        val proc = try {
            ProcessBuilder(binary, "admin", "random", "--data", dataDir.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "CLI admin random 启动失败", e)
            start()
            return Result.failure(RuntimeException("CLI 执行失败: ${e.message}"))
        }

        val output = try {
            proc.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "" }

        val exited = try {
            proc.waitFor(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { false }
        if (!exited) {
            proc.destroyForcibly()
            start()
            return Result.failure(RuntimeException("CLI 执行超时"))
        }

        val exitCode = proc.exitValue()
        Log.d(TAG, "randomPasswordViaCli: exitCode=$exitCode, output='$output'")

        // 重启服务
        start()

        if (exitCode != 0) {
            return Result.failure(RuntimeException("CLI 执行失败(exit=$exitCode): $output"))
        }

        // 解析密码：输出中包含 "password: <value>"
        val passwordPrefix = "password: "
        val idx = output.indexOf(passwordPrefix)
        val pwd = if (idx >= 0) {
            output.substring(idx + passwordPrefix.length).trim()
        } else {
            output.trim()
        }

        if (pwd.isBlank()) {
            return Result.failure(RuntimeException("未能解析出密码，输出: $output"))
        }

        savePassword(pwd)
        _adminSetPassword.value = pwd
        Log.i(TAG, "随机密码已通过 CLI 设置: ${pwd.take(4)}...")
        return Result.success(pwd)
    }

    // ====================================================================
    // 自动启动偏好
    // ====================================================================

    fun getAutoStart(): Boolean = prefs.getBoolean(PREF_AUTO_START, false)

    fun setAutoStart(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_START, enabled).apply()
    }

    // ====================================================================
    // 密码持久化
    // ====================================================================

    private fun getSavedPassword(): String? {
        return prefs.getString(PREF_PASSWORD, null)?.takeIf { it.isNotBlank() }
    }

    private fun savePassword(password: String) {
        prefs.edit().putString(PREF_PASSWORD, password).apply()
    }

    // ====================================================================
    // 辅助
    // ====================================================================

    private fun getPid(proc: Process): Long {
        return try {
            val pidField = proc::class.java.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getLong(proc)
        } catch (_: Exception) {
            0L
        }
    }
}
