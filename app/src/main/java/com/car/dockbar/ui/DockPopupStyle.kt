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

/** Dock 弹窗共用青绿深色外壳、标题和控件状态，不随主栏反色。 */
internal object DockPopupStyle {
    val text = 0xFFF2F4F8.toInt()
    val secondary = 0xFFA6AEBB.toInt()
    val unknown = 0xFFEAC58A.toInt()

    fun dp(context: Context, scale: Float, value: Int): Int =
        (value * scale * context.resources.displayMetrics.density).toInt()

    fun iconSizePx(context: Context, availablePx: Int): Int =
        DockMetrics.iconSizePx(context).coerceAtMost(availablePx.coerceAtLeast(1))

    fun controlRadius(context: Context, scale: Float): Int = dp(context, minOf(scale, 1f), 8)

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
            textSize = 22f * minOf(scale, 1f)
            typeface = AppFont.bold(context)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, scale, 8)
        })
    }

    fun accent(context: Context): Int {
        val fallback = ContextCompat.getColor(context, R.color.settings_accent)
        val color = ColorUtils.setAlphaComponent(Prefs.accentColor(context, fallback), 255)
        val surface = ContextCompat.getColor(context, R.color.settings_card)
        return if (ColorUtils.calculateContrast(color, surface) >= 3.0) color else fallback
    }

    fun selectedText(accent: Int): Int = ColorUtils.blendARGB(accent, Color.WHITE, 0.35f)

    fun selectedSurface(context: Context, scale: Float, accent: Int): GradientDrawable =
        shape(controlRadius(context, scale), ColorUtils.setAlphaComponent(accent, 22)).apply {
            setStroke(dp(context, 1f, 1).coerceAtLeast(1), accent)
        }

    fun controlBackground(context: Context, scale: Float, selectionAccent: Int? = accent(context)): RippleDrawable {
        val radius = controlRadius(context, scale)
        val idle = shape(radius, ContextCompat.getColor(context, R.color.settings_field)).apply {
            setStroke(dp(context, 1f, 1).coerceAtLeast(1), ContextCompat.getColor(context, R.color.settings_outline))
        }
        val content = if (selectionAccent == null) idle else StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selectedSurface(context, scale, selectionAccent))
            addState(intArrayOf(), idle)
        }
        val ripple = ContextCompat.getColor(context, R.color.settings_ripple)
        return RippleDrawable(ColorStateList.valueOf(ripple), content, shape(radius, Color.WHITE))
    }

    private fun shape(radius: Int, fill: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
    }
}
