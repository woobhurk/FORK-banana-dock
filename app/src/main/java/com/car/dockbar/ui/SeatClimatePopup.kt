package com.car.dockbar.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.car.dockbar.R
import com.car.dockbar.util.Logx
import com.car.dockbar.vehicle.VehicleController

/** 主副驾各一列：加热三至一档、关闭、通风一至三档；点击直接选择同侧档位。 */
class SeatClimatePopup(private val ctx: Context) {
    private class Choice(
        val driver: Boolean,
        val heating: Boolean,
        val level: Int,
        val root: FrameLayout,
        val image: ImageView
    )

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }
    private val choices = ArrayList<Choice>(14)
    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private var destroyed = false
    private var scale = 2f

    var onDismissed: (() -> Unit)? = null
    val isShowing: Boolean get() = content != null

    private val listener: (VehicleController.State) -> Unit = { state ->
        if (content != null) render(state)
    }

    fun show(anchor: View? = null) {
        main.post {
            if (destroyed || DockPopupChrome.shouldSuppressShow()) return@post
            if (content != null) {
                resetTimer()
                return@post
            }
            runCatching { buildAndShow(anchor) }.onFailure {
                hide()
                choices.clear()
                Logx.e("座椅加热／通风弹窗：添加失败", it)
            }
        }
    }

    private fun buildAndShow(anchor: View?) {
        val placement = DockPopupChrome.placement(ctx, anchor)
        scale = placement.scale(2f, widthDp = 152, heightDp = 408)
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        choices.clear()
        val card = DockPopupStyle.card(ctx, scale, "座椅加热／通风")
        val columns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        columns.addView(column(true), columnParams())
        columns.addView(column(false), columnParams())
        card.addView(columns)
        card.setOnTouchListener { _, event -> DockPopupChrome.onOutsideTouch(event) { hide() } }
        val params = placement.params(card)
        card.alpha = 0f
        manager.addView(card, params)
        content = card
        card.animate().alpha(1f).setDuration(180L).start()
        VehicleController.addListener(listener)
        VehicleController.setSeatClimatePopupVisible(ctx, true)
        resetTimer()
    }

    private fun column(driver: Boolean): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val side = if (driver) "主驾" else "副驾"
        addView(label(side, 13f), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
        for (row in ICONS.indices) {
            val heating = row < 3
            val level = when {
                row < 3 -> 3 - row
                row == 3 -> 0
                else -> row - 3
            }
            val image = ImageView(ctx).apply {
                setImageResource(ICONS[row])
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val button = FrameLayout(ctx).apply {
                background = DockPopupStyle.controlBackground(ctx, scale)
                isClickable = true
                isFocusable = true
                contentDescription = if (level == 0) "$side 关闭加热和通风"
                    else "$side ${if (heating) "加热" else "通风"} $level 档"
                addView(image, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
                setOnClickListener {
                    VehicleController.setSeatComfortLevel(ctx, driver, heating, level)
                    resetTimer()
                }
            }
            choices.add(Choice(driver, heating, level, button, image))
            addView(button, LinearLayout.LayoutParams(dp(56), dp(44)).apply {
                bottomMargin = dp(4)
            })
        }
    }

    private fun label(text: String, size: Float) = TextView(ctx).apply {
        this.text = text
        setTextColor(DockPopupStyle.secondary)
        textSize = size * scale
        typeface = AppFont.bold(ctx)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private fun columnParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun render(state: VehicleController.State) {
        for (choice in choices) {
            val heat = if (choice.driver) state.seatHeat else state.seatHeatPassenger
            val vent = if (choice.driver) state.seatVent else state.seatVentPassenger
            val ventVisible = state.seatVentVisible(choice.driver)
            choice.root.visibility = if (choice.level == 0 || choice.heating || ventVisible) View.VISIBLE else View.GONE
            if (choice.level == 0) {
                val description = if (choice.driver) {
                    if (ventVisible) "主驾 关闭加热和通风" else "主驾 关闭加热"
                } else {
                    if (ventVisible) "副驾 关闭加热和通风" else "副驾 关闭加热"
                }
                if (choice.root.contentDescription != description) choice.root.contentDescription = description
            }
            val selected = if (choice.level == 0) heat == 0 && (!ventVisible || vent == 0)
                else (if (choice.heating) heat else vent) == choice.level
            choice.root.isSelected = selected
            choice.image.alpha = if (selected) 1f else 0.82f
        }
    }

    fun hide() {
        // 同时取消尚未执行的显示和自动关闭任务。
        main.removeCallbacksAndMessages(null)
        val card = content ?: return
        content = null
        choices.clear()
        VehicleController.removeListener(listener)
        VehicleController.setSeatClimatePopupVisible(ctx, false)
        card.animate().cancel()
        runCatching { wm?.removeViewImmediate(card) }
            .onFailure { Logx.e("座椅加热／通风弹窗：移除失败", it) }
        onDismissed?.invoke()
    }

    fun destroy() {
        destroyed = true
        onDismissed = null
        hide()
        wm = null
    }

    private fun resetTimer() {
        main.removeCallbacks(dismissRunnable)
        main.postDelayed(dismissRunnable, 8000L)
    }

    private fun dp(value: Int): Int = DockPopupStyle.dp(ctx, scale, value)

    private companion object {
        val ICONS = intArrayOf(
            R.drawable.ic_seat_hot_03,
            R.drawable.ic_seat_hot_02,
            R.drawable.ic_seat_hot_01,
            R.drawable.ic_seat_off,
            R.drawable.ic_seat_feng_01,
            R.drawable.ic_seat_feng_02,
            R.drawable.ic_seat_feng_03
        )
    }
}
