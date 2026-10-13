package com.fluxplayer.app.core.data.backup

import com.fluxplayer.app.core.model.BackupData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** 只解析到内存，用户确认恢复之前不写入任何配置或文件。 */
data class BackupArchive(
    val data: BackupData,
    val openListFiles: Map<String, ByteArray> = emptyMap(),
)

internal object BackupArchiveCodec {
    private const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 256
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(archive: BackupArchive): ByteArray {
        validate(archive.data)
        require(archive.openListFiles.size < MAX_ENTRIES) { "备份文件条目过多" }
        val configBytes = json.encodeToString(BackupData.serializer(), archive.data).toByteArray(Charsets.UTF_8)
        require(configBytes.size <= MAX_JSON_BYTES) { "备份配置过大" }
        require(configBytes.size.toLong() + archive.openListFiles.values.sumOf { it.size.toLong() } <= MAX_ARCHIVE_BYTES) { "备份文件过大" }
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(configBytes)
                zip.closeEntry()
                archive.openListFiles.forEach { (name, bytes) ->
                    validateFileName(name)
                    zip.putNextEntry(ZipEntry("openlist_data/$name"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    fun decode(input: InputStream): BackupArchive {
        val bytes = readLimited(input, MAX_ARCHIVE_BYTES)
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return BackupArchive(decodeJson(bytes))
        }
        var data: BackupData? = null
        val files = linkedMapOf<String, ByteArray>()
        var totalBytes = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(++count <= MAX_ENTRIES) { "备份文件条目过多" }
                val content = readLimited(zip, (MAX_ARCHIVE_BYTES - totalBytes).coerceAtLeast(0))
                totalBytes += content.size
                when {
                    entry.name == "backup.json" -> {
                        require(data == null) { "备份文件包含重复的 backup.json" }
                        data = decodeJson(content)
                    }
                    !entry.isDirectory && entry.name.startsWith("openlist_data/") -> {
                        val name = entry.name.removePrefix("openlist_data/")
                        validateFileName(name)
                        require(name !in files) { "备份文件包含重复的 OpenList 数据" }
                        files[name] = content
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return BackupArchive(requireNotNull(data) { "备份文件中没有 backup.json" }, files)
    }

    fun validate(data: BackupData) {
        require(data.version in 1..BackupData.CURRENT_VERSION) { "不支持的备份版本，请使用兼容的应用版本恢复" }
    }

    fun validateFileName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' || it == ':' }) {
            "备份包含无效的 OpenList 文件路径"
        }
    }

    private fun decodeJson(bytes: ByteArray): BackupData {
        require(bytes.size <= MAX_JSON_BYTES) { "备份配置过大" }
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return json.decodeFromString(BackupData.serializer(), text).also(::validate)
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            total += size
            require(total <= limit) { "备份文件过大" }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
}

internal fun isBackupFileName(name: String): Boolean =
    name.startsWith("fluxplayer_backup_") && name.endsWith(".zip", ignoreCase = true)

internal suspend fun <T> backupResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
