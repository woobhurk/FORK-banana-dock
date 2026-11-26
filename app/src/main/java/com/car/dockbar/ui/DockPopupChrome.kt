package com.car.dockbar.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.car.dockbar.util.Prefs

/**
 * Dock 弹窗统一规范 —— 所有从 Dock 按钮弹出的窗口（现在的和将来的）都必须遵守：
 *
 *  1. **贴近触发图标**：弹窗位置贴着触发它的那个 Dock 图标
 *     （Dock 在底部 → 弹窗浮在图标上方；水平方向以图标中心对准弹窗中心，
 *     并收敛在屏幕范围内，不越界）。
 *  2. **点外面关闭**：点击弹窗以外的任何区域立即关闭
 *     （FLAG_WATCH_OUTSIDE_TOUCH + ACTION_OUTSIDE）。
 *  3. **不放 ×**：弹窗不提供右上角关闭按钮；关闭途径只有
 *     「点弹窗外面 / 再点一次触发图标 / 超时自动收起」。
 */
object DockPopupChrome {

    private const val FADE_DURATION_MS = 180L

    /**
     * 窗外点击的 ACTION_OUTSIDE 会关闭弹窗，同一手势的抬手仍可能触发 Dock 图标。
     * 短时抑制再次打开，避免弹窗关闭后立即重弹。
     */
    private const val SUPPRESS_MS = 120L

    private var lastOutsideDismissAt = 0L

    /** 本次 show() 是否应该忽略（刚被点外面关掉，处于同一手势的收尾阶段） */
    fun shouldSuppressShow(): Boolean =
        SystemClock.elapsedRealtime() - lastOutsideDismissAt < SUPPRESS_MS

    /**
     * 弹窗内容 View 的 setOnTouchListener 里调用：
     * 是窗外点击（ACTION_OUTSIDE）就记抑制戳并执行关闭，返回 true。
     */
    fun onOutsideTouch(ev: MotionEvent, close: () -> Unit): Boolean {
        if (ev.action == MotionEvent.ACTION_OUTSIDE) {
            noteOutsideDismiss()
            close()
            return true
        }
        return false
    }

    private fun noteOutsideDismiss() {
        lastOutsideDismissAt = SystemClock.elapsedRealtime()
    }

    internal fun fadeIn(view: View) {
        view.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
    }

    internal fun fadeOut(view: View, onEnd: Runnable) {
        view.animate().alpha(0f).setDuration(FADE_DURATION_MS).withEndAction(onEnd).start()
    }

    /** 关闭时立即解除交互，重开或销毁时可提前移除正在淡出的窗口。 */
    internal class AnimatedDismissal(
        private val manager: WindowManager,
        private val onRemoveFailure: (Throwable) -> Unit
    ) {
        private var closingView: View? = null

        fun dismiss(view: View, animated: Boolean) {
            finish()
            if (!animated || !releaseInput(view)) {
                view.animate().cancel()
                remove(view)
                return
            }

            closingView = view
            fadeOut(view) {
                if (closingView === view) {
                    closingView = null
                    remove(view)
                }
            }
        }

        fun finish() {
            val view = closingView ?: return
            closingView = null
            view.animate().cancel()
            remove(view)
        }

        private fun releaseInput(view: View): Boolean {
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
            view.setOnTouchListener(null)
            view.cancelPendingInputEvents()
            return runCatching { manager.updateViewLayout(view, params) }.isSuccess
        }

        private fun remove(view: View) {
            runCatching { manager.removeViewImmediate(view) }.onFailure(onRemoveFailure)
        }
    }

    /** 同一次打开共享锚点与可用区域，避免窗口靠边规则和内容缩放各算一套。 */
    internal fun placement(context: Context, anchor: View?): Placement {
        val dm = context.resources.displayMetrics
        val edge = dp(context, 8)
        if (anchor == null || anchor.width == 0 || anchor.height == 0) {
            val offset = Prefs.dockHeightPx(context) + Prefs.dockMarginPx(context) + dp(context, 24)
            return Placement(context, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, offset, null, edge)
        }
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val gap = dp(context, 10)
        val offset = dm.heightPixels - loc[1] + gap
        return Placement(context, Gravity.BOTTOM or Gravity.START, offset.coerceAtLeast(0), loc[0] + anchor.width / 2, edge)
    }

    internal class Placement(
        context: Context,
        private val gravity: Int,
        private val offset: Int,
        private val anchorCenterX: Int?,
        private val edge: Int
    ) {
        private val metrics = context.resources.displayMetrics
        private val screenWidth = metrics.widthPixels
        val availableWidth = (screenWidth - 2 * edge).coerceAtLeast(1)
        private val availableHeight = (metrics.heightPixels - offset - edge).coerceAtLeast(1)

        fun scale(maximum: Float, widthDp: Int, heightDp: Int): Float = minOf(
            maximum,
            availableWidth / (widthDp * metrics.density),
            availableHeight / (heightDp * metrics.density)
        )

        fun params(content: View, widthPx: Int = WindowManager.LayoutParams.WRAP_CONTENT): WindowManager.LayoutParams {
            val w = if (widthPx == WindowManager.LayoutParams.WRAP_CONTENT) naturalWidth(content) else widthPx
            val width = if (w > availableWidth) availableWidth else widthPx
            val p = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = gravity
            p.y = offset
            if (anchorCenterX != null) {
                val fitWidth = w.coerceIn(0, availableWidth)
                p.x = (anchorCenterX - fitWidth / 2)
                    .coerceIn(edge, (screenWidth - fitWidth - edge).coerceAtLeast(edge))
            }
            return p
        }
    }

    /** 预估 wrap_content 弹窗的自然宽度（拿不到返回 0，调用方退回贴图标左侧） */
    private fun naturalWidth(content: View?): Int {
        if (content == null) return 0
        return runCatching {
            content.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            content.measuredWidth
        }.getOrDefault(0)
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
