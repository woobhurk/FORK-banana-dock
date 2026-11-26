package com.car.dockbar.ui

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.util.AccessibilityKeep
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import android.provider.Settings

class FloatingDockSettingsPage(activity: MainActivity, root: LinearLayout) :
    SettingsPage(activity, MainActivity.SECTION_OVERLAY, root) {
    override fun buildLayout() { FloatingDockSettingsLayout(activity).rebuild(root) }
    override fun onShown() { refreshStatus() }
    private lateinit var tvOverlayPerm: TextView
    private lateinit var tvNotifPerm: TextView
    private lateinit var tvNotifHint: TextView
    private lateinit var bananaDockSwitch: SwitchCompat
    private lateinit var originalDockSwitch: SwitchCompat
    private lateinit var originalDockResult: TextView
    private var updatingDockSwitches = false
    internal fun refreshStatus() {
        refreshDockSwitches()
        updateStatus()
    }
    override fun create() {
        tvOverlayPerm = findViewById(R.id.tvOverlayPerm)
        tvNotifPerm = findViewById(R.id.tvNotifPerm)
        tvNotifHint = findViewById(R.id.tvNotifHint)
        bananaDockSwitch = findViewById(R.id.swEnabled)
        originalDockSwitch = findViewById(R.id.swUseOriginalDock)
        originalDockResult = findViewById(R.id.tvOriginalDockResult)
        refreshDockSwitches()
        findViewById<SwitchCompat>(R.id.swSimulate).isChecked = Prefs.simulate(this)
        findViewById<EditText>(R.id.etWidth).setText(Prefs.dockWidthPx(this).toString())
        findViewById<EditText>(R.id.etHeight).setText(Prefs.dockHeightPx(this).toString())
        findViewById<EditText>(R.id.etMargin).setText(Prefs.dockMarginPx(this).toString())
        findViewById<EditText>(R.id.etScreenH).setText(Prefs.screenHeightPx(this).toString())

        bananaDockSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingDockSwitches) return@setOnCheckedChangeListener
            if (!checked && !Prefs.useOriginalDock(this)) {
                // 替换模式下关闭 Banana Dock 前先请求恢复原厂栏。
                DockOverlayService.requestDockSelection(this, useOriginal = true)
            } else {
                Prefs.setOverlayEnabled(this, checked)
                if (checked) activity.runtime.startDock() else activity.runtime.stopDock()
            }
        }
        originalDockSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingDockSwitches) DockOverlayService.requestDockSelection(this, checked)
        }

        findViewById<Button>(R.id.btnApplyWindow).setOnClickListener {
            saveWindow()
            activity.runtime.ensureDockStarted()
            reloadService()
            main.postDelayed({ updateStatus() }, 800)
        }

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener { IntentLauncher.requestOverlayPermission(this) }
        findViewById<Button>(R.id.btnGrantNotif).setOnClickListener { IntentLauncher.requestNotificationAccess(this) }
        findViewById<Button>(R.id.btnGrantAccessibilityDock).setOnClickListener { openAccessibilitySettings() }
        val overlaySide = findViewById<LinearLayout>(R.id.colOverlaySide)
        addSwitchRow(
            overlaySide, "底部上滑临时显示 Dock",
            Prefs.swipeShowDockEnabled(this)
        ) { Prefs.setSwipeShowDockEnabled(this, it) }
        addSeekRow(
            overlaySide, "上滑后 Dock 显示时长",
            (Prefs.swipeShowDockMs(this) / 1000L).toInt(), 30,
            { "%d 秒".format(it) },
            onChange = { Prefs.setSwipeShowDockMs(this, it * 1000L) }
        )
    }

    private fun refreshDockSwitches() {
        updatingDockSwitches = true
        bananaDockSwitch.isChecked = Prefs.overlayEnabled(this)
        originalDockSwitch.isChecked = Prefs.useOriginalDock(this)
        bananaDockSwitch.isEnabled = !DockOverlayService.dockSelectionPending
        originalDockSwitch.isEnabled = !DockOverlayService.dockSelectionPending
        DockOverlayService.dockSelectionMessage?.let { originalDockResult.text = it }
        updatingDockSwitches = false
    }

    private fun saveWindow() {
        val w = findViewById<EditText>(R.id.etWidth).text.toString().trim().toIntOrNull() ?: 1920
        val h = findViewById<EditText>(R.id.etHeight).text.toString().trim().toIntOrNull() ?: 40
        val m = findViewById<EditText>(R.id.etMargin).text.toString().trim().toIntOrNull() ?: 0
        Prefs.setDockWidthPx(this, w)
        Prefs.setDockHeightPx(this, h)
        Prefs.setDockMarginPx(this, m)
        Prefs.setScreenHeightPx(
            this,
            findViewById<EditText>(R.id.etScreenH).text.toString().trim().toIntOrNull() ?: 1080
        )
        Prefs.setSimulate(this, findViewById<SwitchCompat>(R.id.swSimulate).isChecked)
        Logx.d("窗口配置已保存：${if (w > 0) w else "MATCH_PARENT"}×$h，距边 $m px")
    }
    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = MediaBridge.hasPermission(this)
        val running = DockOverlayService.alive
        findViewById<TextView>(R.id.settingsRuntimeBadge).apply {
            text = if (running) "●  Dock 正在运行" else "●  Dock 未运行"
            setTextColor(ContextCompat.getColor(this@FloatingDockSettingsPage,
                if (running) R.color.settings_service_running else R.color.settings_warning))
        }
        findViewById<View>(R.id.btnGrantOverlay).visibility = View.VISIBLE
        findViewById<View>(R.id.btnGrantNotif).visibility = View.VISIBLE

        tvOverlayPerm.text = if (overlayOk) "已授权" else "未授权"
        tvOverlayPerm.setTextColor(permColor(overlayOk))
        tvNotifPerm.text = if (notifOk) "已授权" else "未授权"
        tvNotifPerm.setTextColor(permColor(notifOk))
        tvNotifHint.visibility = if (notifOk) View.GONE else View.VISIBLE
        val accessibility = AccessibilityKeep.isEnabled(this)
        findViewById<TextView>(R.id.tvAccPerm).apply {
            text = if (accessibility) "已授权" else "未授权"
            setTextColor(permColor(accessibility))
        }
        findViewById<View>(R.id.tvAccHint).visibility = if (accessibility) View.GONE else View.VISIBLE
    }
    private fun permColor(ok: Boolean): Int = ContextCompat.getColor(
        this, if (ok) R.color.settings_service_running else R.color.settings_warning
    )

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure { Logx.e("打开无障碍设置失败", it) }
    }
}
