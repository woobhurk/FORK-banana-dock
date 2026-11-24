package com.car.dockbar.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R


internal class FloatingDockSettingsLayout(activity: Activity) : SettingsPageLayout(activity) {
    fun rebuild(page: LinearLayout) {
        val side = page.findViewById<LinearLayout>(R.id.colOverlaySide)
        val width = page.findViewById<EditText>(R.id.etWidth)
        val dimensions = width.parent.parent as LinearLayout
        val enabled = page.findViewById<SwitchCompat>(R.id.swEnabled)
        val simulate = page.findViewById<SwitchCompat>(R.id.swSimulate)
        val apply = page.findViewById<Button>(R.id.btnApplyWindow)
        val screenHeight = page.findViewById<EditText>(R.id.etScreenH)
        val permissionIds = listOf(R.id.tvOverlayPerm, R.id.tvNotifPerm, R.id.tvAccPerm)
        val permissions = permissionIds.map { page.findViewById<TextView>(it).parent as LinearLayout }
        val known = setOf<View>(enabled, simulate, dimensions, apply, screenHeight)
        val interaction = (0 until side.childCount).map { side.getChildAt(it) }.filter { it !in known }

        // 清空旧版“左侧权限、右侧表单”布局前，先将控件从原父容器中移除。
        (known + permissions + interaction).forEach { take(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        val badge = text("●  Dock 未运行", 14f).apply {
            id = R.id.settingsRuntimeBadge
            setTextColor(color(R.color.settings_warning))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_nav_selected)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        heading(page, "悬浮 Dock 栏", "设置窗口尺寸、显示行为与运行权限", badge)
        val left = column()
        val right = column()
        page.addView(settingsColumns(activity, left, right), params())

        val geometry = card(left, "布局与位置", "调整后点击“应用布局”保存并生效")
        dimensions.orientation = LinearLayout.HORIZONTAL
        dimensions.gravity = Gravity.BOTTOM
        for (i in 0 until dimensions.childCount) {
            val field = dimensions.getChildAt(i) as? LinearLayout ?: continue
            (field.getChildAt(0) as? TextView)?.apply {
                textSize = 14f
                setTextColor(color(R.color.settings_secondary))
            }
            (field.getChildAt(1) as? EditText)?.apply {
                textSize = 20f
                minHeight = dp(48)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setTextColor(color(R.color.settings_text))
                setHintTextColor(color(R.color.settings_secondary))
                background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_input)
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
            }
        }
        add(geometry, dimensions, 16)
        add(geometry, screenHeight, 0) // 保留隐藏的旧版配置项，供 saveWindow() 使用。
        geometry.addView(text("推荐尺寸 1920 × 128 px", 13f, true), params(12))
        val runtime = card(right, "运行与权限", "管理悬浮栏所需的运行权限")
        enabled.text = "启用悬浮 Dock"
        enabled.textSize = 16f
        enabled.minHeight = dp(48)
        add(runtime, enabled, 8)
        divider(runtime)
        permissions.forEachIndexed { index, permission ->
            if (index > 0) divider(runtime)
            permission.background = null
            permission.setPadding(0, 0, 0, 0)
            val label = permission.getChildAt(0) as TextView
            val value = permission.getChildAt(1) as TextView
            val permissionHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
            take(label)
            take(value)
            label.textSize = 15f
            label.setTextColor(color(R.color.settings_text))
            value.textSize = 14f
            value.typeface = AppFont.bold(activity)
            permissionHeader.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            permissionHeader.addView(value, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            permission.addView(permissionHeader, 0)
            for (i in 0 until permission.childCount) {
                (permission.getChildAt(i) as? Button)?.apply {
                    textSize = 14f
                    minHeight = dp(48)
                    background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_secondary_button)
                    setTextColor(ContextCompat.getColorStateList(activity, R.color.settings_action_text))
                    (layoutParams as LinearLayout.LayoutParams).width = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            add(runtime, permission, if (index == 0) 4 else 0)
        }

        val actions = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val tip = text("尺寸调整后，点击“应用布局”生效", 13f, true)
        page.addView(settingsColumns(activity, tip, actions, minWidthDp = 520,
            gapDp = 12, wrapSecond = true).apply { gravity = Gravity.CENTER_VERTICAL }, params(18))
        actions.addView(Button(activity).apply {
            text = "恢复默认"
            textSize = 14f
            background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_secondary_button)
            setTextColor(ContextCompat.getColorStateList(activity, R.color.settings_action_text))
            setOnClickListener {
                // 仅重置这些输入框，仍需点击“应用布局”才会保存。
                width.setText("1920")
                page.findViewById<EditText>(R.id.etHeight).setText("128")
                page.findViewById<EditText>(R.id.etMargin).setText("0")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })
        actions.addView(take(apply), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val behavior = card(left, "显示与唤出", "调整上滑唤出与临时显示行为")
        side.removeAllViews()
        side.background = null
        side.setPadding(0, 0, 0, 0)
        interaction.forEach { add(side, it, 6) }
        add(behavior, side, 8)
        divider(behavior)
        simulate.text = "模拟模式 · 不连接车控"
        simulate.textSize = 16f
        simulate.minHeight = dp(48)
        add(behavior, simulate, 4)
    }
}
