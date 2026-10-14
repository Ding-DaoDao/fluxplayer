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
