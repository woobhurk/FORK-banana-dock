package com.car.dockbar.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.car.dockbar.R
import kotlin.math.roundToInt

/** 槽位视觉相同，点击与保存仍分别属于弹窗和图标管理页。 */
internal object AppDrawerSlotStyle {
    fun label(view: TextView, caption: String, color: Int, scale: Float = 1f) = with(view) {
        text = caption
        textSize = 13f * scale
        typeface = AppFont.regular(context)
        setTextColor(color)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun emptyIcon(view: ImageView, sizePx: Int, color: Int) = with(view) {
        setImageResource(R.drawable.ic_dock_apps)
        setColorFilter(color)
        val pad = (sizePx * 0.22f).roundToInt()
        setPadding(pad, pad, pad, pad)
        background = outline(sizePx * 0.22f, resources.displayMetrics.density, color, dashed = true)
    }

    fun applyDragOutline(view: View, accent: Int, scale: Float = 1f) {
        val unit = view.resources.displayMetrics.density * scale
        view.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), outline(8 * unit, unit, accent, dashed = true))
            addState(intArrayOf(android.R.attr.state_activated), outline(8 * unit, unit, 0xFF888F9A.toInt(), dashed = false))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    fun updateDrag(view: View, index: Int, from: Int, to: Int) {
        view.isActivated = index == from
        view.isSelected = index == to && index != from
        view.alpha = if (from < 0 || index == from || index == to) 1f else 0.52f
    }

    private fun outline(radius: Float, unit: Float, color: Int, dashed: Boolean) = GradientDrawable().apply {
        cornerRadius = radius
        val stroke = unit.roundToInt().coerceAtLeast(1)
        val gap = if (dashed) 3f * unit else 0f
        setColor(ColorUtils.setAlphaComponent(color, 22))
        setStroke(stroke, color, gap, gap)
    }
}
