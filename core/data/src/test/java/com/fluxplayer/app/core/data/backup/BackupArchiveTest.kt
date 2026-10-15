package com.fluxplayer.app.core.data.backup

import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.BackupData
import com.fluxplayer.app.core.model.BackupWebDavConfig
import com.fluxplayer.app.core.model.Pan123BackupConfig
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.model.WebDavServers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveTest {
    private val data = BackupData(
        backupTime = 1234L,
        appPreferences = ApplicationPreferences(audiobookPlaybackSpeed = 1.5f),
        playerPreferences = PlayerPreferences(defaultPlaybackSpeed = 2f),
        webDavServers = WebDavServers(),
    )

    @Test
    fun zipRoundTripPreservesSettingsAndBinaryOpenListData() {
        val binary = byteArrayOf(0, 1, -1, 4)
        val encoded = BackupArchiveCodec.encode(BackupArchive(data, mapOf("data.db" to binary)))
        val restored = BackupArchiveCodec.decode(ByteArrayInputStream(encoded))
        assertEquals(data, restored.data)
        assertArrayEquals(binary, restored.openListFiles.getValue("data.db"))
    }

    @Test
    fun legacyJsonWithBomCanBeImportedWithoutZipFallback() {
        val encoded = "\uFEFF" + Json.encodeToString(BackupData.serializer(), data.copy(version = 1))
        val restored = BackupArchiveCodec.decode(ByteArrayInputStream(encoded.toByteArray(Charsets.UTF_8)))
        assertEquals(1, restored.data.version)
        assertEquals(data.appPreferences, restored.data.appPreferences)
        assertTrue(restored.openListFiles.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureBackupVersionIsRejectedBeforeRestore() {
        val text = Json.encodeToString(BackupData.serializer(), data.copy(version = BackupData.CURRENT_VERSION + 1))
        BackupArchiveCodec.decode(ByteArrayInputStream(text.toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zipWithoutMetadataIsRejectedEvenIfItContainsOpenListFiles() {
        BackupArchiveCodec.decode(ByteArrayInputStream(zip("openlist_data/data.db" to byteArrayOf(1))))
    }

    @Test
    fun traversalAndAbsoluteOpenListPathsAreRejected() {
        listOf("../data.db", "/data.db", "folder/data.db", "..\\data.db", "C:data.db").forEach { name ->
            val bytes = zip("backup.json" to Json.encodeToString(BackupData.serializer(), data).toByteArray(), "openlist_data/$name" to byteArrayOf(1))
            assertTrue(name, runCatching { BackupArchiveCodec.decode(ByteArrayInputStream(bytes)) }.isFailure)
        }
    }

    @Test
    fun corruptJsonAndUnrelatedZipAreNotAcceptedAsBackups() {
        assertTrue(runCatching { BackupArchiveCodec.decode(ByteArrayInputStream("{}".toByteArray())) }.isFailure)
        assertTrue(runCatching { BackupArchiveCodec.decode(ByteArrayInputStream(zip("photo.jpg" to byteArrayOf(1)))) }.isFailure)
    }

    @Test
    fun oversizedExpandedZipIsRejected() {
        val expanded = ByteArray(17 * 1024 * 1024) { ' '.code.toByte() }
        assertTrue(runCatching { BackupArchiveCodec.decode(ByteArrayInputStream(zip("backup.json" to expanded))) }.isFailure)
    }

    @Test
    fun webDavListingOnlyRecognizesFluxPlayerBackupNames() {
        assertTrue(isBackupFileName("fluxplayer_backup_phone_2026-10-13_08-00-00.zip"))
        assertFalse(isBackupFileName("holiday.zip"))
        assertFalse(isBackupFileName("fluxplayer_backup_photo.jpg"))
    }

    // ==================== 旧备份清理（保留策略） ====================

    @Test
    fun onlyStrictlyNamedBackupFilesArePrunable() {
        // 旧版命名（无毫秒与随机串）与当前命名都必须能被识别。
        assertTrue(isPrunableBackupFileName("fluxplayer_backup_2026-05-01_10-00-00.zip"))
        assertTrue(isPrunableBackupFileName("fluxplayer_backup_phone_2026-10-13_08-00-00.zip"))
        assertTrue(isPrunableBackupFileName("fluxplayer_backup_2026-10-15_18-39-37_123_a1b2c3d4.zip"))
        assertTrue(isPrunableBackupFileName("fluxplayer_backup_My_Phone_2026-10-15_18-39-37_123_a1b2c3d4.zip"))
        // 宽松前缀能过、严格匹配必须拦住，否则会把用户自己改名的文件删掉。
        assertTrue(isBackupFileName("fluxplayer_backup_重要资料.zip"))
        assertFalse(isPrunableBackupFileName("fluxplayer_backup_重要资料.zip"))
        assertFalse(isPrunableBackupFileName("fluxplayer_backup_2026-05-01.zip"))
        assertFalse(isPrunableBackupFileName("fluxplayer_backup_2026-05-01_10-00-00.rar"))
        assertFalse(isPrunableBackupFileName("holiday.zip"))
        assertFalse(isPrunableBackupFileName("fluxplayer_backup_photo.jpg"))
    }

    @Test
    fun backupTimestampKeySortsNewestFirst() {
        val names = listOf(
            "fluxplayer_backup_2026-05-01_10-00-00.zip",
            "fluxplayer_backup_2026-10-15_18-39-37_123_a1b2c3d4.zip",
            "fluxplayer_backup_phone_2026-10-13_08-00-00.zip",
        )
        assertEquals(
            listOf(
                "fluxplayer_backup_2026-10-15_18-39-37_123_a1b2c3d4.zip",
                "fluxplayer_backup_phone_2026-10-13_08-00-00.zip",
                "fluxplayer_backup_2026-05-01_10-00-00.zip",
            ),
            names.sortedByDescending { backupFileTimestampKey(it).orEmpty() },
        )
        // 解析不出时间戳的名字不应参与排序（调用方会先用 isPrunableBackupFileName 过滤）。
        assertNull(backupFileTimestampKey("fluxplayer_backup_重要资料.zip"))
    }

    @Test
    fun retentionCountIsClampedAndZeroMeansUnlimited() {
        // 默认保留 10 份。
        assertEquals(BackupWebDavConfig.DEFAULT_AUTO_BACKUP_RETENTION_COUNT, BackupWebDavConfig().effectiveAutoBackupRetentionCount)
        // 0 与负数都表示不清理。
        assertEquals(0, BackupWebDavConfig(autoBackupRetentionCount = 0).effectiveAutoBackupRetentionCount)
        assertEquals(0, BackupWebDavConfig(autoBackupRetentionCount = -5).effectiveAutoBackupRetentionCount)
        assertEquals(3, BackupWebDavConfig(autoBackupRetentionCount = 3).effectiveAutoBackupRetentionCount)
        assertEquals(
            BackupWebDavConfig.MAX_AUTO_BACKUP_RETENTION_COUNT,
            BackupWebDavConfig(autoBackupRetentionCount = 999).effectiveAutoBackupRetentionCount,
        )
        // 预设里最大的正数必须等于收敛上限，否则设置项会与生效值对不上。
        assertEquals(
            BackupWebDavConfig.MAX_AUTO_BACKUP_RETENTION_COUNT,
            BackupWebDavConfig.AUTO_BACKUP_RETENTION_PRESETS.filter { it > 0 }.max(),
        )
        assertTrue(BackupWebDavConfig.AUTO_BACKUP_RETENTION_PRESETS.contains(0))
    }

    @Test
    fun deviceNameCannotEscapeTheBackupDirectoryAndNamesAreUnique() {
        val config = BackupWebDavConfig(deviceName = "../../手机\\test:device")
        val first = config.generateBackupFileName()
        assertFalse(first.contains('/'))
        assertFalse(first.contains('\\'))
        assertFalse(first.contains(':'))
        assertFalse(first == config.generateBackupFileName())
    }

    @Test
    fun invalidServerUrlsAreNotConsideredConfigured() {
        listOf("", "example.com", "file:///tmp", "https://host/path?query=1", "https://host/#fragment").forEach {
            assertFalse(it, BackupWebDavConfig(url = it).isConfigured)
        }
        assertTrue(BackupWebDavConfig(url = "https://example.com/dav").isConfigured)
        assertEquals("off", BackupWebDavConfig().autoBackupSyncMode)
    }

    // ==================== 自动备份间隔与触发时机 ====================

    @Test
    fun autoBackupIntervalIsClampedIntoThePresetRange() {
        // 默认间隔为一周。
        assertEquals(7L * 24 * 60 * 60 * 1000, BackupWebDavConfig().effectiveAutoBackupIntervalMs())
        // 低于下限的旧值收敛到预设最小值（15 分钟）。
        assertEquals(15L * 60 * 1000, BackupWebDavConfig(autoBackupIntervalMinutes = 1).effectiveAutoBackupIntervalMs())
        assertEquals(15L * 60 * 1000, BackupWebDavConfig(autoBackupIntervalMinutes = 5).effectiveAutoBackupIntervalMs())
        assertEquals(
            BackupWebDavConfig.AUTO_BACKUP_INTERVAL_PRESETS.last() * 60L * 1000,
            BackupWebDavConfig(autoBackupIntervalMinutes = Int.MAX_VALUE).effectiveAutoBackupIntervalMs(),
        )
        assertEquals(3L * 60 * 60 * 1000, BackupWebDavConfig(autoBackupIntervalMinutes = 180).effectiveAutoBackupIntervalMs())
        assertEquals(3L * 24 * 60 * 60 * 1000, BackupWebDavConfig(autoBackupIntervalMinutes = 3 * 24 * 60).effectiveAutoBackupIntervalMs())
        assertEquals(5L * 24 * 60 * 60 * 1000, BackupWebDavConfig(autoBackupIntervalMinutes = 5 * 24 * 60).effectiveAutoBackupIntervalMs())
        assertEquals(7L * 24 * 60 * 60 * 1000, BackupWebDavConfig(autoBackupIntervalMinutes = 7 * 24 * 60).effectiveAutoBackupIntervalMs())
    }

    @Test
    fun autoBackupIntervalPresetsAreSortedAndMatchTheClampBounds() {
        val presets = BackupWebDavConfig.AUTO_BACKUP_INTERVAL_PRESETS
        // 预设必须严格递增，且首项与收敛下限一致，否则设置项会与生效值对不上。
        assertEquals(presets.sorted(), presets)
        assertEquals(presets.distinct(), presets)
        assertEquals(BackupWebDavConfig.MIN_AUTO_BACKUP_INTERVAL_MINUTES, presets.first())
        assertEquals(BackupWebDavConfig.DEFAULT_AUTO_BACKUP_INTERVAL_MINUTES, presets.last())
        assertFalse("5 分钟已从预设中移除", presets.contains(5))
    }

    @Test
    fun autoBackupTriggerSelectsTheCallSite() {
        assertTrue(BackupWebDavConfig.shouldRunOnTrigger("on_stop", onLaunch = false))
        assertFalse(BackupWebDavConfig.shouldRunOnTrigger("on_stop", onLaunch = true))
        assertTrue(BackupWebDavConfig.shouldRunOnTrigger("on_launch", onLaunch = true))
        assertFalse(BackupWebDavConfig.shouldRunOnTrigger("on_launch", onLaunch = false))
        assertTrue(BackupWebDavConfig.shouldRunOnTrigger("on_both", onLaunch = true))
        assertTrue(BackupWebDavConfig.shouldRunOnTrigger("on_both", onLaunch = false))
        // 旧版本或未知取值只按退到后台执行，避免打开应用就上传。
        assertTrue(BackupWebDavConfig.shouldRunOnTrigger("", onLaunch = false))
        assertFalse(BackupWebDavConfig.shouldRunOnTrigger("", onLaunch = true))
    }

    @Test
    fun localAndRemoteAutoBackupCanBeEnabledTogether() {
        val off = BackupWebDavConfig()
        assertFalse(off.autoBackupEnabled)
        assertEquals("off", off.autoBackupSyncMode)

        val localOnly = off.withLocalAutoBackup(true)
        assertEquals("local", localOnly.autoBackupSyncMode)
        assertTrue(localOnly.autoBackupToLocal)
        assertFalse(localOnly.autoBackupToRemote)

        // 在本地已开启的基础上再开 WebDAV，两者应同时生效。
        val both = localOnly.withRemoteAutoBackup(true)
        assertEquals("both", both.autoBackupSyncMode)
        assertTrue(both.autoBackupToLocal)
        assertTrue(both.autoBackupToRemote)
        assertTrue(both.autoBackupEnabled)

        // 关掉其中一个只影响自己，不应把另一个一起关掉。
        val remoteOnly = both.withLocalAutoBackup(false)
        assertEquals("remote", remoteOnly.autoBackupSyncMode)
        assertFalse(remoteOnly.autoBackupToLocal)
        assertTrue(remoteOnly.autoBackupToRemote)

        assertEquals("off", remoteOnly.withRemoteAutoBackup(false).autoBackupSyncMode)

        // 旧版本写入的单一目标取值仍按对应目标解析，无需数据迁移。
        assertTrue(BackupWebDavConfig(autoBackupSyncMode = "remote").autoBackupToRemote)
        assertFalse(BackupWebDavConfig(autoBackupSyncMode = "remote").autoBackupToLocal)
        assertTrue(BackupWebDavConfig(autoBackupSyncMode = "local").autoBackupToLocal)
        assertFalse(BackupWebDavConfig(autoBackupSyncMode = "local").autoBackupToRemote)
    }

    @Test
    fun legacyBackupConfigDecodesWithNewAutoBackupDefaults() {
        val legacy = """{"url":"https://example.com/dav","autoBackupSyncMode":"remote"}"""
        val decoded = Json.decodeFromString(BackupWebDavConfig.serializer(), legacy)
        // 旧配置没有间隔字段，解码后应落到新的默认值（一周）。
        assertEquals(7 * 24 * 60, decoded.autoBackupIntervalMinutes)
        assertEquals("on_stop", decoded.autoBackupTrigger)
        assertEquals(0L, decoded.lastAutoBackupAt)
        // 旧值 "remote" 仍映射到 WebDAV 目标，本地目标保持关闭。
        assertTrue(decoded.autoBackupToRemote)
        assertFalse(decoded.autoBackupToLocal)
        val roundTrip = Json.decodeFromString(
            BackupWebDavConfig.serializer(),
            Json.encodeToString(
                BackupWebDavConfig.serializer(),
                decoded.copy(autoBackupIntervalMinutes = 300, autoBackupTrigger = "on_both", lastAutoBackupAt = 1_700_000_000_000L),
            ),
        )
        assertEquals(300, roundTrip.autoBackupIntervalMinutes)
        assertEquals("on_both", roundTrip.autoBackupTrigger)
        assertEquals(1_700_000_000_000L, roundTrip.lastAutoBackupAt)
    }

    // ==================== 归档加密（凭证不再以裸 ZIP 落盘） ====================

    @Test
    fun plainArchiveIncludesCredentials() {
        val secret = "S3cret-123pan-Password"
        val archive = BackupArchive(
            data.copy(pan123Config = Pan123BackupConfig(passport = "13800000000", password = secret, token = "tok")),
        )
        // 普通 ZIP 包含账号配置，保持导出数据完整。
        val visible = ZipInputStream(ByteArrayInputStream(BackupArchiveCodec.encode(archive))).use { zip ->
            assertEquals("backup.json", zip.nextEntry.name)
            zip.readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(visible.contains(secret))
    }

    @Test
    fun plainZipAndJsonCanBeRestored() {
        // 支持普通 ZIP 与 JSON 两种非加密格式。
        val zipBytes = BackupArchiveCodec.encode(BackupArchive(data))
        assertEquals(data, BackupArchiveCodec.decode(ByteArrayInputStream(zipBytes)).data)
        val jsonBytes = Json.encodeToString(BackupData.serializer(), data).toByteArray()
        assertEquals(data, BackupArchiveCodec.decode(ByteArrayInputStream(jsonBytes)).data)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
