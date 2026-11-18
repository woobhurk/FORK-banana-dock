package com.car.dockbar.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R

/** 弹窗图标按钮：保留关键数值，并用选中边框补充颜色反馈。 */
internal class DockPopupIconButton(
    context: Context,
    private val scale: Float,
    iconRes: Int,
    description: String,
    caption: String = "",
    private val preserveIconColors: Boolean = false,
    /** 只缩放图标绘制，不改变按钮的触摸区域。 */
    private val iconScale: Float = 1f
): LinearLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var captionView: TextView? = null
    private var currentIcon = 0
    private var appliedTint = 0
    private var hasTint = false
    private val outline = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1).coerceAtLeast(1), DockIconView.accentColor)
        alpha = 0
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        minimumHeight = dp(40)
        background = ContextCompat.getDrawable(context, R.drawable.bg_item_press)
        foreground = outline
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = description
        addView(image, LayoutParams(dp(30), dp(30)))
        image.scaleX = iconScale
        image.scaleY = iconScale
        setIcon(iconRes)
        setCaption(caption)
        setActive(false)
    }

    fun setIcon(iconRes: Int) {
        if (currentIcon == iconRes) return
        currentIcon = iconRes
        image.setImageResource(iconRes)
    }

    fun setCaption(value: String) {
        if (value.isEmpty()) {
            captionView?.visibility = View.GONE
            return
        }
        val label = captionView ?: TextView(context).apply {
            textSize = 12f * scale
            typeface = AppFont.bold(context)
            gravity = Gravity.CENTER
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextColor(if (this@DockPopupIconButton.isSelected) DockIconView.accentColor else DockIconView.popupFg)
            addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(5)
            })
        }.also { captionView = it }
        label.visibility = View.VISIBLE
        if (label.text != value) label.text = value
    }

    fun setActive(active: Boolean) {
        isSelected = active
        outline.alpha = if (active) 255 else 0
        image.alpha = if (active) 1f else 0.8f
        val color = if (active) DockIconView.accentColor else DockIconView.popupFg
        if (!preserveIconColors && (!hasTint || appliedTint != color)) {
            image.setColorFilter(color)
            appliedTint = color
            hasTint = true
        }
        captionView?.let { if (it.currentTextColor != color) it.setTextColor(color) }
    }

    private fun dp(value: Int): Int =
        (value * scale * context.resources.displayMetrics.density).toInt()
}
