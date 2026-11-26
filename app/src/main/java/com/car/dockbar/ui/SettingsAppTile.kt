package com.car.dockbar.ui

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R

/** 设置页共用的应用卡片，点击和移除后的操作由各页面处理。 */
internal class SettingsAppTile(context: Context, caption: String?) : FrameLayout(context) {
    companion object {
        const val HEIGHT_DP = 98
    }

    val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val label = TextView(context)
    private var removeButton: Button? = null

    init {
        setBackgroundResource(R.drawable.bg_settings_choice)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val iconSize = dp(42)
        val secondary = ContextCompat.getColor(context, R.color.settings_secondary)
        if (caption == null) {
            AppDrawerSlotStyle.emptyIcon(icon, iconSize, secondary)
        }
        body.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
        AppDrawerSlotStyle.label(label, caption ?: "空位",
            if (caption == null) secondary else ContextCompat.getColor(context, R.color.settings_text))
        body.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
    }

    fun addRemoveButton(description: String, onRemove: () -> Unit) {
        val button = Button(context).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.settings_error))
            includeFontPadding = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setBackgroundResource(R.drawable.bg_settings_nav_idle)
            setPadding(0, 0, 0, 0)
            contentDescription = description
            setOnClickListener { onRemove() }
        }
        removeButton = button
        addView(button, LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END))
    }

    fun updateLabel(caption: String, removeDescription: String) {
        label.text = caption
        removeButton?.contentDescription = removeDescription
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
