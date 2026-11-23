// ┌──────────────────────────────────────────────────────────────┐
// │ ★ 本弹窗的可见元素编号（纯注释，不影响任何运行逻辑）★           │
// │  P20 起 —— 风向弹窗                                    │
// └──────────────────────────────────────────────────────────────┘

package com.car.dockbar.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.car.dockbar.R
import com.car.dockbar.util.Logx
import com.car.dockbar.vehicle.VehicleController

/**
 * 风向独立开关小弹窗 —— 点击 Dock 上的「风向」按钮弹出。
 *
 * 与 [HvacControlPopup] 同一套 WindowManager 方案，但更小、更轻：
 *  - 三个独立开关：吹脸 / 吹脚 / 吹前挡，每个单独开 / 关（位掩码组合，至少保留一项）
 *  - 按钮仅展示对应风向图标，与 Dock 主栏共用矢量资源。
 *  - 5 秒自动消失；点击面板外部立即关闭（统一规范 [DockPopupChrome]，无 × 按钮）
 *  - 位置贴着触发它的「风向」图标
 *  - 订阅 [VehicleController] 状态，开关实时反映当前风向组合
 */
class AirDirectionPopup(private val ctx: Context) {

    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }

    private val AUTO_DISMISS_MS = 5000L

    private var btnFace: DockPopupIconButton? = null
    private var btnFoot: DockPopupIconButton? = null
    private var btnDefrost: DockPopupIconButton? = null

    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean
        get() = content != null

    private val listener: (VehicleController.State) -> Unit = { st ->
        main.post { render(st.airDirection) }
    }

    /**
     * @param anchor 触发弹窗的 Dock「风向」图标，用于把面板贴在它附近
     */
    fun show(anchor: View? = null) {
        main.post {
            // ★★ v1.4.67：抑制「同一手势的收尾」。
            //   弹窗带 FLAG_NOT_TOUCH_MODAL：点 Dock 图标时，down 让弹窗收到
            //   ACTION_OUTSIDE 而关闭，up 却照样落到图标上再触发一次 show()。
            //   不压制的话 = 弹窗关了又立刻弹回来，表现为「再点图标关不掉」。
            //   （原先只有 HvacControlPopup 做了这个压制，其余弹窗都漏了。）
            if (DockPopupChrome.shouldSuppressShow()) return@post
            if (content != null) {
                resetTimer()
                return@post
            }
            runCatching { buildAndShow(anchor) }
                .onFailure {
                    Logx.e("AirDirection：添加失败", it)
                    content = null
                }
        }
    }

    private fun buildAndShow(anchor: View?) {
        // 标题与三个竖排图标按钮，未放大时内容总高约 200dp。
        val placement = DockPopupChrome.placement(ctx, anchor)
        scale = placement.scale(2f, widthDp = 90, heightDp = 200)
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val card = DockPopupStyle.card(ctx, scale, "风向")

        // 三个独立开关
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnFace = makeIconBtn(R.drawable.ic_blowing_face, "吹脸").apply {
            setOnClickListener { toggle(AirFlowDrawable.FACE) }
        }
        btnFoot = makeIconBtn(R.drawable.ic_blowing_foot, "吹脚").apply {
            setOnClickListener { toggle(AirFlowDrawable.FOOT) }
        }
        btnDefrost = makeIconBtn(R.drawable.ic_blowing_window, "吹前挡").apply {
            setOnClickListener { toggle(AirFlowDrawable.DEFROST) }
        }
        // 顺序：① 吹前挡 ② 吹脸 ③ 吹脚
        column.addView(btnDefrost, quickParams())
        column.addView(btnFace, quickParams())
        column.addView(btnFoot, quickParams())
        card.addView(column)

        // 窗口参数：统一规范 —— 贴着触发图标（DockPopupChrome 负责位置 + flags）
        val p = placement.params(card)

        content = card
        params = p
        // 点击面板外部（FLAG_WATCH_OUTSIDE_TOUCH）立即关闭（统一规范）
        card.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { hide() } }
        card.alpha = 0f
        wm?.addView(card, p)
        card.animate().alpha(1f).setDuration(180L).start()

        VehicleController.addListener(listener)
        render(VehicleController.state.airDirection)
        resetTimer()
        Logx.d("AirDirection：显示（3 个独立风向开关）")
    }

    /** 单独开 / 关某个风向位（异或）；全部关掉时忽略，至少保留一项 */
    private fun toggle(bit: Int) {
        val cur = VehicleController.state.airDirection
        val next = cur xor bit
        if (next == 0) {
            // 不允许全部关闭：保持当前状态，仅把倒计时重新计满
            resetTimer()
            return
        }
        VehicleController.setAirDirection(ctx, next)
        resetTimer()
    }

    private fun render(bits: Int) {
        btnFace?.setActive(bits and AirFlowDrawable.FACE != 0)
        btnFoot?.setActive(bits and AirFlowDrawable.FOOT != 0)
        btnDefrost?.setActive(bits and AirFlowDrawable.DEFROST != 0)
    }
    fun hide() {
        main.removeCallbacks(dismissRunnable)
        val box = content ?: return
        // ★ 立即置空：快速连点时 isShowing 能马上反映「已关」
        content = null
        params = null
        VehicleController.removeListener(listener)
        box.animate().alpha(0f).setDuration(180L).withEndAction {
            runCatching { wm?.removeViewImmediate(box) }
                .onFailure { Logx.e("AirDirection：移除失败", it) }
            onDismissed?.invoke()
        }.start()
    }

    fun destroy() {
        main.removeCallbacks(dismissRunnable)
        val box = content
        content = null
        params = null
        if (box != null) {
            VehicleController.removeListener(listener)
            runCatching { wm?.removeViewImmediate(box) }
        }
    }

    // =====================================================================
    // 小工具（与 HvacControlPopup 保持一致）
    // =====================================================================

    private fun resetTimer() {
        main.removeCallbacks(dismissRunnable)
        main.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    private fun makeIconBtn(iconRes: Int, description: String) =
        DockPopupIconButton(ctx, scale, iconRes, description, iconScale = 1.2f).apply {
            // 保留原风向按钮的触控区，只更换状态背景。
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }


    private fun quickParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
            bottomMargin = dp(4)
        }

    private var scale = 2f

    private fun dp(value: Int): Int = DockPopupStyle.dp(ctx, scale, value)
}
