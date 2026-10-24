package com.fluxplayer.app.core.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordProtectedBackupTest {
    private val password get() = "fixture-password".toCharArray()

    @Test fun roundTripEncryptsWholeArchiveWithFreshSalt() {
        val source = "fixture-token-and-file-data".toByteArray()
        val first = PasswordProtectedBackup.encrypt(source, password)
        val second = PasswordProtectedBackup.encrypt(source, password)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains("fixture-token"))
        assertArrayEquals(source, PasswordProtectedBackup.decrypt(first, password))
    }

    @Test fun passwordRequiredAndWrongPasswordAreDistinctAndNeverReturnPlaintext() {
        val encrypted = PasswordProtectedBackup.encrypt("fixture".toByteArray(), password)
        assertTrue(runCatching { PasswordProtectedBackup.decrypt(encrypted, null) }.exceptionOrNull() is BackupPasswordRequired)
        assertTrue(runCatching { PasswordProtectedBackup.decrypt(encrypted, "wrong-password".toCharArray()) }.exceptionOrNull() is BackupPasswordInvalid)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { PasswordProtectedBackup.decrypt(encrypted, password) }.exceptionOrNull() is BackupPasswordInvalid)
    }

    @Test fun legacyZipAndJsonRemainReadableWithoutPassword() {
        listOf("PK\u0003\u0004fixture", "{\"version\":1}").forEach {
            assertArrayEquals(it.toByteArray(), PasswordProtectedBackup.decrypt(it.toByteArray(), null))
        }
    }

    @Test fun rejectsShortPasswords() {
        assertTrue(runCatching { PasswordProtectedBackup.encrypt(byteArrayOf(1), "short".toCharArray()) }.isFailure)
    }

    @Test fun fullArchivePreservesCredentialsSettingsAndBinaryFilesAfterDecryption() {
        val data = com.fluxplayer.app.core.model.BackupData(
            appPreferences = com.fluxplayer.app.core.model.ApplicationPreferences(),
            playerPreferences = com.fluxplayer.app.core.model.PlayerPreferences(),
            webDavServers = com.fluxplayer.app.core.model.WebDavServers(),
            pan123Config = com.fluxplayer.app.core.model.Pan123BackupConfig(token = "fixture-secret"),
        )
        val binary = byteArrayOf(0, -1, 3, 42)
        val zip = BackupArchiveCodec.encode(BackupArchive(data, mapOf("data.db" to binary)))
        val encrypted = PasswordProtectedBackup.encrypt(zip, password)
        val restored = BackupArchiveCodec.decode(java.io.ByteArrayInputStream(PasswordProtectedBackup.decrypt(encrypted, password)))
        org.junit.Assert.assertEquals(data, restored.data)
        assertArrayEquals(binary, restored.openListFiles.getValue("data.db"))
        val legacy = BackupArchiveCodec.decode(java.io.ByteArrayInputStream(PasswordProtectedBackup.decrypt(zip, null)))
        org.junit.Assert.assertEquals(data, legacy.data)
    }
}
