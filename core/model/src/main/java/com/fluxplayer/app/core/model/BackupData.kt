package com.fluxplayer.app.core.model

import kotlinx.serialization.Serializable

/**
 * 备份/恢复数据包，包含用户设置、播放器设置、WebDAV 服务器、OpenList 配置和云盘凭证。
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
    val alipanConfig: AlipanBackupConfig? = null,
    val cloud189Config: Cloud189BackupConfig? = null,
    val pan123Config: Pan123BackupConfig? = null,
    val quarkConfig: QuarkBackupConfig? = null,
    val yun139Config: Yun139BackupConfig? = null,
    val backupWebDavConfig: BackupWebDavConfig? = null,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

@Serializable
data class OpenListBackupConfig(
    val autoStart: Boolean = false,
    val adminPassword: String? = null,
    val databaseIncluded: Boolean = false,
)

@Serializable
data class AlipanBackupConfig(
    val authorization: String? = null,
    val driveId: String? = null,
    val refreshToken: String? = null,
    val deviceId: String? = null,
    val signature: String? = null,
    val cookies: String? = null,
)

@Serializable
data class Cloud189BackupConfig(
    val accessToken: String? = null,
    val sessionKey: String? = null,
    val sessionSecret: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long = 0L,
    val cookies: String? = null,
)

@Serializable
data class Pan123BackupConfig(
    val passport: String? = null,
    val password: String? = null,
    val token: String? = null,
)

@Serializable
data class QuarkBackupConfig(
    val quarkCookie: String? = null,
    val ucCookie: String? = null,
)

@Serializable
data class Yun139BackupConfig(
    val authorization: String? = null,
    val phoneNumber: String? = null,
    val userDomainId: String? = null,
    val lastRefresh: Long = 0L,
)
