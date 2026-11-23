package com.car.dockbar.util

import android.content.Context
import android.net.Uri
import android.os.Build
import com.car.dockbar.vehicle.HvacChannelEvidence
import com.car.dockbar.vehicle.HvacDiagnostics
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 生成用户主动选择位置的本地诊断 ZIP；不上传，也不读取偏好、密钥或上传队列。
 * 写入锁只固定日志范围，逐行脱敏后直接压缩到临时 ZIP。
 */
internal object DiagnosticLogExporter {
    data class Result(val success: Boolean, val message: String)

    @Synchronized
    fun export(context: Context, destination: Uri): Result {
        val appContext = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var work: File? = null
        return try {
            val archive = File.createTempFile("diagnostic-export-", ".zip", appContext.cacheDir)
            work = archive
            ZipOutputStream(BufferedOutputStream(archive.outputStream())).use { zip ->
                val missing = mutableListOf<String>()
                val coverage = Coverage()
                val hasEvicted = Logx.withExportSnapshot { snapshot, memory ->
                    if (snapshot == null) missing += "运行日志：存储不可用"
                    else if (snapshot.slices.isEmpty()) missing += "运行日志：暂无留存分片"
                    snapshot?.slices?.forEach { slice ->
                        writeEntry(zip, "logs/runtime/${slice.file.name}") { writer ->
                            slice.open().bufferedReader(Charsets.UTF_8).useLines { lines ->
                                lines.forEach { line ->
                                    coverage.record(line)
                                    writeRedactedLine(writer, line)
                                }
                            }
                        }
                    }
                    writeEntry(zip, "memory_snapshot.log") { writer ->
                        memory.forEach { record -> record.lineSequence().forEach { writeRedactedLine(writer, it) } }
                    }
                    snapshot?.hasEvicted
                }
                writeCrashSources(appContext, zip, missing)
                writeEntry(zip, "hvac/channel-evidence.txt") {
                    it.write(report("hvac/channel-evidence.txt", HvacChannelEvidence::report, missing))
                }
                writeEntry(zip, "hvac/diagnostics.txt") {
                    it.write(report("hvac/diagnostics.txt", HvacDiagnostics::report, missing))
                }
                writeEntry(zip, "metadata.txt") { it.write(buildMetadata(appContext, stamp, coverage, hasEvicted, missing)) }
            }
            val output = appContext.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("系统未提供文档写入流")
            output.use { archive.inputStream().use { input -> input.copyTo(it) } }
            Result(true, "导出完成")
        } catch (t: Throwable) {
            Logx.e("诊断日志导出失败", t)
            Result(false, "导出失败：${t.javaClass.simpleName}；未生成完整日志包，目标文件可能不完整")
        } finally {
            work?.delete()
        }
    }

    private fun buildMetadata(
        context: Context, stamp: String, coverage: Coverage, hasEvicted: Boolean?, missing: List<String>
    ): String = buildString {
        appendLine("Banana_Dock 诊断与运行日志导出")
        appendLine("生成时间：$stamp")
        appendLine("包名：${context.packageName}")
        appendLine("版本：${appVersion(context)}")
        appendLine("Android SDK：${Build.VERSION.SDK_INT}")
        appendLine("设备型号：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("说明：选择保存位置不会自动分享或上传；ZIP 仅由用户主动保存。")
        appendLine("隐私提示：日志可能包含应用名/包名、媒体信息及设备诊断信息，请仅交给可信分析者。")
        appendLine("脱敏：导出副本移除 HTTP(S) URL 用户信息、查询参数、片段以及常见认证字段；原始日志不变。")
        appendLine("最早留存记录时间（本地）：${coverage.first ?: "无"}")
        appendLine("最新留存记录时间（本地）：${coverage.last ?: "无"}")
        val evictionStatus = when (hasEvicted) {
            true -> "是"
            false -> "否"
            null -> "未知"
        }
        appendLine("较早分片已因容量淘汰：$evictionStatus")
        appendLine("旧版迁入的记录可能只有时分秒，且可能已在升级前被截断。")
        appendLine("java_crash_export.txt 为最近一份历史 Java 堆栈归档；last_crash.txt 仅在尚未被页面清理时存在，二者均不改变导出外的启动熔断语义。")
        appendLine("缺失文件说明：")
        if (missing.isEmpty()) appendLine("无（各固定白名单文件均存在）")
        else missing.forEach { appendLine(it) }
    }

    private fun report(name: String, producer: () -> String, missing: MutableList<String>): String =
        runCatching { producer() }.getOrElse {
            missing += "$name：报告生成失败（${it.javaClass.simpleName}）"
            "报告不可用：${it.javaClass.simpleName}: ${it.message ?: "无详细信息"}"
        }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName}($code)"
    }.getOrDefault("unknown")

    private class Coverage {
        private val prefix = timestampPrefix.matcher("")
        var first: String? = null
            private set
        private var lastLine: String? = null
        private var lastEnd = 0
        val last: String? get() = lastLine?.substring(0, lastEnd)

        fun record(line: String) {
            if (!prefix.reset(line).lookingAt()) return
            val end = prefix.end(1)
            if (first == null) first = line.substring(0, end)
            lastLine = line
            lastEnd = end
        }
    }

    private val timestampPrefix = Pattern.compile("^((?:[0-9]{4}-[0-9]{2}-[0-9]{2} )?[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3})  [DWE]  ")
    private val urlPattern = Regex("(?i)https?://[^\\s\\\"'<>]+")
    private val credentialPattern = Regex("(?i)(\\b(?:access_token|api_key|token|secret|password|signature)\\s*[:=]\\s*)[^\\s&,;]+")
    private val authorizationPattern = Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer|basic)\\s+[^\\s]+")

    private fun writeCrashSources(context: Context, zip: ZipOutputStream, missing: MutableList<String>) {
        val sources = linkedMapOf(
            "logs/native_crash.txt" to LogUploader.nativeCrashFile(context),
            "logs/native_crash.txt.err" to LogUploader.nativeAbortFile(context),
            "logs/java_crash_export.txt" to File(LogUploader.logDir(context), "java_crash_export.txt"),
            "logs/last_crash.txt" to LogUploader.javaCrashFile(context)
        )
        for ((name, file) in sources) {
            val input = try {
                file.bufferedReader(Charsets.UTF_8)
            } catch (error: FileNotFoundException) {
                if (file.exists()) throw error
                missing += "$name：文件不存在（可能尚未产生或已被清理）"
                continue
            }
            input.useLines { lines ->
                writeEntry(zip, name) { writer -> lines.forEach { writeRedactedLine(writer, it) } }
            }
        }
    }

    private fun writeRedactedLine(writer: BufferedWriter, line: String) {
        writer.write(redact(line))
        writer.write('\n'.code)
    }

    private fun redact(text: String): String {
        val urlsRemoved = urlPattern.replace(text) { match ->
            val uri = Uri.parse(match.value)
            val authority = uri.encodedAuthority?.substringAfterLast('@')
            if (authority.isNullOrBlank()) "<redacted-url>"
            else "${uri.scheme}://$authority${uri.encodedPath.orEmpty()}[redacted]"
        }
        return authorizationPattern.replace(credentialPattern.replace(urlsRemoved, "$1<redacted>"), "$1<redacted>")
    }

    private inline fun writeEntry(zip: ZipOutputStream, name: String, write: (BufferedWriter) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        val writer = zip.bufferedWriter(Charsets.UTF_8)
        write(writer)
        writer.flush()
        zip.closeEntry()
    }
}
