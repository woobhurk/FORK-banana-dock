package com.car.dockbar.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.RotateDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.car.dockbar.R
import kotlin.math.ceil

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

        val left = column()
        val right = column()
        page.addView(adaptiveRow(left, right, dp(560), dp(18), equalColumns = true), params())

        overview = diagnosticCard(left, "运行概况")
        val firstMetrics = row().apply { isBaselineAligned = false }
        val secondMetrics = row().apply { isBaselineAligned = false }
        add(overview, firstMetrics, 6)
        add(overview, secondMetrics, 12)
        metrics = listOf("设备标识", "Native 崩溃捕获", "安全等级", "待补传崩溃").mapIndexed { index, label ->
            val metric = column().apply { if (index % 2 == 1) setPaddingRelative(dp(12), 0, 0, 0) }
            val metricRow = if (index < 2) firstMetrics else secondMetrics
            metricRow.addView(metric, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            add(metric, text(label, 13f, true), 0)
            text("", 16f).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                add(metric, this, 6)
            }
        }

        upload = diagnosticCard(left, "日志上传")
        logs = diagnosticCard(left)
        permissions = diagnosticCard(right)
        vehicle = diagnosticCard(right, "车控保护")
        advanced = diagnosticCard(right)
    }

    fun note(parent: LinearLayout, value: String = "", top: Int = 8): TextView =
        text(value, 14f, true).also { add(parent, it, top) }

    fun subsection(parent: LinearLayout, title: String) {
        divider(parent)
        add(parent, text(title, 17f).apply { typeface = AppFont.bold(activity) }, 10)
    }

    fun action(
        parent: LinearLayout, title: String, primary: Boolean = false, onClick: (Button) -> Unit
    ): Button = button(title, primary, onClick).also { parent.addView(it, actionParams()) }

    fun button(title: String, primary: Boolean = false, onClick: (Button) -> Unit): Button = Button(activity).apply {
        text = title
        styleAction(this, primary)
        setOnClickListener { onClick(this) }
    }

    fun pairedButtons(parent: LinearLayout, first: Button, second: Button) {
        val gap = dp(8)
        val fittingWidth = actionWidth(first) + actionWidth(second) + gap
        add(parent, adaptiveRow(first, second, fittingWidth, gap), 10)
    }

    fun fold(
        parent: LinearLayout,
        title: String,
        titleSize: Float = 21f,
        summary: ((LinearLayout) -> Unit)? = null,
        onChanged: (Boolean) -> Unit = {}
    ): LinearLayout {
        val body = column().apply { isVisible = false }
        val arrow = RotateDrawable().apply {
            drawable = ContextCompat.getDrawable(activity, R.drawable.ic_diagnostic_expand)
            toDegrees = 180f
        }
        val toggle = button("展开") { button ->
            val open = !body.isVisible
            if (!open) body.findFocus()?.clearFocus()
            body.isVisible = open
            button.text = if (open) "收起" else "展开"
            button.contentDescription = if (open) "收起$title" else "展开$title"
            button.isActivated = open
            arrow.level = if (open) 10000 else 0
            onChanged(open)
        }.apply {
            background = ContextCompat.getDrawable(activity, R.drawable.bg_diagnostic_fold)
            contentDescription = "展开$title"
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
            minimumWidth = maxOf(dp(92), actionWidth(this), actionWidth(this, "收起"))
        }
        sectionHeader(parent, title, titleSize, toggle)
        summary?.invoke(parent)
        add(parent, body, 8)
        return body
    }

    fun mountConsole(parent: LinearLayout, clear: Button, log: LogTailScrollView, logText: TextView) {
        styleAction(clear, primary = false)
        log.setPadding(dp(14), dp(14), dp(14), dp(14))
        log.background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
        logText.textSize = 13f
        logText.typeface = Typeface.MONOSPACE
        logText.setTextColor(color(R.color.settings_text))
        parent.addView(take(log), params().apply { height = dp(208) })
        parent.addView(take(clear), actionParams())
    }

    private fun diagnosticCard(parent: LinearLayout, title: String? = null): LinearLayout = column().apply {
        background = ContextCompat.getDrawable(activity, R.drawable.bg_card)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        parent.addView(this, params(16))
        if (title != null) sectionHeader(this, title, 21f)
    }

    private fun sectionHeader(parent: LinearLayout, title: String, size: Float, toggle: Button? = null) {
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        header.addView(text(title, size).apply {
            typeface = AppFont.bold(activity)
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (toggle != null) header.addView(toggle, actionParams(0).apply { marginStart = dp(8) })
        add(parent, header, 0)
    }

    private fun actionParams(top: Int = 10) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun actionWidth(button: Button, label: String = button.text.toString()): Int =
        ceil(button.paint.measureText(label)).toInt() + button.compoundPaddingLeft + button.compoundPaddingRight

    private fun adaptiveRow(
        first: View, second: View, horizontalMin: Int, gap: Int, equalColumns: Boolean = false
    ): LinearLayout = object : LinearLayout(activity) {
        private val firstParams = if (equalColumns) params() else actionParams(0)
        private val secondParams = if (equalColumns) params() else actionParams(0).apply { topMargin = gap }

        init {
            orientation = VERTICAL
            isBaselineAligned = false
            addView(first, firstParams)
            addView(second, secondParams)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val wide = MeasureSpec.getSize(widthMeasureSpec) >= horizontalMin
            val direction = if (wide) HORIZONTAL else VERTICAL
            if (orientation != direction) {
                orientation = direction
                if (equalColumns) {
                    val width = if (wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                    val weight = if (wide) 1f else 0f
                    firstParams.width = width
                    secondParams.width = width
                    firstParams.weight = weight
                    secondParams.weight = weight
                }
                secondParams.marginStart = if (wide) gap else 0
                secondParams.marginEnd = 0
                secondParams.topMargin = if (!wide && !equalColumns) gap else 0
                secondParams.resolveLayoutDirection(layoutDirection)
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
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
