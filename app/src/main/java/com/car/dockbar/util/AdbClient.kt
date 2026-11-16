package com.car.dockbar.util

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.UUID

/**
 * 连接本机 adbd，通过传统 shell 服务执行单条命令，不实现 RSA/TLS 认证或 root 提权。
 * 能否免交互连接取决于设备配置；ADB shell 不等于 root，也不能用普通 pm grant
 * 授予标准 signature|privileged 车控权限。
 *
 * 使用旧协议 0x01000000：24 字节小端消息头中的 data_check 是 payload 无符号字节
 * 的 32 位累加和，不是 CRC32。只有收到打开确认、完整退出标记和正常关闭才报告命令完成。
 */
object AdbClient {

    private const val A_CNXN = 0x4e584e43
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43

    private const val A_AUTH = 0x48545541
    private const val VERSION = 0x01000000
    private const val MAXDATA = 4096
    private const val MAX_OUTPUT_BYTES = 1024 * 1024

    data class Result(
        val ok: Boolean,
        val output: String,
        val error: String = "",
        val exitCode: Int? = null
    ) {
        val text: String get() = output.ifBlank { error.ifBlank { "(无输出)" } }
    }

    /** timeoutMs 约束建连、发送和每一段接收的总耗时；失败保留已接收的输出。 */
    fun exec(port: Int, cmd: String, timeoutMs: Int = 10_000): Result {
        val output = ByteArrayOutputStream()
        return try {
            require(timeoutMs > 0) { "执行期限必须大于零" }
            require('\u0000' !in cmd) { "命令不能包含 NUL" }
            val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000L
            val marker = "__BANANA_ADB_EXIT_${UUID.randomUUID()}__="
            // 原命令在子 shell 中执行，exit/exec 不会跳过外层退出码标记。
            val wrapped = "sh -c ${shellQuote(cmd)}; printf '\\n$marker%d\\n' \"\$?\""
            val service = "shell:$wrapped\u0000"
            val serviceBytes = service.toByteArray(Charsets.UTF_8)
            require(serviceBytes.size <= MAXDATA) { "命令超过 ADB 单包上限" }

            SocketChannel.open().use { channel ->
                Selector.open().use { selector ->
                    val connection = Connection(channel, selector, deadline)
                    connection.connect(port)
                    execute(connection, serviceBytes, marker, output)
                }
            }
        } catch (e: Exception) {
            Result(false, output.toString(Charsets.UTF_8.name()), "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun execute(
        connection: Connection,
        serviceBytes: ByteArray,
        marker: String,
        output: ByteArrayOutputStream
    ): Result {
        connection.send(A_CNXN, VERSION, MAXDATA, "host::\u0000".toByteArray(Charsets.UTF_8))
        val hello = connection.read()
        if (hello.cmd == A_AUTH) return Result(false, "", "adbd 要求 RSA 认证，本机无已授权 key")
        if (hello.cmd != A_CNXN || hello.arg0 < VERSION || hello.arg1 <= 0) {
            return Result(false, "", "ADB 握手响应无效")
        }
        require(serviceBytes.size <= hello.arg1) { "命令超过对端 ADB 单包上限" }

        val localId = 1
        connection.send(A_OPEN, localId, 0, serviceBytes)
        var peerId = 0
        while (true) {
            val message = connection.read()
            if (message.arg1 != localId) throw IOException("ADB 本地流 ID 不匹配")
            when (message.cmd) {
                A_OKAY -> {
                    if (peerId != 0 || message.arg0 == 0 || message.data.isNotEmpty()) {
                        throw IOException("ADB 打开确认无效或重复")
                    }
                    peerId = message.arg0
                }
                A_WRTE -> {
                    if (peerId == 0 || message.arg0 != peerId) {
                        throw IOException("ADB 输出早于打开确认或远端流 ID 不匹配")
                    }
                    val available = MAX_OUTPUT_BYTES - output.size()
                    output.write(message.data, 0, minOf(available, message.data.size))
                    if (message.data.size > available) throw IOException("命令输出超过 1 MiB 上限")
                    connection.send(A_OKAY, localId, peerId)
                }
                A_CLSE -> {
                    if (message.data.isNotEmpty()) throw IOException("ADB 关闭消息包含异常数据")
                    if (peerId == 0) return Result(false, "", "adbd 拒绝打开 shell")
                    if (message.arg0 != peerId) throw IOException("ADB 关闭消息流 ID 不匹配")
                    connection.send(A_CLSE, localId, peerId)
                    return completedResult(output.toString(Charsets.UTF_8.name()), marker)
                }
                else -> throw IOException("ADB 流消息无效：0x${Integer.toHexString(message.cmd)}")
            }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun completedResult(output: String, marker: String): Result {
        val prefix = "\n$marker"
        val start = output.lastIndexOf(prefix)
        if (start >= 0 && output.endsWith('\n')) {
            val status = output.substring(start + prefix.length, output.length - 1)
            val code = status.takeIf { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } }
                ?.toIntOrNull()
            if (code != null && code in 0..255) {
                return Result(code == 0, output.substring(0, start), if (code == 0) "" else "命令退出码：$code", code)
            }
        }
        return Result(false, output, "shell 已关闭，但未收到完整退出码标记")
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (byte in data) sum += byte.toInt() and 0xff
        return sum
    }

    /** 非阻塞通道让发送和分段读取共用同一个单调时钟期限，无额外看门狗线程。 */
    private class Connection(
        private val channel: SocketChannel,
        private val selector: Selector,
        private val deadline: Long
    ) {
        private val key: SelectionKey
        private val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)

        init {
            channel.configureBlocking(false)
            key = channel.register(selector, 0)
        }

        fun connect(port: Int) {
            remainingMillis()
            if (!channel.connect(InetSocketAddress("127.0.0.1", port))) {
                while (!channel.finishConnect()) await(SelectionKey.OP_CONNECT)
            }
        }

        fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = EMPTY) {
            header.clear()
            header.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size)
                .putInt(checksum(data)).putInt(cmd xor -1)
            header.flip()
            writeFully(header)
            if (data.isNotEmpty()) writeFully(ByteBuffer.wrap(data))
        }

        private fun writeFully(buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                remainingMillis()
                if (channel.write(buffer) == 0) await(SelectionKey.OP_WRITE)
            }
        }

        fun read(): Msg {
            header.clear()
            readFully(header)
            header.flip()
            val cmd = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val check = header.int
            val magic = header.int
            if (magic != (cmd xor -1)) throw IOException("ADB 消息 magic 无效")
            if (length !in 0..MAXDATA) throw IOException("ADB payload 长度无效：$length")
            val data = if (length == 0) EMPTY else ByteArray(length)
            if (length != 0) readFully(ByteBuffer.wrap(data))
            if (check != checksum(data)) throw IOException("ADB payload 校验和不匹配")
            return Msg(cmd, arg0, arg1, data)
        }

        private fun readFully(buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                remainingMillis()
                when (channel.read(buffer)) {
                    -1 -> throw EOFException("ADB 在完整关闭前断开")
                    0 -> await(SelectionKey.OP_READ)
                }
            }
        }

        private fun await(operation: Int) {
            key.interestOps(operation)
            selector.select(remainingMillis())
            selector.selectedKeys().clear()
            remainingMillis()
        }

        private fun remainingMillis(): Long {
            val nanos = deadline - System.nanoTime()
            if (nanos <= 0) throw SocketTimeoutException("ADB 整体执行期限已到")
            return (nanos + 999_999L) / 1_000_000L
        }
    }

    private val EMPTY = ByteArray(0)
}
