package dev.anilbeesetti.nextplayer.core.data.openlist

import android.content.Context
import android.os.Build
import java.io.File

/**
 * OpenList 二进制提取器 —— 查找 libopenlist.so 是否存在且可执行。
 *
 * 搜索顺序：
 * 1. context.filesDir/libopenlist.so（内部存储）
 * 2. context.filesDir/openlist-binary/libopenlist.so（内部存储子目录）
 * 3. context.applicationInfo.nativeLibraryDir/libopenlist.so（jniLibs）
 */
class OpenListBinaryExtractor(private val context: Context) {

    /**
     * 查找并验证 OpenList 二进制文件。
     * @return 二进制文件绝对路径，或失败原因
     */
    fun ensureBinaryExtracted(): Result<String> {
        // 候选路径列表
        val candidates = listOf(
            File(context.filesDir, "libopenlist.so"),
            File(context.filesDir, "openlist-binary/libopenlist.so"),
            File(context.applicationInfo.nativeLibraryDir, "libopenlist.so"),
        )

        val binaryPath = candidates.firstOrNull { it.exists() }
            ?: return Result.failure(IllegalStateException(
                "找不到 libopenlist.so，请在项目根目录创建 openlist-binary/ 并将二进制文件放入。\n" +
                "搜索路径:\n${candidates.joinToString("\n") { "  - ${it.absolutePath}" }}"
            ))

        if (!binaryPath.canExecute() && !binaryPath.setExecutable(true)) {
            return Result.failure(
                IllegalStateException("无法设置 libopenlist.so 为可执行: ${binaryPath.absolutePath}")
            )
        }

        return Result.success(binaryPath.absolutePath)
    }
}
