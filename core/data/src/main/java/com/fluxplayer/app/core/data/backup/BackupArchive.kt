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
    val coverFiles: Map<String, ByteArray> = emptyMap(),
    val coverWarnings: List<String> = emptyList(),
)

data class BackupExportResult(
    val fileName: String = "",
    val coverWarnings: List<String> = emptyList(),
) {
    fun successMessage(message: String): String =
        if (coverWarnings.isEmpty()) message else "$message；${coverWarnings.size} 张封面未能备份"
}

internal object BackupArchiveCodec {
    private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 2048
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(archive: BackupArchive): ByteArray {
        validate(archive.data)
        require(archive.openListFiles.size + archive.coverFiles.size < MAX_ENTRIES) { "备份文件条目过多" }
        val configBytes = json.encodeToString(BackupData.serializer(), archive.data).toByteArray(Charsets.UTF_8)
        require(configBytes.size <= MAX_JSON_BYTES) { "备份配置过大" }
        require(configBytes.size.toLong() + archive.openListFiles.values.sumOf { it.size.toLong() } + archive.coverFiles.values.sumOf { it.size.toLong() } <= MAX_ARCHIVE_BYTES) { "备份文件过大" }
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
                archive.coverFiles.forEach { (name, bytes) ->
                    com.fluxplayer.app.core.tingshu.ListeningCoverStore.validateName(name)
                    zip.putNextEntry(ZipEntry("listening_covers/$name"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    /** 仅解析普通 ZIP 或 JSON 备份，不读取旧加密格式。 */
    fun decode(input: InputStream): BackupArchive = decodePlain(readLimited(input, MAX_ARCHIVE_BYTES))

    private fun decodePlain(bytes: ByteArray): BackupArchive {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            require(bytes.toString(Charsets.UTF_8).trimStart().startsWith("{")) { "不支持该备份格式，请选择未加密的 ZIP 或 JSON 备份" }
            return BackupArchive(decodeJson(bytes))
        }
        var data: BackupData? = null
        val files = linkedMapOf<String, ByteArray>()
        val covers = linkedMapOf<String, ByteArray>()
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
                    !entry.isDirectory && entry.name.startsWith("listening_covers/") -> {
                        val name = entry.name.removePrefix("listening_covers/")
                        com.fluxplayer.app.core.tingshu.ListeningCoverStore.validateName(name)
                        require(name !in covers) { "备份包含重复的封面" }
                        covers[name] = content
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
        return BackupArchive(requireNotNull(data) { "备份文件中没有 backup.json" }, files, covers)
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
