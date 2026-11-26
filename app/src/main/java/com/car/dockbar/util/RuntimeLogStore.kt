package com.car.dockbar.util

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** 运行日志的落盘与留存；写入、取快照和释放快照均由 Logx 串行化。 */
internal class RuntimeLogStore(logDir: File) : Closeable {
    enum class Scope { ALL, PREVIOUS, CURRENT }

    private class Part(val file: File, val session: Long, var bytes: Long) {
        var readers = 0
        var retained = true
    }

    class Slice(val file: File, val bytes: Long) {
        fun open(offset: Long = 0): InputStream {
            val input = FileInputStream(file)
            try {
                if (offset != 0L) input.channel.position(offset)
                return BoundedInput(input, bytes - offset)
            } catch (error: IOException) {
                input.close()
                throw error
            }
        }
    }

    class Snapshot(val slices: List<Slice>, val hasEvicted: Boolean, private val release: () -> Unit) : Closeable {
        fun readHead(maxChars: Int): String {
            val chars = CharArray(minOf(maxChars.toLong(), slices.sumOf { it.bytes }).toInt())
            var length = 0
            for (slice in slices) {
                if (length == chars.size) break
                slice.open().reader(Charsets.UTF_8).use { reader ->
                    while (length < chars.size) {
                        val count = reader.read(chars, length, chars.size - length)
                        if (count < 0) break
                        length += count
                    }
                }
            }
            return String(chars, 0, length)
        }

        fun readTail(maxChars: Int): String {
            val chunks = ArrayDeque<String>()
            var remaining = maxChars
            for (index in slices.indices.reversed()) {
                if (remaining == 0) break
                val slice = slices[index]
                val offset = (slice.bytes - remaining * 4L).coerceAtLeast(0)
                val text = slice.open(offset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val tail = text.takeLast(remaining)
                chunks.addFirst(tail)
                remaining -= tail.length
            }
            return chunks.joinToString("")
        }

        override fun close() = release()
    }

    private val directory = File(logDir, "runtime")
    private val evictionMarker = File(directory, ".evicted")
    private val parts = ArrayDeque<Part>()
    private var retainedBytes = 0L
    private var hasEvicted = evictionMarker.isFile
    private val previousSession: Long
    private val session: Long
    private var sequence = 0
    private var active: Part
    private var output: FileOutputStream
    private var writeFailed = false

    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建运行日志目录")
        val saved = directory.listFiles() ?: throw IOException("无法读取运行日志目录")
        var latest = 0L
        val emptyParts = mutableListOf<File>()
        for (file in saved.sortedBy { it.name }) {
            val match = partName.matchEntire(file.name) ?: continue
            if (!file.isFile) continue
            val id = match.groupValues[1].toLong()
            latest = id
            val bytes = file.length()
            if (bytes == 0L) emptyParts += file else retain(Part(file, id, bytes))
        }
        previousSession = latest
        session = latest + 1
        evictOverBudget()
        active = Part(partFile(session, sequence), session, 0)
        output = FileOutputStream(active.file, true)
        retain(active)
        // 新会话文件建立后再清理空片，保留最近一次无日志启动的身份。
        emptyParts.forEach { it.delete() }
    }

    /** 一条记录只调用一次 write；超长记录显式截断在 UTF-8 字符边界。 */
    fun append(line: String) {
        val bytes = "$line\n".toByteArray(Charsets.UTF_8)
        var size = bytes.size
        if (size > PART_BYTES) {
            var end = PART_BYTES - oversizedSuffix.size
            while ((bytes[end].toInt() and 0xc0) == 0x80) end--
            oversizedSuffix.copyInto(bytes, end)
            size = end + oversizedSuffix.size
        }
        if (retainedBytes > BUDGET_BYTES) evictOverBudget()
        if (writeFailed || active.bytes + size > PART_BYTES) rotate()
        try {
            output.write(bytes, 0, size)
            active.bytes += size
            retainedBytes += size
        } catch (error: IOException) {
            val actual = active.file.length()
            writeFailed = actual > active.bytes
            retainedBytes += actual - active.bytes
            active.bytes = actual
            throw error
        }
        evictOverBudget()
    }

    /** 快照固定每片的读取边界；正在导出的被淘汰分片等读者结束再删除。 */
    fun capture(scope: Scope): Snapshot {
        val selected = parts.filter { part ->
            part.bytes > 0 && when (scope) {
                Scope.ALL -> true
                Scope.PREVIOUS -> part.session == previousSession
                Scope.CURRENT -> part.session == session
            }
        }
        val slices = selected.map { Slice(it.file, it.bytes) }
        selected.forEach { it.readers++ }
        return Snapshot(slices, hasEvicted) {
            for (part in selected) {
                part.readers--
                if (!part.retained && part.readers == 0) part.file.delete()
            }
        }
    }

    override fun close() = output.close()

    private fun rotate() {
        val next = Part(partFile(session, sequence + 1), session, 0)
        val nextOutput = FileOutputStream(next.file, true)
        val previous = output
        output = nextOutput
        active = next
        sequence++
        writeFailed = false
        retain(next)
        previous.close()
    }

    private fun partFile(id: Long, index: Int): File = File(
        directory, "${id.toString().padStart(12, '0')}-${index.toString().padStart(6, '0')}.log"
    )

    private fun retain(part: Part) {
        parts.addLast(part)
        retainedBytes += part.bytes
    }

    private fun evictOverBudget() {
        while (retainedBytes > BUDGET_BYTES) {
            val oldest = parts.first()
            if (!hasEvicted) {
                evictionMarker.createNewFile()
                hasEvicted = true
            }
            if (oldest.readers == 0) Files.deleteIfExists(oldest.file.toPath())
            oldest.retained = false
            parts.removeFirst()
            retainedBytes -= oldest.bytes
        }
    }

    private class BoundedInput(private val input: FileInputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = input.read()
            if (value < 0) throw IOException("运行日志分片提前结束")
            remaining--
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val count = input.read(bytes, offset, minOf(remaining, length.toLong()).toInt())
            if (count < 0) throw IOException("运行日志分片提前结束")
            remaining -= count
            return count
        }

        override fun close() = input.close()
    }

    private companion object {
        const val PART_BYTES = 1024 * 1024
        const val BUDGET_BYTES = 8L * PART_BYTES
        val partName = Regex("([0-9]{12})-[0-9]{6}\\.log")
        val oversizedSuffix = " …（单条日志超过分片上限，已截断）\n".toByteArray(Charsets.UTF_8)
    }
}
