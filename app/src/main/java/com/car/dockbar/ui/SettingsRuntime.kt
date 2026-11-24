package com.car.dockbar.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.util.AccessibilityKeep
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.VehicleController

/** 设置窗口中的服务恢复与状态刷新，独立于当前显示的页面。 */
internal class SettingsRuntime(
    private val activity: MainActivity,
    private val refresh: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var notifWasOk = false
    private var lastToastMs = 0L
    private val refreshStatus = Runnable { if (!destroyed) refresh() }
    private val retryDock = Runnable {
        ensureDockStarted()
        refreshStatus.run()
    }
    private val selectionListener: () -> Unit = { refreshStatus.run() }

    fun start() {
        DockOverlayService.addDockSelectionListener(selectionListener)
        val context = activity.applicationContext
        Thread {
            runCatching { VehicleController.init(context) }
                .onFailure { Logx.e("车控初始化异常", it) }
            runCatching { MediaBridge.refresh(context) }
            main.post(refreshStatus)
        }.start()
    }

    fun onResume() {
        AccessibilityKeep.restore(activity)
        ensureDockStarted()
        val notifOk = MediaBridge.hasPermission(activity)
        if (notifOk && !notifWasOk) {
            Logx.d("检测到通知使用权刚被授予 → 立即重绑媒体会话")
            runCatching { MediaBridge.rebind(activity) }
        }
        notifWasOk = notifOk
        if (Prefs.lyricEnabled(activity) && !LyricOverlayService.alive) ensureLyricsStarted()
        refreshStatus.run()
        main.removeCallbacks(retryDock)
        main.postDelayed(retryDock, 1500)
    }

    fun onDestroy() {
        destroyed = true
        DockOverlayService.removeDockSelectionListener(selectionListener)
        main.removeCallbacksAndMessages(null)
    }

    fun ensureDockStarted() {
        if (!Prefs.overlayEnabled(activity)) return
        if (!Settings.canDrawOverlays(activity)) {
            Logx.e("悬浮窗权限未授予 → Dock 不会显示。请点「授权悬浮窗权限」，打开后回到本页会自动出现")
            toastOnce("请先授予悬浮窗权限，回来后 Dock 会自动出现")
            return
        }
        if (!DockOverlayService.alive) {
            startDock()
        } else if (!DockOverlayService.attached) {
            Logx.d("服务在运行但悬浮窗未挂载，重试挂载…")
            runCatching {
                activity.sendBroadcast(Intent(DockOverlayService.ACTION_RELOAD).setPackage(activity.packageName))
            }
        }
    }

    fun startDock() {
        Logx.d("启动 Dock 服务")
        runCatching {
            ContextCompat.startForegroundService(
                activity, Intent(activity, DockOverlayService::class.java).setAction(DockOverlayService.ACTION_START),
            )
        }.onFailure { Logx.e("启动失败", it) }
        main.postDelayed(refreshStatus, 800)
    }

    fun stopDock() {
        runCatching {
            activity.startService(Intent(activity, DockOverlayService::class.java).setAction(DockOverlayService.ACTION_STOP))
        }
        main.postDelayed(refreshStatus, 500)
    }

    fun ensureLyricsStarted(): Boolean {
        if (!Settings.canDrawOverlays(activity)) {
            Logx.e("歌词悬浮窗：没有悬浮窗权限")
            toastOnce("请先授予悬浮窗权限，回来后会自动出现")
            IntentLauncher.requestOverlayPermission(activity)
            return false
        }
        if (!LyricWindows.start(activity)) {
            toastOnce("启动失败，详情看日志")
            return false
        }
        main.postDelayed(refreshStatus, 800)
        return true
    }

    fun stopLyrics() {
        LyricWindows.stop(activity)
        main.postDelayed(refreshStatus, 500)
    }

    private fun toastOnce(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastMs < 4000) return
        lastToastMs = now
        runCatching { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }
}
