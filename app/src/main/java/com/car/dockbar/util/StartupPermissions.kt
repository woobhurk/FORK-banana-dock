package com.car.dockbar.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.car.dockbar.media.DockNotificationListener
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.ui.DockOverlayService
import com.car.dockbar.ui.ForegroundAppService
import com.car.dockbar.ui.LyricOverlayService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 核心权限是运行必需项，缺失即补齐；功能启停和车控熔断只约束服务恢复。 */
object StartupPermissions {
    private val busy = AtomicBoolean(false)
    private val shellIdentity = Regex("(?:^|\\s)uid=(?:0|2000)(?:\\(|\\s|$)")
    private val vehiclePermissions = arrayOf(
        "android.car.permission.CONTROL_CAR_CLIMATE",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_INFO",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
    )

    @Volatile
    var lastReport: String = "尚未检查核心权限"
        private set

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!busy.compareAndSet(false, true)) return
        try {
            Thread { execute(app) }.apply {
                name = "startup-permissions"
                isDaemon = true
            }.start()
        } catch (e: Exception) {
            busy.set(false)
            lastReport = "启动权限检查失败：${e.javaClass.simpleName}: ${e.message}"
            Logx.e(lastReport, e)
        }
    }

    /** 手动检查与启动共用同一补权流程，在后台调用。 */
    fun grantMissing(ctx: Context): String {
        if (!busy.compareAndSet(false, true)) return "权限检查正在执行，本次未重复执行。\n$lastReport"
        return execute(ctx.applicationContext)
    }

    private fun execute(ctx: Context): String {
        return try {
            Check(ctx).run().also {
                lastReport = it
                Logx.d(it)
            }
        } catch (e: Exception) {
            "权限检查异常：${e.javaClass.simpleName}: ${e.message}".also {
                lastReport = it
                Logx.e(it, e)
            }
        } finally {
            busy.set(false)
        }
    }

    private class Check(private val ctx: Context) {
        private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        private val report = StringBuilder("核心权限检查（SDK ${Build.VERSION.SDK_INT}，仅本机 ADB shell）\n")
        // Android 9 的 UserHandle.identifier 尚非公开 API，按框架 UID 分区取用户号。
        private val user = ctx.applicationInfo.uid / 100_000
        private val pkg = quote(ctx.packageName)
        private val accessibility = ComponentName(ctx, ForegroundAppService::class.java)
        private val listener = ComponentName(ctx, DockNotificationListener::class.java)
        private var port: Int? = null
        private var searched = false
        private var attempted = false
        private var permissionsChanged = false

        private fun lyricWanted() = (Prefs.lyricEnabled(ctx) || Prefs.lyricBoot(ctx)) && Prefs.safeLevel(ctx) < 2
        private fun windowWanted() = Prefs.overlayEnabled(ctx) || lyricWanted()
        private fun mediaWanted() = windowWanted() && Prefs.mediaEnabled(ctx) && Prefs.safeLevel(ctx) < 3
        private fun granted(permission: String) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        private fun remaining() = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)

        fun run(): String {
            report.append("应用用户：").append(user).append('\n')
            ensure("悬浮窗", { Settings.canDrawOverlays(ctx) }) {
                command("悬浮窗", "appops set --user $user $pkg SYSTEM_ALERT_WINDOW allow")
            }
            ensure("WRITE_SECURE_SETTINGS（无障碍维护）", {
                granted(Manifest.permission.WRITE_SECURE_SETTINGS)
            }) {
                command("WRITE_SECURE_SETTINGS", "pm grant --user $user $pkg android.permission.WRITE_SECURE_SETTINGS")
            }
            ensure("通知使用权", { MediaBridge.hasPermission(ctx) }) {
                command("通知使用权", "cmd notification allow_listener ${quote(listener.flattenToString())} $user")
            }
            ensure("前台检测无障碍", ::accessibilityEnabled) {
                grantAccessibility()
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ensure("通知发送", { granted(Manifest.permission.POST_NOTIFICATIONS) }) {
                    command("通知发送", "pm grant --user $user $pkg android.permission.POST_NOTIFICATIONS")
                }
            }
            ensureVehiclePermissions()
            if (!searched) report.append("未连接 ADB：没有可执行的缺失授权。\n")
            recoverServices()
            connectionReport()
            if (remaining() == 0L) report.append("检查已到 45 秒期限；未确认的项目不代表成功。\n")
            report.append("仅尝试本包声明的核心与车控权限；未请求 root、存储、日志、安装来源或电池白名单；未写入车控通道，未修改用户功能开关和熔断。")
            return report.toString()
        }

        private fun ensureVehiclePermissions() {
            val declared = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions.orEmpty()
            } catch (e: Exception) {
                report.append("读取应用权限声明失败，未尝试车控授权：").append(e.message).append('\n')
                return
            }
            for (permission in vehiclePermissions) {
                val label = "车控权限 ${permission.substringAfterLast('.')}"
                if (permission !in declared) {
                    report.append(label).append("：本应用未声明，未尝试授予。\n")
                    continue
                }
                ensure(label, { granted(permission) }) {
                    val info = try {
                        ctx.packageManager.getPermissionInfo(permission, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        report.append(label).append("：设备未定义此权限，未执行授权。\n")
                        return@ensure
                    } catch (e: Exception) {
                        report.append(label).append("：读取本机定义失败，未执行授权：")
                            .append(e.message).append('\n')
                        return@ensure
                    }
                    report.append(label).append("：本机保护级别=0x")
                        .append(Integer.toHexString(info.protectionLevel)).append("，尝试 ADB 授予并回读。\n")
                    command(label, "pm grant --user $user $pkg ${quote(permission)}")
                }
            }
        }

        private fun accessibilityEnabled(): Boolean {
            val cr = ctx.contentResolver
            val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { ComponentName.unflattenFromString(it.trim()) == accessibility } &&
                Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }

        private fun read(label: String, state: () -> Boolean): Boolean? = try {
            state()
        } catch (e: Exception) {
            report.append(label).append("：读取失败，未视为已授权：")
                .append(e.javaClass.simpleName).append(": ").append(e.message).append('\n')
            null
        }

        private fun ensure(label: String, state: () -> Boolean, grant: () -> Unit) {
            when (read(label, state)) {
                null -> return
                true -> report.append(label).append("：已满足，无需写入。\n")
                false -> {
                    if (mayGrant()) grant()
                    val finalState = read(label, state)
                    if (finalState == true) permissionsChanged = true
                    report.append(label).append("：最终回读")
                        .append(when (finalState) {
                            true -> "已满足。\n"
                            false -> "仍缺失，未确认生效。\n"
                            null -> "失败，未确认生效。\n"
                        })
                }
            }
        }

        private fun mayGrant(): Boolean = remaining() > 0L

        private fun adbPort(): Int? {
            if (searched) return port
            searched = true
            for (candidate in PrivilegeProbe.localAdbPorts()) {
                if (!mayGrant()) break
                val result = AdbClient.exec(candidate, "id", minOf(3000L, remaining()).coerceAtLeast(1).toInt())
                if (result.ok && shellIdentity.containsMatchIn(result.output)) {
                    port = candidate
                    report.append("本机 ADB 执行身份已确认：端口 ").append(candidate)
                        .append("，").append(result.output.trim()).append('\n')
                    break
                }
                report.append("ADB ").append(candidate).append("：")
                    .append(if (result.ok) "执行身份不是 shell/root：${result.output}" else details(result)).append('\n')
            }
            if (port == null) report.append("没有可用且已确认的本机 shell，缺项保持未授权。\n")
            return port
        }

        private fun command(label: String, cmd: String): AdbClient.Result? {
            if (!mayGrant()) return null
            val target = adbPort() ?: return null
            if (!mayGrant()) return null
            attempted = true
            val result = AdbClient.exec(target, cmd, minOf(6000L, remaining()).coerceAtLeast(1).toInt())
            if (!result.ok) report.append(label).append("：命令失败：").append(details(result)).append('\n')
            return result
        }

        private fun grantAccessibility() {
            // 写名单之前必须从同一 Android 用户重新读取，不能用启动时的旧快照覆盖其它服务。
            val current = command("读取无障碍名单", "settings --user $user get secure enabled_accessibility_services")
                ?: return
            if (!current.ok) return
            val raw = current.output.trim()
            val list = if (raw == "null") "" else raw
            if (list.split(':').none { ComponentName.unflattenFromString(it.trim()) == accessibility }) {
                val merged = if (list.isEmpty()) accessibility.flattenToString() else "$list:${accessibility.flattenToString()}"
                if (command("写入无障碍名单", "settings --user $user put secure enabled_accessibility_services ${quote(merged)}")?.ok != true) return
            }
            // 主开关也重新读取；已经启用就不重复写入。
            val master = command("读取无障碍主开关", "settings --user $user get secure accessibility_enabled") ?: return
            if (!master.ok) return
            when (master.output.trim()) {
                "1" -> Unit
                "0", "null" -> command("启用无障碍主开关", "settings --user $user put secure accessibility_enabled 1")
                else -> report.append("无障碍主开关返回值异常，未执行写入：").append(master.output).append('\n')
            }
        }

        private fun recoverServices() {
            if (remaining() == 0L) {
                report.append("期限已到，未派发服务恢复。\n")
                return
            }
            val done = CountDownLatch(1)
            val notes = StringBuilder()
            val cancelled = AtomicBoolean(false)
            val task = Runnable {
                try {
                    if (cancelled.get() || remaining() == 0L) return@Runnable
                    fun recover(label: String, action: () -> Unit) {
                        try {
                            action()
                            notes.append(label).append("：已派发恢复，实际状态另行回读。\n")
                        } catch (e: Exception) {
                            notes.append(label).append("：恢复失败：").append(e.javaClass.simpleName)
                                .append(": ").append(e.message).append('\n')
                        }
                    }
                    if (Prefs.overlayEnabled(ctx) && Settings.canDrawOverlays(ctx) &&
                        (permissionsChanged || !DockOverlayService.alive)) {
                        recover("Dock") {
                            ContextCompat.startForegroundService(ctx, Intent(ctx, DockOverlayService::class.java)
                                .setAction(if (DockOverlayService.alive) DockOverlayService.ACTION_RELOAD else DockOverlayService.ACTION_START))
                        }
                    }
                    if (mediaWanted() && MediaBridge.hasPermission(ctx)) recover("媒体") {
                        if (permissionsChanged) MediaBridge.rebind(ctx) else MediaBridge.refresh(ctx)
                    }
                    if (lyricWanted() && Settings.canDrawOverlays(ctx) &&
                        (permissionsChanged || !LyricOverlayService.alive)) {
                        // LyricWindows.start 会改 lyricEnabled；直接复用开机入口，保留 boot-only 原值。
                        recover("歌词") {
                            ContextCompat.startForegroundService(ctx, Intent(ctx, LyricOverlayService::class.java))
                        }
                    }
                } catch (e: Exception) {
                    notes.append("服务恢复异常：").append(e.javaClass.simpleName).append(": ").append(e.message).append('\n')
                } finally {
                    done.countDown()
                }
            }
            val main = Handler(Looper.getMainLooper())
            if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
            if (done.await(minOf(3000L, remaining()), TimeUnit.MILLISECONDS)) {
                report.append(notes)
            } else {
                cancelled.set(true)
                main.removeCallbacks(task)
                report.append("服务恢复尚未确认完成，停止等待；未执行的恢复已取消。\n")
            }
        }

        private fun connectionReport() {
            // 名单授权不等于系统已经绑定服务，最多等待两秒，不循环重写授权。
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(minOf(2000L, remaining()))
            while (System.nanoTime() < until && (
                    read("无障碍授权", ::accessibilityEnabled) == true && ForegroundAppService.instance == null ||
                    read("通知使用权", { MediaBridge.hasPermission(ctx) }) == true && DockNotificationListener.instance == null
                )) {
                Thread.sleep(minOf(100L, remaining()).coerceAtLeast(1))
            }
            report.append("无障碍实际连接：").append(if (ForegroundAppService.instance != null) "已连接" else "尚未连接")
                .append("（名单及主开关：").append(read("无障碍授权", ::accessibilityEnabled)).append("）。\n")
            if (Prefs.overlayEnabled(ctx)) {
                report.append("Dock 实际状态：alive=").append(DockOverlayService.alive)
                    .append("，attached=").append(DockOverlayService.attached).append("。\n")
            }
            report.append("通知监听实际连接：")
                .append(if (DockNotificationListener.instance != null) "已连接" else "尚未连接")
                .append("（通知使用权：").append(read("通知使用权", { MediaBridge.hasPermission(ctx) })).append("）。\n")
            if (lyricWanted()) report.append("歌词服务实际状态：alive=").append(LyricOverlayService.alive).append("。\n")
            if (attempted) report.append("命令结果与最终授权、服务连接分别报告；未连接不视为恢复成功。\n")
        }

        private fun details(result: AdbClient.Result) =
            "${result.error}；exitCode=${result.exitCode}；输出=${result.output}"
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
