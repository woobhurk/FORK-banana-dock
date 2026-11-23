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

    /**
     * 被「点外面」关掉后，片刻之内的 show() 一律忽略。
     *
     * 原因：弹窗带 FLAG_NOT_TOUCH_MODAL，同一根手指的 down 会让弹窗收到
     * ACTION_OUTSIDE 而关闭，up 却照样落到 Dock 图标上再触发一次 show()，
     * 不压制的话弹窗会立刻重新弹出来 —— 表现为「点了外面 / 再点图标关不掉」。
     *
     * ★ v1.5.8：500ms → **120ms**。
     *   500ms 太长 —— 用户「快速连点」Dock 按钮时（两次点击间隔通常 200~400ms），
     *   第二次点击正好落在抑制窗口里，被整条忽略 → 表现为「点了没反应」。
     *   要压制的其实只是**同一根手指的 down→up**（一般 100ms 内），
     *   120ms 足够吞掉它，又不会误伤正常的连点。
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

    /** 同一次打开共享锚点与可用区域，避免窗口靠边规则和内容缩放各算一套。 */
    internal fun placement(context: Context, anchor: View?): Placement {
        val dm = context.resources.displayMetrics
        val bottom = Prefs.dockAtBottom(context)
        val gravity = if (bottom) Gravity.BOTTOM else Gravity.TOP
        val edge = dp(context, 8)
        if (anchor == null || anchor.width == 0 || anchor.height == 0) {
            val offset = Prefs.dockHeightPx(context) + Prefs.dockMarginPx(context) + dp(context, 24)
            return Placement(context, gravity or Gravity.CENTER_HORIZONTAL, offset, null, edge)
        }
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val gap = dp(context, 10)
        val offset = if (bottom) dm.heightPixels - loc[1] + gap else loc[1] + anchor.height + gap
        return Placement(context, gravity or Gravity.START, offset.coerceAtLeast(0), loc[0] + anchor.width / 2, edge)
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
