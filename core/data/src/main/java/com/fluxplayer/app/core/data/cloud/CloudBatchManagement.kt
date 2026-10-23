package com.fluxplayer.app.core.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

data class CloudBatchResult(val completedIds: Set<String>, val message: String? = null)

/** 使用重新读取的原始条目提交，完成后核对目录；失败批次不自动重发。 */
class CloudBatchManager(
    private val root: String,
    private val session: () -> String,
    private val read: suspend (String) -> List<CloudRemoteFile>,
    private val submit: suspend (List<CloudRemoteFile>, String?) -> Unit,
    private val waitForListing: suspend () -> Unit = { delay(750) },
) {
    suspend fun execute(
        source: String,
        selected: List<CloudRemoteFile>,
        target: String? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): CloudBatchResult {
        require(selected.isNotEmpty()) { "请先选择文件" }
        require(selected.map { it.id }.distinct().size == selected.size) { "所选文件重复，请重新选择" }
        require(selected.all { it.id.isNotBlank() && it.id != root && it.parentId == source }) { "所选文件不属于当前目录，请刷新后重选" }
        require(target == null || target.isNotBlank() && target != source && selected.none { it.directory && it.id == target }) { "不能移动到原目录或所选文件夹自身" }
        val owner = session()
        fun checkOwner() = check(session() == owner) { "账号或网盘空间已变化，请重新打开目录" }
        val completed = mutableSetOf<String>()
        try {
            checkOwner()
            val current = read(source).associateBy { it.id }
            checkOwner()
            val fresh = selected.map { chosen ->
                val item = current[chosen.id]
                check(item != null && item.name == chosen.name && item.directory == chosen.directory && item.size == chosen.size) { "所选文件已变化，请刷新后重选" }
                item
            }
            for (chunk in fresh.chunked(50)) {
                checkOwner()
                cloudMutation { submit(chunk, target) }
                checkOwner()
                var confirmed = false
                for (attempt in 0 until 8) {
                    val remaining = read(source).map { it.id }.toSet()
                    val destination = if (target == null) emptySet() else read(target).map { it.id }.toSet()
                    checkOwner()
                    val verified = chunk.filter { it.id !in remaining && (target == null || it.id in destination) }.map { it.id }
                    completed.addAll(verified)
                    onProgress(completed.size, selected.size)
                    confirmed = chunk.all { it.id in completed }
                    if (confirmed) break
                    if (attempt < 7) waitForListing()
                }
                check(confirmed) { "部分文件尚未确认完成，已停止后续批次，请刷新目录确认" }
            }
            return CloudBatchResult(completed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return CloudBatchResult(completed, error.message ?: "操作未完成，请刷新目录确认")
        }
    }
}

suspend fun CloudProviderFeatures.manageBatch(
    source: String,
    selected: List<CloudRemoteFile>,
    target: String? = null,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): CloudBatchResult = CloudBatchManager(rootId, ::searchSessionKey, ::listPersonalFiles, manageFiles)
    .execute(source, selected, target, onProgress)
