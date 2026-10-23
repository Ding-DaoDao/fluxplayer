package com.fluxplayer.app.feature.player.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 只为明确标记的原画文件预读，最多保留三段；拖动或关闭立即释放旧请求。 */
internal class ParallelRangeDataSource(private val upstream: DataSource.Factory) : DataSource {
    private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<DataSource>()
    private var direct: DataSource? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    private val pending = ArrayDeque<Future<ByteArray>>()

    @Volatile private var closed = false
    private var spec: DataSpec? = null
    private var responseUri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var validator: Pair<String, String>? = null
    private var total = 0L
    private var end = 0L
    private var position = 0L
    private var next = 0L
    private var firstRemaining = 0L
    private var bytes: ByteArray? = null
    private var offset = 0
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        listeners.add(transferListener)
    }

    private fun source(): DataSource = upstream.createDataSource().also { source ->
        listeners.forEach(source::addTransferListener)
        active.add(source)
        if (closed) {
            active.remove(source)
            throw InterruptedIOException("原画读取已取消")
        }
    }

    private fun release(source: DataSource) {
        if (active.remove(source)) source.close()
    }

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        spec = dataSpec
        position = dataSpec.position
        val eligible = dataSpec.uri.fragment.orEmpty().contains("quarkOriginal=true") &&
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null &&
            dataSpec.length != 0L
        if (!eligible) return openDirect(dataSpec)
        // 首段既是能力验证，也是实际播放数据，不额外增加一次探测请求。
        val count = if (dataSpec.length == C.LENGTH_UNSET.toLong()) WARMUP else minOf(WARMUP, dataSpec.length)
        val first = source()
        direct = first
        try {
            first.open(dataSpec.buildUpon().setLength(count).setFlags(dataSpec.flags and DataSpec.FLAG_ALLOW_GZIP.inv()).build())
            responseUri = first.uri
            headers = first.responseHeaders
            val range = parseRange(header(headers, "Content-Range"))
            validator = header(headers, "ETag")?.takeUnless { it.startsWith("W/") }?.let { "ETag" to it }
                ?: header(headers, "Last-Modified")?.let { "Last-Modified" to it }
            val encoding = header(headers, "Content-Encoding")
            val type = header(headers, "Content-Type").orEmpty()
            if (range == null || range.first != position || range.second != minOf(position + count, range.third) - 1 ||
                validator == null || (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) ||
                type.contains("mpegurl", true) || type.contains("dash+xml", true)
            ) {
                release(first)
                direct = null
                return openDirect(dataSpec)
            }
            total = range.third
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total else minOf(total, position + dataSpec.length)
            firstRemaining = range.second - range.first + 1
            next = position + firstRemaining
            executor = Executors.newFixedThreadPool(CONNECTIONS) { runnable ->
                Thread(runnable, "QuarkRange").apply { isDaemon = true }
            }
            android.util.Log.i("QuarkRange", "原画分段读取已启用：首段256KiB，预读3段，每段1MiB")
            fillQueue()
            return end - position
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun openDirect(dataSpec: DataSpec): Long {
        val source = source()
        direct = source
        return source.open(dataSpec).also {
            responseUri = source.uri
            headers = source.responseHeaders
        }
    }

    private fun fillQueue() {
        while (!closed && pending.size < CONNECTIONS && next < end) {
            val start = next
            val size = minOf(CHUNK, end - start).toInt()
            next += size
            val request = spec!!
            val identity = validator!!
            val expectedTotal = total
            val session = generation.get()
            pending.add(executor!!.submit(Callable { fetch(start, size, request, identity, expectedTotal, session) }))
        }
    }

    private fun fetch(start: Long, size: Int, original: DataSpec, identity: Pair<String, String>, expectedTotal: Long, session: Int): ByteArray {
        if (session != generation.get()) throw InterruptedIOException("原画读取已取消")
        val source = source()
        try {
            source.open(
                original.buildUpon().setPosition(start).setLength(size.toLong())
                    .setFlags(original.flags and DataSpec.FLAG_ALLOW_GZIP.inv())
                    .setHttpRequestHeaders(original.httpRequestHeaders + mapOf("If-Range" to identity.second, "Accept-Encoding" to "identity"))
                    .build(),
            )
            val response = source.responseHeaders
            val range = parseRange(header(response, "Content-Range"))
            if (range != Triple(start, start + size - 1, expectedTotal) ||
                header(response, identity.first) != identity.second ||
                header(response, "Content-Encoding")?.let { !it.equals("identity", true) } == true
            ) {
                throw IOException("原画分段响应或文件标识发生变化")
            }
            val result = ByteArray(size)
            var received = 0
            while (received < size) {
                if (closed || session != generation.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("原画读取已取消")
                val read = source.read(result, received, size - received)
                if (read == C.RESULT_END_OF_INPUT) throw EOFException("原画分段未完整返回")
                received += read
            }
            return result
        } finally {
            release(source)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed) throw InterruptedIOException("原画读取已取消")
        if (executor == null) return direct!!.read(buffer, offset, length)
        if (position >= end) return C.RESULT_END_OF_INPUT
        if (firstRemaining > 0) {
            val read = direct!!.read(buffer, offset, minOf(length.toLong(), firstRemaining).toInt())
            if (read == C.RESULT_END_OF_INPUT) throw EOFException("原画首段未完整返回")
            firstRemaining -= read
            position += read
            if (firstRemaining == 0L) {
                release(direct!!)
                direct = null
            }
            return read
        }
        if (bytes == null) {
            try {
                bytes = pending.removeFirst().get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("原画读取已取消")
            } catch (error: java.util.concurrent.CancellationException) {
                throw InterruptedIOException("原画读取已取消")
            } catch (error: ExecutionException) {
                // 失败后从尚未交付给播放器的位置串行续读，避免坏分段和重复字节。
                stopWorkers()
                executor = null
                if (closed) throw InterruptedIOException("原画读取已取消")
                android.util.Log.w("QuarkRange", "分段读取失败，从未交付位置回退到单连接")
                openDirect(spec!!.buildUpon().setPosition(position).setLength(end - position).build())
                if (header(headers, validator!!.first) != validator!!.second) {
                    close()
                    throw IOException("原画文件已变化，请重新播放")
                }
                return direct!!.read(buffer, offset, length)
            }
            this.offset = 0
        }
        val current = bytes!!
        val count = minOf(length, current.size - this.offset)
        current.copyInto(buffer, offset, this.offset, this.offset + count)
        this.offset += count
        position += count
        if (this.offset == current.size) {
            bytes = null
            fillQueue()
        }
        return count
    }

    private fun stopWorkers() {
        pending.forEach { it.cancel(true) }
        pending.clear()
        executor?.shutdownNow()
        active.toList().forEach { runCatching { release(it) } }
    }

    override fun close() {
        closed = true
        generation.incrementAndGet()
        stopWorkers()
        executor = null
        direct = null
        bytes = null
        firstRemaining = 0
    }

    override fun getUri(): Uri? = responseUri
    override fun getResponseHeaders(): Map<String, List<String>> = headers

    companion object {
        private const val CONNECTIONS = 3
        private const val WARMUP = 256L * 1024
        private const val CHUNK = 1024L * 1024
        private fun header(headers: Map<String, List<String>>, name: String) =
            headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()

        private fun parseRange(value: String?): Triple<Long, Long, Long>? {
            val parts = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value ?: return null)?.groupValues ?: return null
            val start = parts[1].toLongOrNull() ?: return null
            val end = parts[2].toLongOrNull() ?: return null
            val total = parts[3].toLongOrNull() ?: return null
            return if (start <= end && end < total) Triple(start, end, total) else null
        }
    }
}
