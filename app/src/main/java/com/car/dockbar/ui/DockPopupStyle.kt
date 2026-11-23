package com.car.dockbar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.car.dockbar.R
import com.car.dockbar.dock.DockMetrics
import com.car.dockbar.util.Prefs

/** 深色弹窗独立于主栏与设置主题；只共享外壳、标题和控件的视觉状态。 */
internal object DockPopupStyle {
    val text = 0xFFF2F4F8.toInt()
    val secondary = 0xFFA6AEBB.toInt()
    val unknown = 0xFFEAC58A.toInt()
    private val defaultAccent = 0xFF4EA1FF.toInt()

    fun dp(context: Context, scale: Float, value: Int): Int =
        (value * scale * context.resources.displayMetrics.density).toInt()

    fun iconSizePx(context: Context, availablePx: Int): Int =
        DockMetrics.iconSizePx(context).coerceAtMost(availablePx.coerceAtLeast(1))

    fun card(context: Context, scale: Float, title: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_dock_popup_card)
        val horizontal = dp(context, scale, 12)
        val vertical = dp(context, scale, 8)
        setPadding(horizontal, vertical, horizontal, vertical)
        addView(TextView(context).apply {
            text = title
            setTextColor(DockPopupStyle.text)
            textSize = 16f * minOf(scale, 2f)
            typeface = AppFont.bold(context)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, scale, 8)
        })
    }

    fun accent(context: Context): Int {
        val color = ColorUtils.setAlphaComponent(Prefs.accentColor(context), 255)
        val surface = ContextCompat.getColor(context, R.color.dock_popup_surface)
        return if (ColorUtils.calculateContrast(color, surface) >= 3.0) color else defaultAccent
    }

    fun selectedText(accent: Int): Int = ColorUtils.blendARGB(accent, Color.WHITE, 0.35f)

    fun selectedSurface(context: Context, scale: Float, accent: Int): GradientDrawable =
        shape(dp(context, scale, 6), ColorUtils.setAlphaComponent(accent, 22)).apply {
            setStroke(dp(context, scale, 1).coerceAtLeast(1), accent)
        }

    fun controlBackground(context: Context, scale: Float, selectionAccent: Int? = accent(context)): RippleDrawable {
        val mask = shape(dp(context, scale, 6), Color.WHITE)
        val content = selectionAccent?.let { accent ->
            StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_selected), selectedSurface(context, scale, accent))
            }
        }
        return RippleDrawable(ColorStateList.valueOf(0x38FFFFFF), content, mask)
    }

    private fun shape(radius: Int, fill: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
    }
}
