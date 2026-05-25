package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 备份/恢复数据包，包含用户设置、播放器设置、WebDAV 服务器和 OpenList 配置。
 * 历史记录不包含在内。
 */
@Serializable
data class BackupData(
    val version: Int = CURRENT_VERSION,
    val backupTime: Long = System.currentTimeMillis(),
    val appPreferences: ApplicationPreferences,
    val playerPreferences: PlayerPreferences,
    val webDavServers: WebDavServers,
    val openListConfig: OpenListBackupConfig? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class OpenListBackupConfig(
    val autoStart: Boolean = false,
    val adminPassword: String? = null,
)
