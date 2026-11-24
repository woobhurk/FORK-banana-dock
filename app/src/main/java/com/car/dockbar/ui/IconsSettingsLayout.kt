package com.car.dockbar.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.model.DockEntries


internal class IconsSettingsLayout(activity: Activity) : SettingsPageLayout(activity) {
    fun rebuild(page: LinearLayout) {
        val manage = page.findViewById<Button>(R.id.settingsManageDockEntries)
        val summary = page.findViewById<TextView>(R.id.settingsQuickAppsSummary)
        val manageApps = page.findViewById<Button>(R.id.settingsManageQuickApps)
        val grid = page.findViewById<View>(R.id.settingsQuickAppsGrid)
        val fade = page.findViewById<View>(R.id.settingsFadeDurationRow)
        val longPress = page.findViewById<View>(R.id.settingsLongPressDelayRow)
        val auto = page.findViewById<View>(R.id.settingsAutoFanRow)
        val targets = page.findViewById<EditText>(R.id.etSettingsPkg).parent as View
        take(targets)
        listOf(manage, summary, manageApps, grid, fade, longPress, auto).forEach { take(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        heading(page, "Dock 图标管理", "管理快捷入口、排列顺序与交互设置")
        val left = column()
        val right = column()
        page.addView(settingsColumns(activity, left, right, firstWeight = 1.51f), params())
        val entries = card(left, "Dock 图标", "添加、删除图标，长按拖动调整排列顺序")
        val entryHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
        entryHeader.addView(take(entries.getChildAt(0)), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        entryHeader.addView(text("已添加 ${DockEntries.load(activity).size} 项", 13f, true).apply {
            id = R.id.settingsEntryCount
            background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        })
        entries.addView(entryHeader, 0)
        manage.text = "修改 Dock 图标显示"
        manage.textSize = 15f
        manage.gravity = Gravity.CENTER
        manage.setPadding(dp(20), dp(12), dp(20), dp(12))
        manage.background = ContextCompat.getDrawable(activity, R.drawable.bg_button_primary)
        manage.setTextColor(color(R.color.settings_on_accent))
        add(entries, manage, 12)

        val apps = card(left, "快捷应用", "点击空位添加，长按拖动换位，点击 × 清除应用")
        val appsHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
        summary.setPadding(0, 0, 0, 0)
        summary.textSize = 14f
        summary.setTextColor(color(R.color.settings_accent))
        appsHeader.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        manageApps.textSize = 13f
        manageApps.visibility = View.GONE
        manageApps.minHeight = dp(40)
        manageApps.background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_nav_idle)
        manageApps.setTextColor(color(R.color.settings_secondary))
        appsHeader.addView(manageApps)
        add(apps, appsHeader, 10)
        add(apps, grid, 12)
        apps.addView(text("快捷应用显示在 Dock 的应用面板中", 13f, true), params(18))

        val feedback = card(right, "交互与反馈", "控制长按触发时间和显示动画")
        add(feedback, longPress, 8)
        add(feedback, fade, 6)

        val autoCard = card(right, "AUTO 风道档位")
        add(autoCard, auto, 8)
        page.addView(targets, params())
    }
}
