package com.car.dockbar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.car.dockbar.R
import com.car.dockbar.media.CabinVolume
import com.car.dockbar.media.VolumeEvents
import com.car.dockbar.util.Logx

/**
 * 音量面板。窗口规范和空调控制弹窗一致：贴着触发图标、点外面关闭、无关闭按钮、超时收起。
 * 媒体、导航、电话、语音各一条竖向音量条，条上显示当前数值，上下是 + / −。
 * 最多放大 1.8 倍，小屏按实际可用宽高收敛。
 */
class VolumePopup(private val ctx: Context) {

    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (content == null) return
            render()
            main.postDelayed(this, 500L)
        }
    }

    private val bars = LinkedHashMap<CabinVolume.Channel, LevelBar>()
    private val volumeListener: (CabinVolume.Channel, CabinVolume.Level, Long) -> Unit = { channel, level, sampledAt ->
        val bar = bars[channel]
        if (bar != null && !bar.dragging && sampledAt >= bar.lastUpdateAt) {
            bar.applyLevel(level, sampledAt)
        }
    }

    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean
        get() = content != null

    fun show(anchor: View? = null) {
        main.post {
            if (content != null) {
                resetTimer()
                return@post
            }
            if (DockPopupChrome.shouldSuppressShow()) return@post
            runCatching { buildAndShow(anchor) }
                .onFailure {
                    Logx.e("音量面板：添加失败", it)
                    content = null
                }
        }
    }

    fun hide() {
        main.removeCallbacks(dismissRunnable)
        main.removeCallbacks(refreshRunnable)
        VolumeEvents.removeListener(volumeListener)
        val box = content ?: return
        box.animate().alpha(0f).setDuration(180L).withEndAction {
            runCatching { wm?.removeViewImmediate(box) }
                .onFailure { Logx.e("音量面板：移除失败", it) }
            if (content === box) content = null
            onDismissed?.invoke()
        }.start()
    }

    fun destroy() {
        main.removeCallbacks(dismissRunnable)
        main.removeCallbacks(refreshRunnable)
        VolumeEvents.removeListener(volumeListener)
        val box = content
        content = null
        if (box != null) runCatching { wm?.removeViewImmediate(box) }
    }

    private fun buildAndShow(anchor: View?) {
        val placement = DockPopupChrome.placement(ctx, anchor)
        scale = placement.scale(1.8f, widthDp = 336, heightDp = 432)
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bars.clear()

        val card = DockPopupStyle.card(ctx, scale, "音量")

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        CabinVolume.channels.forEach { row.addView(column(it), colParams()) }
        card.addView(row)

        val p = placement.params(card)
        content = card
        card.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { hide() } }
        card.alpha = 0f
        wm?.addView(card, p)
        card.animate().alpha(1f).setDuration(180L).start()
        render()
        main.postDelayed(refreshRunnable, 500L)
        resetTimer()
        Logx.d("音量面板：显示（媒体 group=0，导航 group=1，电话 group=2，语音 group=3）")
        VolumeEvents.addListener(volumeListener)
    }

    private fun column(channel: CabinVolume.Channel): LinearLayout {
        val trackWidth = dp(56)
        val iconSize = DockPopupStyle.iconSizePx(ctx, trackWidth)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(ImageView(ctx).apply {
            setImageResource(iconFor(channel))
            setColorFilter(DockPopupStyle.text)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "${channel.title}音量"
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            }
        })
        box.addView(stepBtn("+") {
            CabinVolume.step(ctx, channel, 1)
            render()
        }.apply { contentDescription = "${channel.title}音量增加" })
        val bar = LevelBar(ctx)
        bar.onSeek = { shown ->
            bar.value = CabinVolume.write(ctx, channel, shown)
            bar.lastUpdateAt = SystemClock.elapsedRealtimeNanos()
            resetTimer()
        }
        bars[channel] = bar
        box.addView(bar, LinearLayout.LayoutParams(trackWidth, dp(148)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })
        box.addView(stepBtn("−") {
            CabinVolume.step(ctx, channel, -1)
            render()
        }.apply { contentDescription = "${channel.title}音量降低" })
        return box
    }
    private fun iconFor(channel: CabinVolume.Channel): Int = when (channel) {
        CabinVolume.MEDIA -> R.drawable.ic_popup_media
        CabinVolume.NAVI -> R.drawable.ic_popup_navigation
        CabinVolume.PHONE -> R.drawable.ic_popup_phone
        CabinVolume.VOICE -> R.drawable.ic_popup_voice
        else -> R.drawable.ic_volume
    }

    private fun stepBtn(label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        setTextColor(DockPopupStyle.text)
        textSize = sp(22f)
        typeface = AppFont.bold(ctx)
        gravity = Gravity.CENTER
        background = DockPopupStyle.controlBackground(ctx, scale)
        minWidth = dp(56)
        minHeight = dp(40)
        isClickable = true
        setOnClickListener {
            onClick()
            resetTimer()
        }
    }

    private fun render() {
        for ((channel, bar) in bars) {
            if (bar.dragging) continue
            val sampledAt = SystemClock.elapsedRealtimeNanos()
            bar.applyLevel(CabinVolume.read(ctx, channel), sampledAt)
        }
    }

    private fun resetTimer() {
        main.removeCallbacks(dismissRunnable)
        main.postDelayed(dismissRunnable, 6000L)
    }

    private fun colParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        }

    private var scale = 1.8f

    private fun dp(v: Int): Int = DockPopupStyle.dp(ctx, scale, v)

    private fun sp(v: Float): Float = v * scale

    private inner class LevelBar(context: Context) : View(context) {
        var onSeek: ((Int) -> Unit)? = null
        var dragging = false
        var lastUpdateAt = Long.MIN_VALUE

        fun applyLevel(level: CabinVolume.Level, sampledAt: Long) {
            lastUpdateAt = sampledAt
            if (max != level.max) max = level.max
            if (value != level.value) value = level.value
        }

        init {
            isClickable = true
        }

        var max: Int = 1
            set(v) {
                field = v.coerceAtLeast(1)
                invalidate()
            }
        var value: Int = 0
            set(v) {
                field = v
                invalidate()
            }

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.settings_field)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // 与空轨混色，保证浅色数字在不同强调色下均可读。
            color = ColorUtils.blendARGB(track.color, DockPopupStyle.accent(context), 0.32f)
        }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DockPopupStyle.text
            textAlign = Paint.Align.CENTER
            typeface = AppFont.bold(context)
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = DockPopupStyle.controlRadius(context, scale).toFloat()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, track)
            val span = (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            if (span > 0f) {
                val top = height * (1f - span)
                rect.set(0f, top, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(rect, r, r, fill)
            }
            num.textSize = dp(16).toFloat()
            val y = height / 2f - (num.descent() + num.ascent()) / 2f
            canvas.drawText(value.toString(), width / 2f, y, num)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    seekTo(event.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    seekTo(event.y)
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun seekTo(y: Float) {
            val h = height.coerceAtLeast(1)
            val span = (1f - y / h).coerceIn(0f, 1f)
            val shown = (span * max + 0.5f).toInt().coerceIn(0, max)
            if (shown == value && dragging) return
            value = shown
            onSeek?.invoke(shown)
        }
    }
}
