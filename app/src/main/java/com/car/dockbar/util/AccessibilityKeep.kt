package com.car.dockbar.util

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** 前台检测状态与系统恢复入口；运行必需的核心授权统一检查和补齐。 */
object AccessibilityKeep {

    private const val SERVICE = "com.car.dockbar.ui.ForegroundAppService"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ restore(app) }, 20_000L)
    }

    fun isEnabled(ctx: Context): Boolean = runCatching {
        val raw = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        raw.split(':').any { matches(it, ctx.packageName) } &&
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    }.getOrDefault(false)

    fun restore(ctx: Context) {
        StartupPermissions.start(ctx.applicationContext)
    }

    private fun matches(raw: String, pkg: String): Boolean {
        val c = ComponentName.unflattenFromString(raw.trim()) ?: return false
        return c.packageName == pkg && c.className == SERVICE
    }
}
