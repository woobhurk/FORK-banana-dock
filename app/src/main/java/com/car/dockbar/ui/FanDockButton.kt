package com.car.dockbar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat
import com.car.dockbar.R

/** 风量组合的中间触控区；整组图形与角标一起缩放，不侵占两侧箭头。 */
internal class FanDockButton(context: Context, private val iconPx: Int) : View(context) {
    private val fan = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_dock_fan)).mutate().apply {
        setBounds(0, 0, 64, 64)
    }
    private val autoMark = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_dock_fan_auto_mark)).mutate().apply {
        setBounds(0, 0, 64, 64)
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 18f
        typeface = AppFont.bold(context)
    }
    private var shownLevel = "0"
    private var auto = false

    init {
        setBackgroundResource(R.drawable.bg_item_press)
        isClickable = true
        isFocusable = false
    }

    fun render(level: Int, auto: Boolean, darkIcons: Boolean) {
        val stopped = !auto && level <= 0
        this.auto = auto
        shownLevel = if (auto && level !in 1..5) "–" else level.coerceIn(0, 9).toString()
        val foreground = DockBarPalette.foreground(darkIcons)
        fan.setTint(if (stopped) DockBarPalette.offColor(darkIcons) else foreground)
        // 深浅底使用评审稿的两种蓝色，保持小字在两种背景上的对比度。
        autoMark.setTint(if (darkIcons) 0xFF125BA8.toInt() else 0xFF79B8FF.toInt())
        number.color = foreground
        contentDescription = when {
            stopped -> "风量关闭，0 档"
            auto && level !in 1..5 -> "自动风量，档位未知"
            auto -> "自动风量 ${shownLevel} 档"
            else -> "手动风量 ${shownLevel} 档"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(iconPx, width, height).toFloat()
        if (size <= 0f) return
        val saved = canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 64f, size / 64f)
        fan.draw(canvas)
        if (auto) autoMark.draw(canvas)
        canvas.drawText(shownLevel, 55f, 55f, number)
        canvas.restoreToCount(saved)
    }
}
