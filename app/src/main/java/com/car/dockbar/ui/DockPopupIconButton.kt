package com.car.dockbar.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 弹窗图标按钮：保留关键数值，并用选中边框补充颜色反馈。 */
internal class DockPopupIconButton(
    context: Context,
    private val scale: Float,
    iconRes: Int,
    description: String,
    caption: String = "",
    private val preserveIconColors: Boolean = false,
    /** 只缩放图标绘制，不改变按钮的触摸区域。 */
    private val iconScale: Float = 1f,
    private val readOnly: Boolean = false
): LinearLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var captionView: TextView? = null
    private var currentIcon = 0
    private var appliedTint: Int? = null
    private val accent = DockPopupStyle.accent(context)
    private val selectedColor = DockPopupStyle.selectedText(accent)
    private var state: Boolean? = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        minimumHeight = dp(40)
        if (!readOnly) background = DockPopupStyle.controlBackground(context, scale, accent)
        isClickable = !readOnly
        isFocusable = !readOnly
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
            setTextColor(labelColor())
            addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(5)
            })
        }.also { captionView = it }
        label.visibility = View.VISIBLE
        if (label.text != value) label.text = value
    }

    fun setActive(active: Boolean?) {
        state = active
        isSelected = active == true
        val labelColor = labelColor()
        if (!preserveIconColors) {
            val tint = if (active == null) DockPopupStyle.secondary else labelColor
            if (appliedTint != tint) {
                image.setColorFilter(tint)
                appliedTint = tint
            }
        } else {
            image.alpha = if (active == true || readOnly) 1f else 0.82f
        }
        captionView?.let { if (it.currentTextColor != labelColor) it.setTextColor(labelColor) }
    }

    private fun labelColor() = when {
        readOnly -> DockPopupStyle.text
        state == true -> selectedColor
        state == null -> DockPopupStyle.unknown
        else -> DockPopupStyle.secondary
    }

    private fun dp(value: Int): Int = DockPopupStyle.dp(context, scale, value)
}
