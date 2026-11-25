package com.car.dockbar.ui

import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.car.dockbar.R
import com.car.dockbar.dock.DockMetrics
import com.car.dockbar.model.AppDrawerApp
import com.car.dockbar.model.AppDrawerApps
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.Logx

/**
 * 2×3 应用快捷面板，作为独立浮窗贴近 Dock 显示。
 *
 * 遵循 [DockPopupChrome] 统一规范：
 *  1. 位置贴着触发它的「快捷应用」Dock 图标（[show] 传入锚点 View）；
 *  2. 点击面板外部立即关闭；
 *  3. 没有右上角 × 关闭按钮。
 */
class AppDrawerPopup(
    private val context: Context,
    private val onInteraction: () -> Unit
) {
    private companion object {
        const val WIDTH_DP = 312
        const val CELL_HEIGHT_DP = 64
        const val CELL_VERTICAL_MARGIN_DP = 2
        const val CELL_ICON_SPACE_DP = 30
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dismissal = DockPopupChrome.AnimatedDismissal(windowManager) {
        Logx.e("快捷应用面板：移除失败", it)
    }
    private var content: LinearLayout? = null
    var isShowing: Boolean = false
        private set

    /** 6 个槽位当前放的应用（null = 空位） */
    private val slotApps = MutableList<AppDrawerApp?>(AppDrawerApps.MAX_APPS) { null }
    /** 6 个槽位对应的 View，用于换算手指落在哪个槽 */
    private val slotViews = arrayOfNulls<View>(AppDrawerApps.MAX_APPS)
    /** 2×3 网格的宿主容器（换位是局部重建，不重建整个窗口） */
    private var gridHost: LinearLayout? = null
    private var dragFrom = -1
    private var dragTo = -1

    /** 弹窗关闭后通知 Dock 恢复临时显示计时。 */
    var onDismissed: (() -> Unit)? = null

    /**
     * @param anchor 触发面板的 Dock 图标，用于把面板贴在它附近；为空时退回 Dock 上方居中
     */
    fun show(anchor: View? = null) {
        if (DockPopupChrome.shouldSuppressShow()) return
        if (isShowing) {
            dismiss()
            return
        }
        dismissal.finish()
        val placement = DockPopupChrome.placement(context, anchor)
        scale = placement.scale(2f, widthDp = WIDTH_DP, heightDp = 190)
        val view = buildContent()
        // 点面板外面立即关闭（规范第 2 条）
        view.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { dismiss() } }
        val params = placement.params(view, dp(WIDTH_DP))
        runCatching {
            view.alpha = 0f
            windowManager.addView(view, params)
            content = view
            isShowing = true
            DockPopupChrome.fadeIn(view)
        }.onFailure {
            Logx.e("应用面板显示失败：${it.javaClass.simpleName} ${it.message}", it)
        }
    }

    fun dismiss() = dismiss(animated = true)

    internal fun destroy() {
        dismissal.finish()
        dismiss(animated = false)
    }

    private fun dismiss(animated: Boolean) {
        val view = content ?: return
        content = null
        isShowing = false
        dragFrom = -1
        dragTo = -1
        gridHost = null
        for (i in slotViews.indices) slotViews[i] = null
        dismissal.dismiss(view, animated)
        onDismissed?.invoke()
    }

    private fun buildContent(): LinearLayout {
        val panel = DockPopupStyle.card(context, scale, "快捷应用")

        val selected = AppDrawerApps.loadSlots(context)
        for (i in 0 until AppDrawerApps.MAX_APPS) slotApps[i] = selected.getOrNull(i)

        gridHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(gridHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        renderGrid()
        return panel
    }

    /** 重画 2×3 网格（换位是局部重建，不重建整个窗口） */
    private fun renderGrid() {
        val host = gridHost ?: return
        host.removeAllViews()
        val cellHeight = dp(CELL_HEIGHT_DP)
        val verticalMargin = dp(CELL_VERTICAL_MARGIN_DP)
        val iconSize = DockMetrics.appIconSizePx(DockMetrics.iconSizePx(context)).coerceAtMost(dp(CELL_ICON_SPACE_DP))
        repeat(2) { rowIndex ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            repeat(3) { columnIndex ->
                val index = rowIndex * 3 + columnIndex
                row.addView(appCell(index, iconSize), LinearLayout.LayoutParams(0, cellHeight, 1f).apply {
                    setMargins(dp(3), verticalMargin, dp(3), verticalMargin)
                })
            }
            host.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cellHeight + 2 * verticalMargin
            ))
        }
    }

    /** 长按后拖向目标槽，松手时交换两个槽位并保存。 */
    private fun setupSlotDrag(v: View, index: Int) {
        DockDragLongPress.attach(v, onDragTouch = { ev ->
            if (dragFrom == index) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val hit = slotAt(ev.rawX, ev.rawY)
                        if (hit != -1 && hit != dragTo) {
                            dragTo = hit
                            highlightDrag()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishDrag()
                }
            }
        }) { _, _ ->
            if (dragFrom == -1) {
                dragFrom = index
                dragTo = index
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                highlightDrag()
                Logx.d("快捷应用：长按槽位 $index（${slotApps[index]?.label}），进入拖动")
            }
        }
    }

    /** 手指当前落在第几个槽（-1 = 不在网格内） */
    private fun slotAt(rawX: Float, rawY: Float): Int {
        val loc = IntArray(2)
        for (i in 0 until AppDrawerApps.MAX_APPS) {
            val v = slotViews[i] ?: continue
            v.getLocationOnScreen(loc)
            val x = loc[0]; val y = loc[1]
            if (rawX >= x && rawX <= x + v.width && rawY >= y && rawY <= y + v.height) return i
        }
        return -1
    }

    /** 拖动时区分起点和落点，空槽也可高亮。 */
    private fun highlightDrag() {
        for (i in 0 until AppDrawerApps.MAX_APPS) {
            val v = slotViews[i] ?: continue
            AppDrawerSlotStyle.updateDrag(v, i, dragFrom, dragTo)
        }
    }

    /**
     * 松手：**两个槽对调**并保存。
     * 只动 from / to 这两个位置，其它槽原样不动（不再整体移位）。
     */
    private fun finishDrag() {
        val from = dragFrom
        val to = dragTo
        dragFrom = -1
        dragTo = -1
        if (from in 0 until AppDrawerApps.MAX_APPS && to in 0 until AppDrawerApps.MAX_APPS && from != to) {
            val a = slotApps[from]
            val b = slotApps[to]
            slotApps[from] = b
            slotApps[to] = a
            AppDrawerApps.saveSlots(context, slotApps)
            val whoA = a?.label ?: "空位"
            val whoB = b?.label ?: "空位"
            Logx.d("快捷应用：槽位 $from（$whoA）↔ 槽位 $to（$whoB）互换，已保存")
        }
        renderGrid()
    }

    /** @param index 槽位 0..5（[slotApps] 里为 null 就是空位） */
    private fun appCell(index: Int, iconSize: Int): View {
        val app = slotApps.getOrNull(index)
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(4), dp(3), dp(3))
            background = DockPopupStyle.controlBackground(context, scale, selectionAccent = null)
            AppDrawerSlotStyle.applyDragOutline(this, DockPopupStyle.accent(context), scale)
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val title = TextView(context)
        AppDrawerSlotStyle.label(title, app?.label ?: "空位",
            if (app == null) DockPopupStyle.secondary else DockPopupStyle.text, scale)
        if (app == null) {
            AppDrawerSlotStyle.emptyIcon(icon, iconSize, DockPopupStyle.secondary)
        } else {
            runCatching { icon.setImageDrawable(context.packageManager.getApplicationIcon(app.packageName)) }
                .onFailure { icon.setImageResource(R.drawable.ic_car) }
            cell.setOnClickListener {
                // ★ 拖动刚结束的那一次 UP 已被 onTouch 消费，不会走到这里
                dismiss()
                onInteraction()
                IntentLauncher.launch(
                    context,
                    app.label,
                    IntentLauncher.Target(pkg = app.packageName, cls = app.activityName),
                    emptyList()
                )
            }
            setupSlotDrag(cell, index)
        }
        cell.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
        cell.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        slotViews[index] = cell
        return cell
    }

    private var scale = 2f

    private fun dp(value: Int): Int = DockPopupStyle.dp(context, scale, value).coerceAtLeast(1)
}
