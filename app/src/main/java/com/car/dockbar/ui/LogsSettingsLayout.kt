package com.car.dockbar.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.car.dockbar.R

/** 诊断页按用途挂载控件，不再从旧布局按按钮位置或文案推断归属。 */
internal class LogsSettingsLayout(activity: Activity, page: LinearLayout) : SettingsPageLayout(activity) {
    val overview: LinearLayout
    val vehicle: LinearLayout
    val upload: LinearLayout
    val permissions: LinearLayout
    val logs: LinearLayout
    val advanced: LinearLayout
    val metrics: List<TextView>

    init {
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        heading(page, "诊断与日志", "查看运行状态、导出诊断和排查车控问题")

        overview = card(page, "运行概况")
        val metricRow = row()
        add(overview, metricRow, 12)
        metrics = listOf("设备标识", "Native 崩溃捕获", "安全等级", "待补传崩溃").mapIndexed { index, label ->
            val metric = column().apply { if (index > 0) setPadding(dp(12), 0, 0, 0) }
            metricRow.addView(metric, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            add(metric, text(label, 13f, true), 0)
            text("", 16f).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                add(metric, this, 6)
            }
        }

        val paired = row()
        page.addView(paired, params())
        val vehicleColumn = column()
        val uploadColumn = column()
        paired.addView(vehicleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        paired.addView(uploadColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(22)
        })
        vehicle = card(vehicleColumn, "车控保护")
        upload = card(uploadColumn, "日志上传")
        permissions = card(page, "权限状态")
        logs = card(page, "运行日志")
        advanced = card(page, "排查工具")
    }

    fun note(parent: LinearLayout, value: String = "", top: Int = 8): TextView =
        text(value, 14f, true).also { add(parent, it, top) }

    fun subsection(parent: LinearLayout, title: String) {
        divider(parent)
        add(parent, text(title, 17f).apply { typeface = AppFont.bold(activity) }, 10)
    }

    fun action(
        parent: LinearLayout, title: String, primary: Boolean = false, onClick: (Button) -> Unit
    ): Button = button(title, primary, onClick).also { add(parent, it, 10) }

    fun button(title: String, primary: Boolean = false, onClick: (Button) -> Unit): Button = Button(activity).apply {
        text = title
        styleAction(this, primary)
        setOnClickListener { onClick(this) }
    }

    fun pairedButtons(parent: LinearLayout, first: Button, second: Button) {
        val buttons = row()
        buttons.addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        buttons.addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(10)
        })
        add(parent, buttons, 10)
    }

    fun fold(
        parent: LinearLayout, openLabel: String, closeLabel: String, onChanged: (Boolean) -> Unit = {}
    ): LinearLayout {
        val body = column().apply { isVisible = false }
        action(parent, openLabel) { toggle ->
            val open = !body.isVisible
            if (!open) body.findFocus()?.clearFocus()
            body.isVisible = open
            toggle.text = if (open) closeLabel else openLabel
            onChanged(open)
        }
        add(parent, body, 0)
        return body
    }

    fun mountConsole(parent: LinearLayout, clear: Button, log: LogTailScrollView, logText: TextView) {
        styleAction(clear, primary = false)
        add(parent, clear, 10)
        log.setPadding(dp(14), dp(14), dp(14), dp(14))
        log.background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
        logText.textSize = 13f
        logText.typeface = Typeface.MONOSPACE
        logText.setTextColor(color(R.color.settings_text))
        parent.addView(take(log), params(12).apply { height = dp(320) })
    }

    private fun styleAction(button: Button, primary: Boolean) {
        button.textSize = 14f
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = dp(if (primary) 56 else 48)
        button.minimumHeight = button.minHeight
        button.background = if (primary) {
            ContextCompat.getDrawable(activity, R.drawable.bg_button_primary)
        } else {
            RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), ContextCompat.getDrawable(activity, R.drawable.bg_field), null)
        }
        button.setPadding(dp(10), dp(6), dp(10), dp(6))
        button.setTextColor(color(if (primary) R.color.settings_on_accent else R.color.settings_text))
    }
}
