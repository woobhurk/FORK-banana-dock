package com.car.dockbar.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.car.dockbar.R
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.SensorBridge
import com.car.dockbar.vehicle.VehicleController

/**
 * 温度弹窗（主驾 / 副驾）—— 长按 Dock 温度组合按钮的中间值弹出（不再进系统空调页面）。
 *
 * 布局（按用户示意图）：
 *  左栏：温度档位竖排列表（15.5~28.5℃，步进 0.5），当前温度对应档位蓝字高亮，点某项直接设温；
 *  右栏 主驾：前挡加热 / 后挡加热 / 极速降温 / 极速升温 / 方向盘加热（档位）
 *  右栏 副驾：双温同步 / 车内温度 / 高于25°通风 / 低于15°加热
 *
 * 遵循 [DockPopupChrome] 统一规范，风格与 [AirDirectionPopup] 一致：
 *  bg_card 圆角卡、贴着触发图标、点弹窗外面关闭、无 × 按钮、5 秒自动消失。
 * 功能使用图标，保留温度、档位与自动阈值；选中边框补充颜色反馈。
 */
class TemperaturePopup(
    private val ctx: Context,
    /** "driver" = 主驾弹窗 / "passenger" = 副驾弹窗 */
    val zone: String
) {
    companion object {
        const val ZONE_DRIVER = "driver"
        const val ZONE_PASSENGER = "passenger"
        private val STEERING_HEAT_ICONS = intArrayOf(
            R.drawable.ic_sw_heat_off,
            R.drawable.ic_sw_heat_01,
            R.drawable.ic_sw_heat_02,
            R.drawable.ic_sw_heat_03
        )
    }

    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }
    private val AUTO_DISMISS_MS = 8000L
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshSensors()
            main.postDelayed(this, 1000L)
        }
    }

    // 左栏：标准 ScrollView 滚轮 —— 完整温度档位（15.5~28.5 步进 0.5），滚动停止后居中项生效
    // ★ v1.4.41 高亮改「固定居中半透明遮罩」：颜色不再跟字走（滑快了会错位），
    //   可视区正中盖一层半透明条，文字在遮罩下滚过
    private val tempCells = mutableListOf<Pair<Float, TextView>>()
    private var tempScroll: ScrollView? = null
    private var lastScrollY = -1
    /**
     * ★ v1.4.77：滚动停止检测（**固定间隔自检**，不被滚动事件取消）。
     *
     * 旧实现的毛病：每次 onScrollChanged 都 `removeCallbacks + postDelayed(300)`，
     *   而快速甩动时 ScrollView 会**持续惯性滚动**几百毫秒，每次滚动都把定时器往后推
     *   → settle 迟迟不触发 → 用户感觉「快速滑动不会选中」。
     *
     * 现在：定时器每 150ms 自己检查一次「scrollY 还在不在变」，
     *   还在变就再等 150ms（最多 8 轮 / 1.2s），停了就立刻 settle。
     *   滚动事件**不再取消**这个定时器，所以响应最快 150ms。
     */
    private val scrollIdleCheck: Runnable = Runnable {
        val sc = tempScroll
        if (sc != null && lastScrollY != sc.scrollY && settleWaitCount < 8) {
            settleWaitCount++
            lastScrollY = sc.scrollY
            main.postDelayed(scrollIdleCheck, 150L)
            return@Runnable
        }
        settleWaitCount = 0
        onScrollSettled()
    }

    /** ★ v1.4.77：settle 等待轮次计数 */
    private var settleWaitCount = 0
    /** 本弹窗对应温区（滚动回调里用） */
    private var isDriverZone = true
    /** 滚轮上下占位高度（让首/尾项能滚到居中） */
    private var wheelPadY = 0
    /** 用户正在滚动（滚动引发的温度同步不再反向滚列表） */
    private var userScrolling = false
    /** ★ v1.4.76：程序侧自动滚动中（避免把自己的滚动当成用户手势） */
    private var autoScrolling = false
    /** 上次渲染到的温度（判断是否需要程序侧滚动对齐） */
    private var lastRenderedTemp: Float? = null

    // 右栏 主驾
    private var btnDefrostFront: DockPopupIconButton? = null
    private var btnDefrostRear: DockPopupIconButton? = null
    private var acMaxButton: DockPopupIconButton? = null
    private var btnSteerHeat: DockPopupIconButton? = null

    // 右栏 副驾
    private var btnDualSync: DockPopupIconButton? = null
    private var tvCabinTemp: DockPopupIconButton? = null
    private var btnVentHot: DockPopupIconButton? = null
    private var btnHeatCold: DockPopupIconButton? = null

    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean
        get() = content != null

    private val listener: (VehicleController.State) -> Unit = { st ->
        main.post { render(st) }
    }

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
                    Logx.e("温度弹窗：添加失败", it)
                    content = null
                }
        }
    }

    private fun buildAndShow(anchor: View?) {
        val placement = DockPopupChrome.placement(ctx, anchor)
        SCALE = placement.scale(2.5f, widthDp = 244, heightDp = 270)
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        SensorBridge.init(ctx)
        val isDriver = zone == ZONE_DRIVER
        isDriverZone = isDriver

        val card = DockPopupStyle.card(ctx, SCALE, if (isDriver) "主驾温度" else "副驾温度")

        // 两栏主体
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        // ---------- 左栏：ScrollView 温度滚轮（29 项完整档位） ----------
        // ★ 用户要求：滑动列表，滚动停止后「居中那一格」的温度设为当前温度；
        //   最高温显示 HI、最低温显示 LO；首尾加占位，保证 HI/LO 也能滚到居中。不点击设温。
        val itemH = dp(44)
        val viewH = 5 * itemH                       // 可视区 5 格
        val padY = viewH / 2 - itemH / 2            // 上下占位：让首/尾项能滚到正中
        wheelPadY = padY
        val tempCol = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            isClickable = true
            clipToPadding = false
        }
        val tempList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tempCells.clear()
        // ★★ v1.4.76：**倒序** —— 最高温在【最上面】、最低温在【最下面】。
        //   用户反馈「lo 在最上面 hi 在最下面」是反的。改成和车机空调面板一致：
        //   往上滑 = 更热（HI 在顶），往下滑 = 更冷（LO 在底）。
        //   注意：倒序只改 tempCells 的**顺序**，每个元素携带的温度值仍然正确，
        //   所以「索引 → 滚动位置 → 温度」的换算公式全部不用动。
        var t = VehicleController.TEMP_MAX_C
        var isFirst = true
        var isLast = false
        while (t >= VehicleController.TEMP_MIN_C - 0.01f) {
            val value = t
            isLast = value - 0.5f < VehicleController.TEMP_MIN_C - 0.01f
            val item = TextView(ctx).apply {
                text = when {
                    isFirst -> "HI"      // ★ 最高温显示 HI（最上面）
                    isLast -> "LO"       // ★ 最低温显示 LO（最下面）
                    else -> fmtTemp(value)
                }
                textSize = sp(16f)
                typeface = AppFont.bold(ctx)
                gravity = Gravity.CENTER
                setTextColor(DockPopupStyle.secondary)
            }
            isFirst = false
            tempCells.add(value to item)
            tempList.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, itemH
            ))
            t -= 0.5f
        }
        tempList.setPadding(0, padY, 0, padY)
        tempCol.addView(tempList)
        tempScroll = tempCol
        // 滚动监听：静止判定（停止滚动即生效）+ 标记用户滚动中
        tempCol.viewTreeObserver.addOnScrollChangedListener {
            val sc = tempScroll ?: return@addOnScrollChangedListener
            onTempScrolled(sc.scrollY)
        }
        // ★ v1.4.41 高亮条：FrameLayout 双层 —— 底层 ScrollView，顶层固定居中的半透明遮罩。
        //   遮罩不随内容滚动，滑多快都不会错位
        val wheelWrap = android.widget.FrameLayout(ctx).apply {
            clipChildren = false
        }
        wheelWrap.addView(tempCol, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, viewH
        ))
        val mask = View(ctx).apply {
            background = DockPopupStyle.selectedSurface(ctx, SCALE, DockPopupStyle.accent(ctx))
            isClickable = false
            isFocusable = false
        }
        wheelWrap.addView(mask, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, itemH, Gravity.CENTER
        ))
        body.addView(wheelWrap, LinearLayout.LayoutParams(dp(96), viewH).apply {
            marginEnd = dp(8)
        })

        // ---------- 右栏 ----------
        val rightCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (isDriver) {
            btnDefrostFront = funcBtn(R.drawable.ic_defrost, "前挡加热") {
                VehicleController.toggleDefrostFront(ctx)
                resetTimer()
            }
            btnDefrostRear = funcBtn(
                R.drawable.ic_rear_defrost, "后挡加热"
            ) {
                VehicleController.toggleDefrostRear(ctx)
                resetTimer()
            }
            val fastCool = funcBtn(
                R.drawable.ic_popup_cool,
                "原厂急速降温",
                "MAX",
            ) {
                if (!VehicleController.toggleAcMax(ctx)) {
                    val message = if (VehicleController.acMaxOn() == null) "急速降温状态未知，未发送"
                    else "急速降温未发送，请检查车控写入开关或等待回读"
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                }
                resetTimer()
            }
            acMaxButton = fastCool
            val fastHeat = funcBtn(
                R.drawable.ic_popup_heat, "极速升温", "MAX"
            ) {
                VehicleController.fastHeat(ctx)
                resetTimer()
            }
            btnSteerHeat = funcBtn(
                R.drawable.ic_sw_heat_off,
                "方向盘加热关闭",
                "0",
                preserveIconColors = true,
            ) {
                VehicleController.steeringHeatCycle(ctx)
                resetTimer()
            }
            rightCol.addView(btnDefrostFront, funcLp())
            rightCol.addView(btnDefrostRear, funcLp())
            rightCol.addView(fastCool, funcLp())
            rightCol.addView(fastHeat, funcLp())
            rightCol.addView(btnSteerHeat, funcLp())
        } else {
            btnDualSync = funcBtn(
                R.drawable.ic_popup_sync, "双温同步", "SYNC"
            ) {
                val next = VehicleController.state.tempDualOn != true
                runCatching { VehicleController.setTempDual(ctx, next) }
                    .onFailure { Logx.e("双温同步：写车机失败", it) }
                render(VehicleController.state)
                resetTimer()
            }
            tvCabinTemp = DockPopupIconButton(
                ctx,
                SCALE,
                R.drawable.ic_popup_cabin_temp,
                "车内温度暂不可用",
                "--",
                readOnly = true
            ).apply { minimumWidth = dp(116) }
            // 自动规则保留阈值和 AUTO，避免与立即调节座椅档位混淆。
            btnVentHot = funcBtn(
                R.drawable.ic_seat_feng_03,
                "高于25℃自动通风",
                ">25℃\nAUTO",
            ) {
                Prefs.setAutoSeatVentHot(ctx, !Prefs.autoSeatVentHot(ctx))
                render(VehicleController.state)
                resetTimer()
            }
            btnHeatCold = funcBtn(
                R.drawable.ic_seat_hot_03,
                "低于15℃自动加热",
                "<15℃\nAUTO",
            ) {
                Prefs.setAutoSeatHeatCold(ctx, !Prefs.autoSeatHeatCold(ctx))
                render(VehicleController.state)
                resetTimer()
            }
            rightCol.addView(btnDualSync, funcLp())
            rightCol.addView(tvCabinTemp, funcLp())
            rightCol.addView(btnVentHot, funcLp())
            rightCol.addView(btnHeatCold, funcLp())
        }
        body.addView(rightCol, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(body)

        // 窗口参数：统一规范 —— 贴着触发图标
        val p = placement.params(card)

        content = card
        params = p
        card.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { hide() } }
        card.alpha = 0f
        wm?.addView(card, p)
        DockPopupChrome.fadeIn(card)

        VehicleController.addListener(listener)
        render(VehicleController.state)
        // 打开时滚到当前温度居中
        card.post { scrollToCurrentTemp() }
        refreshSensors()
        main.postDelayed(refreshRunnable, 1000L)
        resetTimer()
        Logx.d("温度弹窗：显示（${if (isDriver) "主驾" else "副驾"}）")
    }

    /** 右栏功能按钮（激活时字体变蓝） */
    private fun funcBtn(
        iconRes: Int,
        description: String,
        caption: String = "",
        preserveIconColors: Boolean = false,
        onClick: () -> Unit
    ): DockPopupIconButton = DockPopupIconButton(
        ctx, SCALE, iconRes, description, caption, preserveIconColors
    ).apply {
        minimumWidth = dp(116)
        setOnClickListener { onClick() }
    }
    private fun funcLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }


    private fun render(st: VehicleController.State) {
        val isDriver = zone == ZONE_DRIVER
        val current = if (isDriver) st.driverTempC else st.passengerTempC
        // 用户滚动期间保持滚轮位置，停稳后再吸附。
        if (current != lastRenderedTemp) {
            if (!userScrolling && current != null) {
                val itemH = dp(44)
                val idx = tempCells.indexOfFirst { kotlin.math.abs(it.first - current) < 0.24f }
                if (idx >= 0) {
                    // 程序更新与停稳吸附使用同一坐标和瞬时定位方式。
                    val sc = tempScroll
                    if (sc != null) {
                        val maxScroll = (tempCells.size * itemH + 2 * wheelPadY - sc.height).coerceAtLeast(0)
                        val targetY = (idx * itemH).coerceIn(0, maxScroll)
                        if (sc.scrollY != targetY) {
                            autoScrolling = true
                            sc.scrollTo(0, targetY)
                            lastScrollY = targetY
                            autoScrolling = false
                        }
                    }
                }
                highlightCentered(current)
            }
            lastRenderedTemp = current
        }
        if (isDriver) {
            btnDefrostFront?.let {
                it.contentDescription = "前挡加热${if (st.defrostFront) "开启" else "关闭"}"
                it.setActive(st.defrostFront)
            }
            btnDefrostRear?.let {
                it.contentDescription = "后挡加热${if (st.defrostRear) "开启" else "关闭"}"
                it.setActive(st.defrostRear)
            }
            acMaxButton?.let { button ->
                val on = VehicleController.acMaxOn()
                button.setActive(on)
                button.setCaption(if (on == null) "MAX\n未知" else "MAX")
                button.contentDescription = when (on) {
                    true -> "原厂急速降温已开启，点击关闭"
                    false -> "原厂急速降温已关闭，点击开启"
                    null -> "原厂急速降温状态未知"
                }
            }
            btnSteerHeat?.let {
                it.visibility = if (st.steeringHeatVisible) View.VISIBLE else View.GONE
                it.setIcon(STEERING_HEAT_ICONS[st.steeringHeat.coerceIn(0, STEERING_HEAT_ICONS.lastIndex)])
                it.setCaption(st.steeringHeat.toString())
                it.contentDescription = if (st.steeringHeat > 0) "方向盘加热 ${st.steeringHeat} 档" else "方向盘加热关闭"
                it.setActive(st.steeringHeat > 0)
            }
        } else {
            val sync = st.tempDualOn
            btnDualSync?.let {
                it.contentDescription = when (sync) {
                    true -> "双温同步开启"
                    false -> "双温同步关闭"
                    null -> "双温同步状态未知"
                }
                it.setActive(sync)
                it.setCaption(if (sync == null) "SYNC\n未知" else "SYNC")
            }
            btnVentHot?.let {
                it.visibility = if (st.seatVentVisible(driver = true)) View.VISIBLE else View.GONE
                val on = Prefs.autoSeatVentHot(ctx)
                it.contentDescription = "高于25℃自动通风${if (on) "开启" else "关闭"}"
                it.setActive(on)
            }
            btnHeatCold?.let {
                val on = Prefs.autoSeatHeatCold(ctx)
                it.contentDescription = "低于15℃自动加热${if (on) "开启" else "关闭"}"
                it.setActive(on)
            }
        }
    }

    /** 传感器类读数（车内温度 / AQI），拉取式，每秒刷新；每秒顺带重试 init（车控可能迟连） */
    private fun refreshSensors() {
        if (zone == ZONE_DRIVER) return
        SensorBridge.init(ctx)
        val cabin = SensorBridge.readFloat(SensorBridge.TEMP_INDOOR)
        val value = if (cabin == null) "--" else String.format("%.1f℃", cabin)
        tvCabinTemp?.setCaption(value)
        tvCabinTemp?.contentDescription = if (cabin == null) "车内温度暂不可用" else "车内温度 $value"
    }

    /** 档位显示：超出可调范围显示 "--" */
    private fun fmtTemp(t: Float): String =
        if (t < VehicleController.TEMP_MIN_C || t > VehicleController.TEMP_MAX_C) "--"
        else String.format("%.1f°", t)

    /** 滚动中：标记用户滚动 + 实时给遮罩下的格子染蓝（纯几何计算，跟随滚动位置不会错位）+ 静止判定 */
    private fun onTempScrolled(scrollY: Int) {
        // ★ v1.4.76：程序自己的滚动（autoScrolling）不算「用户滚动」，
        //   否则会把自己的定位当成用户手势 → 触发 settle → 又滚 → 死循环。
        if (autoScrolling) {
            lastScrollY = scrollY
            return
        }
        if (lastScrollY != scrollY) {
            lastScrollY = scrollY
            userScrolling = true
            highlightCentered(null)
            // ★ v1.4.77：**不再 removeCallbacks 取消定时器** ——
            //   取消会让惯性滚动无限推迟 settle（快速滑动不选中的根因）。
            //   定时器每 150ms 自检一次「还在不在滚」，最快 150ms 内就 settle。
            settleWaitCount = 0
            main.removeCallbacks(scrollIdleCheck)
            main.postDelayed(scrollIdleCheck, 150L)
        }
        resetTimer()
    }

    /** 滚动静止：先吸附对齐整格（文字精确停在遮罩中间），再把居中项温度设为当前 */
    private fun onScrollSettled() {
        val sc = tempScroll ?: return
        if (tempCells.isEmpty()) return
        settleWaitCount = 0
        val itemH = dp(44)
        val centerIdx = ((sc.scrollY + sc.height / 2 - wheelPadY) / itemH).toInt()
            .coerceIn(0, tempCells.size - 1)
        // 上下填充已将第 0 项居中，第 i 项的目标偏移为 i * itemH。
        val maxScroll = (tempCells.size * itemH + 2 * wheelPadY - sc.height).coerceAtLeast(0)
        val targetY = (centerIdx * itemH).coerceIn(0, maxScroll)
        Logx.d(
            "温度滚轮吸附：scrollY=${sc.scrollY} h=${sc.height} pad=$wheelPadY " +
                "idx=$centerIdx target=$targetY（max=$maxScroll）"
        )
        // 瞬时定位避免动画再次触发滚动监听，形成重复吸附。
        if (sc.scrollY != targetY) {
            autoScrolling = true
            sc.scrollTo(0, targetY)
            lastScrollY = targetY
            autoScrolling = false
        }
        userScrolling = false
        val value = tempCells[centerIdx].first
        val current = if (isDriverZone) VehicleController.state.driverTempC
        else VehicleController.state.passengerTempC
        if (current != null && kotlin.math.abs(current - value) < 0.24f) {
            lastRenderedTemp = current
            highlightCentered(current)
            return // 没变，不写
        }
        VehicleController.setTempC(ctx, isDriverZone, value)
        Logx.d("温度滚轮：静止生效 ${"%.1f".format(value)}℃（${if (isDriverZone) "主驾" else "副驾"}）")
    }

    /** 实时给遮罩下的格子染蓝（纯几何：按滚动位置算居中项，与温度值解耦，滑动再快也不会错位）；
     *  指定 [current] 时把该温度项也一并染蓝（打开/程序侧对齐用） */
    private fun highlightCentered(current: Float?) {
        val sc = tempScroll ?: return
        val itemH = dp(44)
        val centerIdx = ((sc.scrollY + sc.height / 2 - wheelPadY) / itemH).toInt()
            .coerceIn(0, tempCells.size - 1)
        for ((index, pair) in tempCells.withIndex()) {
            val selected = index == centerIdx ||
                    (current != null && kotlin.math.abs(current - pair.first) < 0.24f)
            val cell = pair.second
            cell.setTextColor(if (selected) DockPopupStyle.text else DockPopupStyle.secondary)
        }
    }

    /** 打开时把列表滚到当前温度居中（scrollY = idx×itemH，同吸附公式） */
    private fun scrollToCurrentTemp() {
        val sc = tempScroll ?: return
        val current = if (isDriverZone) VehicleController.state.driverTempC
        else VehicleController.state.passengerTempC
        val itemH = dp(44)
        val idx = if (current == null) tempCells.size / 2
        else tempCells.indexOfFirst { kotlin.math.abs(it.first - current) < 0.24f }
        if (idx >= 0) {
            val maxScroll = (tempCells.size * itemH + 2 * wheelPadY - sc.height).coerceAtLeast(0)
            sc.scrollTo(0, (idx * itemH).coerceIn(0, maxScroll))
        }
        lastScrollY = sc.scrollY
        lastRenderedTemp = current
    }

    fun hide() {
        main.removeCallbacks(dismissRunnable)
        main.removeCallbacks(refreshRunnable)
        val box = content ?: return
        // ★ 立即置空：快速连点时 isShowing 能马上反映「已关」，toggle 语义才正确
        content = null
        params = null
        acMaxButton = null
        VehicleController.removeListener(listener)
        DockPopupChrome.fadeOut(box) {
            runCatching { wm?.removeViewImmediate(box) }
                .onFailure { Logx.e("温度弹窗：移除失败", it) }
            onDismissed?.invoke()
        }
    }

    fun destroy() {
        main.removeCallbacks(dismissRunnable)
        main.removeCallbacks(refreshRunnable)
        val box = content
        content = null
        params = null
        if (box != null) {
            VehicleController.removeListener(listener)
            runCatching { wm?.removeViewImmediate(box) }
        }
    }

    private fun resetTimer() {
        main.removeCallbacks(dismissRunnable)
        main.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    // 大屏保留原有控制区尺寸；窗口边缘较紧时按真实锚点方向收敛。
    private var SCALE = 2.5f

    private fun dp(v: Int): Int = (v * SCALE * ctx.resources.displayMetrics.density).toInt()

    private fun sp(v: Float): Float = v * SCALE
}
