package com.car.dockbar.util

import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 轻量日志总线：
 *  - 打到 logcat
 *  - 内存保留最近 [MAX] 条，供主界面实时展示（车机上没有 adb 也能看）
 *  - 逐条同步追加到 [RuntimeLogStore]。进程退出前已交给内核的日志可供下次启动取证。
 */
object Logx {
    private const val TAG = "CarDockBar"
    private const val MAX = 400

    private val buffer = ArrayDeque<String>()
    private val listeners = mutableSetOf<(String) -> Unit>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var verbose = true

    private var store: RuntimeLogStore? = null

    @Synchronized
    internal fun startSession(directory: File): String {
        runCatching { store?.close() }
        store = null
        val opened = runCatching { RuntimeLogStore(directory) }.getOrElse {
            Log.e(TAG, "无法开启运行日志会话", it)
            return ""
        }
        store = opened
        return runCatching {
            opened.capture(RuntimeLogStore.Scope.PREVIOUS).use { snapshot ->
                val prefix = snapshot.readHead(24_001)
                if (prefix.length <= 24_000) prefix else
                    prefix.take(12_000) + "\n…（上次会话中段省略，详见本地诊断 ZIP）…\n" + snapshot.readTail(12_000)
            }
        }.getOrElse {
            Log.e(TAG, "无法读取上次运行日志", it)
            ""
        }
    }

    fun setVerbose(v: Boolean) { verbose = v }
    fun isVerbose() = verbose

    @Synchronized
    fun d(msg: String) {
        if (!verbose) { Log.d(TAG, msg); return }
        push("D", msg, null)
    }

    @Synchronized
    fun w(msg: String) {
        push("W", msg, null)
    }

    @Synchronized
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        push("E", msg, t)
    }

    private fun push(level: String, msg: String, t: Throwable?) {
        val line = "${fmt.format(Date())}  $level  $msg" +
                (t?.let { "  ⁄ ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        if (level != "E") Log.println(if (level == "W") Log.WARN else Log.DEBUG, TAG, msg)
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.removeFirst()
        try {
            store?.append(line)
        } catch (error: IOException) {
            Log.e(TAG, "运行日志写入失败", error)
        }
        val snapshot = runCatching { listeners.toList() }.getOrDefault(emptyList())
        for (l in snapshot) runCatching { l(line) }
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    /** 锁内仅固定分片及字节边界，压缩、脱敏和文档写入在锁外执行。 */
    internal fun <T> withExportSnapshot(block: (RuntimeLogStore.Snapshot?, List<String>) -> T): T {
        val (snapshot, memory) = synchronized(this) {
            store?.capture(RuntimeLogStore.Scope.ALL) to buffer.toList()
        }
        try {
            return block(snapshot, memory)
        } finally {
            synchronized(this) { snapshot?.close() }
        }
    }

    @Synchronized
    internal fun currentSessionTail(): String? = runCatching {
        store?.capture(RuntimeLogStore.Scope.CURRENT)?.use { it.readTail(24_000) }?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    @Synchronized
    fun addListener(l: (String) -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    @Synchronized
    fun clear() { buffer.clear() }
}
