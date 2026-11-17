package com.car.dockbar.util

import android.content.Context
import android.net.Uri
import android.os.Build
import com.car.dockbar.vehicle.HvacChannelEvidence
import com.car.dockbar.vehicle.HvacDiagnostics
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 生成用户主动选择位置的本地诊断 ZIP；不上传，也不读取偏好、密钥或上传队列。
 * 活动日志在 Logx 锁内取快照，归档复制、脱敏、压缩和文档写入均在锁外执行。
 */
internal object DiagnosticLogExporter {
    data class Result(val success: Boolean, val message: String)

    fun export(context: Context, destination: Uri): Result {
        val appContext = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var work: File? = null
        return try {
            work = java.nio.file.Files.createTempDirectory(appContext.cacheDir.toPath(), "diagnostic-export-").toFile()
            val snapshot = File(work, "snapshot")
            if (!snapshot.mkdir()) throw IOException("无法创建导出日志快照目录")
            val zipFile = File(work, "logs.zip")
            val sources = linkedMapOf(
                "logs/session.log" to LogUploader.sessionLog(appContext),
                "logs/prev.log" to LogUploader.prevLog(appContext),
                "logs/native_crash.txt" to LogUploader.nativeCrashFile(appContext),
                "logs/native_crash.txt.err" to LogUploader.nativeAbortFile(appContext),
                "logs/java_crash_export.txt" to File(LogUploader.logDir(appContext), "java_crash_export.txt"),
                "logs/last_crash.txt" to LogUploader.javaCrashFile(appContext)
            )
            val missing = Logx.copyFilesForExport(sources, snapshot)
            sanitizeCopiedLogs(snapshot, sources.keys + "memory_snapshot.log")
            writeText(snapshot, "hvac/channel-evidence.txt", HvacChannelEvidence.report())
            writeText(snapshot, "hvac/diagnostics.txt", HvacDiagnostics.report())
            writeText(snapshot, "metadata.txt", buildMetadata(appContext, stamp, missing))
            writeZip(snapshot, zipFile)
            val output = appContext.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("系统未提供文档写入流")
            output.use { zipFile.inputStream().use { input -> input.copyTo(it) } }
            Result(true, "导出完成")
        } catch (t: Throwable) {
            Logx.e("诊断日志导出失败", t)
            Result(false, "导出失败：${t.javaClass.simpleName}；未生成完整日志包，目标文件可能不完整")
        } finally {
            work?.deleteRecursively()
        }
    }

    private fun buildMetadata(context: Context, stamp: String, missing: List<String>): String = buildString {
        appendLine("Banana_Dock 诊断与运行日志导出")
        appendLine("生成时间：$stamp")
        appendLine("包名：${context.packageName}")
        appendLine("版本：${appVersion(context)}")
        appendLine("Android SDK：${Build.VERSION.SDK_INT}")
        appendLine("设备型号：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("说明：选择保存位置不会自动分享或上传；ZIP 仅由用户主动保存。")
        appendLine("隐私提示：日志可能包含应用名/包名、媒体信息及设备诊断信息，请仅交给可信分析者。")
        appendLine("脱敏：导出副本移除 HTTP(S) URL 用户信息、查询参数、片段以及常见认证字段；原始日志不变。")
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

    private fun writeText(root: File, name: String, value: String) {
        val file = File(root, name)
        file.parentFile?.mkdirs()
        file.writeText(value, StandardCharsets.UTF_8)
    }

    private val urlPattern = Regex("(?i)https?://[^\\s\\\"'<>]+")
    private val credentialPattern = Regex("(?i)(\\b(?:access_token|api_key|token|secret|password|signature)\\s*[:=]\\s*)[^\\s&,;]+")
    private val authorizationPattern = Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer|basic)\\s+[^\\s]+")

    private fun sanitizeCopiedLogs(root: File, names: Collection<String>) {
        for (name in names) {
            val file = File(root, name)
            if (!file.exists()) continue
            val sanitized = File(file.parentFile, file.name + ".redacted")
            file.bufferedReader(Charsets.UTF_8).use { input ->
                sanitized.bufferedWriter(Charsets.UTF_8).use { output ->
                    input.forEachLine { line -> output.append(redact(line)).append('\n') }
                }
            }
            java.nio.file.Files.move(sanitized.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
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

    private fun writeZip(root: File, zipFile: File) {
        FileOutputStream(zipFile).use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    val entryName = file.relativeTo(root).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}
