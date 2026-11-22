package com.car.dockbar.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.config.VehicleChannels.AC_MAX_VALIDATION_KEY
import com.car.dockbar.config.VehicleChannels.POWER_VALIDATION_KEY
import com.car.dockbar.ui.AirFlowDrawable
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/**
 * 空调 / 座椅控制的高层封装。
 *
 * 取值编码遵循已验证的风量规律：
 *   value = functionId + index      （低 8 位 = 档位 / 枚举）
 *   例：风量手动 func=0x10020100，1 档 → 0x10020101
 *
 * 读回来的值若不是该前缀，则退化为「直接把返回值当 index 用」。
 */
object VehicleController {

    data class State(
        val connected: Boolean = false,
        val backend: String = "未连接",
        val driverTempC: Float? = null,
        val passengerTempC: Float? = null,
        val fanLevel: Int = 0,
        val fanMax: Int = 9,
        val fanAuto: Boolean = false,
        val recircInside: Boolean = false,
        val airDirection: Int = 1,
        val seatVent: Int = 0,
        val seatHeat: Int = 0,
        val acOn: Boolean = false,
        val tempDualOn: Boolean? = null,
        // ★ v1.4.35：温度弹窗 / 座椅弹窗所需
        val seatVentPassenger: Int = 0,
        val seatHeatPassenger: Int = 0,
        val defrostFront: Boolean = false,      // 前挡除霜/加热（0x10040200）
        val defrostRear: Boolean = false,       // 后挡除霜/加热（0x10040300）
        val steeringHeat: Int = 0,             // 方向盘加热档位（0x10090100）
        internal val unavailableComfort: Int = 0
    ) {
        internal fun seatVentVisible(driver: Boolean): Boolean {
            val property = if (driver) HvacProperty.SEAT_VENT else HvacProperty.SEAT_VENT_PASSENGER
            return unavailableComfort and property.mask == 0
        }

        internal val steeringHeatVisible: Boolean
            get() = unavailableComfort and HvacProperty.STEERING_HEAT.mask == 0
    }

    /** 空调风向：吹脸 / 吹脚 / 吹前挡 三个方向可按位组合（共 7 种模式） */
    const val AIR_FACE = AirFlowDrawable.FACE      // 1
    const val AIR_FOOT = AirFlowDrawable.FOOT      // 2
    const val AIR_DEFROST = AirFlowDrawable.DEFROST // 4
    /** 7 种组合的循环切换顺序（吹脸→吹脸+吹脚→吹脚→吹脚+吹前挡→吹前挡→吹脸+吹前挡→全开） */
    val AIR_MODES = AirFlowDrawable.MODES

    /**
     * 车机可调温度区间（℃）。
     * 来源：oneOS_Dock 的 HvacConstants —— `TEMP_C_MIN="15.5"` / `TEMP_C_MAX="28.5"`，
     * 对应 `MAX_SHOW_TAMP="HI"` / `MIN_SHOW_TAMP="LO"`。
     * 注意：车机实际上下限由 MaxTempSignal(0x10060300)/MinTempSignal(0x10060500) 给出，
     * 这里用系统常量作为默认兜底。
     */

    const val TEMP_MIN_C = 15.5f

    const val TEMP_MAX_C = 28.5f

    /** 座椅通风 / 加热档位上限（0=关，1..3） */
    const val SEAT_LEVEL_MAX = 3

    // ★ v1.4.48 修正（用户真车日志）：前除霜真车 funcId 是 0x10040200（事件映射表），
    //   而非 oneOS_Hvac 常量表里的 0x10040100；三个信号的 areaId 在真车都是全局区
    //   0x80000000（日志实锤：0x10010300 / 0x10030100 事件 zone=-2147483648），不再用 zone=1
    /** 前挡除霜/加热（HVAC_DEFROST_FRONT） */
    private const val FUNC_DEFROST_FRONT = VehicleChannels.FUNC_DEFROST_FRONT_MAX
    /** 后挡除霜/加热（HVAC_DEFROST_REAR） */
    private const val FUNC_DEFROST_REAR = VehicleChannels.FUNC_DEFROST_REAR
    /** 座椅记忆-应用位置（SETTING_FUNC_SEAT_POSITION_SET）——写位置号 1/2/3，车机待实测 */
    private const val FUNC_SEAT_POSITION_SET = 0x2d400200
    /** 全局区 areaId：真车对无分区信号统一用 0x80000000 */
    private const val ZONE_GLOBAL = Int.MIN_VALUE

    /**
     * 座椅记忆-当前位置号（SETTING_FUNC_SEAT_NUM）。
     *
     * 来源：oneOS_Hvac 的 `GlyCarPropertyIds` → `SEAT_NUM = 0x2d410f00`。
     * 读它就能知道当前处于 1/2/3 哪个记忆位，弹窗据此高亮，而不是靠「点了才亮一下」。
     */
    private const val FUNC_SEAT_NUM = 0x2d410f00

    /**
     * ★★ v1.4.89：座椅记忆**当前位置**（读这个才靠谱）。
     *
     * 来源：反编译参考 2.3 节（`SETTING_FUNC_*`，来自**座椅调节模块**，不是 HVAC）：
     * ```
     * SEAT_POSITION_SAVE = 0x2d400100   ← 当前处于第几号记忆位（1/2/3）
     * SEAT_POSITION_SET  = 0x2d400200   ← 调用第几号记忆位（写 1/2/3）
     * SEAT_NUM           = 0x2d410f00   ← 记忆位数量（本车 = 3，且该通道读不到）
     * ```
     * 我们之前只用了 `SEAT_NUM`（读不到），**真正的「选/读」两个通道压根没接**。
     */
    private const val FUNC_SEAT_POSITION_SAVE = 0x2d400100
    // 注：FUNC_SEAT_POSITION_SET 已在上面（0x2d400200）声明

    /** 座椅记忆位数量（用户确认：本车 3 个） */
    const val SEAT_MEMORY_SLOTS = 3

    /** 座椅记忆的 zone 候选：主驾 1 / 副驾 4 / 前排 0x8 / 全局 */
    private val SEAT_MEMORY_ZONES = intArrayOf(1, 4, 0x8, ZONE_GLOBAL)


    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(State) -> Unit>()

    /**
     * ★★ v1.4.78：**HAL 读写专用后台线程**。
     *
     * 为什么要：调温一次要打 4~6 次 binder IPC（读上下限 / 写 / 写回读…），
     * 每次几十毫秒。跑在主线程上 → **点温度 ± 会顿一下**（用户实测反馈）。
     * 现在：主线程只做乐观更新（立即响应），HAL 读写全丢到这个单线程上。
     */
    private val hal = HvacCommands.executor

    // 写后合并只包含受影响分组，不再触发整车全量读取。
    private const val REFRESH_DEBOUNCE_MS = 900L
    private const val FALLBACK_PROBE_INTERVAL_MS = 2500L
    private val REFRESH_GROUPS = VehicleRefreshGroup.values()
    private val FAN_GROUP = 1 shl VehicleRefreshGroup.FAN.ordinal
    private val CLIMATE_GROUP = 1 shl VehicleRefreshGroup.CLIMATE.ordinal
    private val TEMPERATURE_GROUP = 1 shl VehicleRefreshGroup.TEMPERATURE.ordinal
    private val COMFORT_GROUP = 1 shl VehicleRefreshGroup.COMFORT.ordinal
    private val ALL_GROUPS = (1 shl REFRESH_GROUPS.size) - 1
    private val refreshQueue = VehicleRefreshQueue()
    private val refreshRevisions = LongArray(REFRESH_GROUPS.size)
    private val refreshKeys = arrayOf(
        arrayOf("fan_auto", "fan_manual"),
        arrayOf("recirc", "air_direction", "ac", "temp_dual"),
        arrayOf("temp_driver", "temp_passenger"),
        arrayOf("seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger",
            "defrost_front", "defrost_rear", "steering_heat"),
        emptyArray()
    )
    private val nextFallbackProbeAt = Array(REFRESH_GROUPS.size) { LongArray(refreshKeys[it].size) }
    private val nextRefreshLogAt = LongArray(REFRESH_GROUPS.size)
    private val comfortCapabilities = arrayOf(HvacProperty.SEAT_VENT, HvacProperty.SEAT_VENT_PASSENGER, HvacProperty.STEERING_HEAT)
    // 查询与档位读取共用后台队列，但不跟随弹窗的 500ms 档位轮询加速。
    private var comfortProbeConnection = Long.MIN_VALUE
    private var nextComfortProbeAt = 0L
    private var comfortVisibilityConnection = Long.MIN_VALUE
    private var seatClimatePopupVisible = false
    private var forcedProbeGroups = 0
    private var pendingRefreshGroups = 0
    private val pendingRefreshAt = LongArray(REFRESH_GROUPS.size) { Long.MAX_VALUE }
    private val pendingRefresh = Runnable {
        val c = appCtx ?: return@Runnable
        val now = SystemClock.elapsedRealtime()
        var ready = 0
        for (group in REFRESH_GROUPS) {
            val bit = 1 shl group.ordinal
            if (pendingRefreshGroups and bit != 0 && now >= pendingRefreshAt[group.ordinal]) {
                ready = ready or bit
                pendingRefreshGroups = pendingRefreshGroups and bit.inv()
                pendingRefreshAt[group.ordinal] = Long.MAX_VALUE
            }
        }
        if (ready != 0) requestRefresh(c, ready)
        armPendingRefresh()
    }

    private fun armPendingRefresh() {
        main.removeCallbacks(pendingRefresh)
        var deadline = Long.MAX_VALUE
        for (group in REFRESH_GROUPS) {
            if (pendingRefreshGroups and (1 shl group.ordinal) != 0) {
                deadline = minOf(deadline, pendingRefreshAt[group.ordinal])
            }
        }
        if (deadline != Long.MAX_VALUE) {
            main.postDelayed(pendingRefresh, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
        }
    }

    /** 同组连点只延后本组，其他控件操作不得推迟已有温度确认。 */
    private fun scheduleRefresh(ctx: Context, groups: Int, delayMs: Long = REFRESH_DEBOUNCE_MS) {
        onMain {
            appCtx = ctx.applicationContext
            val deadline = SystemClock.elapsedRealtime() + delayMs
            for (group in REFRESH_GROUPS) {
                if (groups and (1 shl group.ordinal) != 0) pendingRefreshAt[group.ordinal] = deadline
            }
            pendingRefreshGroups = pendingRefreshGroups or groups
            armPendingRefresh()
        }
    }

    @Volatile
    var state: State = State()
        private set
    private var hvacState = HvacState(state)
    private val targetExpiry = Runnable { publishDisplay() }
    private data class AirflowResume(
        val level: Int, val auto: Boolean, val direction: Int, val connection: Long, val simulated: Boolean,
    )
    private var airflowResume: AirflowResume? = null

    @Volatile private var temperatureConfirmationConnection = Long.MIN_VALUE
    @Volatile private var driverConfirmationUntil = 0L
    @Volatile private var passengerConfirmationUntil = 0L
    @Volatile private var driverFloatConfirmationUntil = 0L
    @Volatile private var passengerFloatConfirmationUntil = 0L


    private var appCtx: Context? = null
    private val confirmationReceiver = object : HvacConfirmationReceiver {
        override fun capture(functionId: Int, zone: Int): HvacConfirmationSample? {
            val c = appCtx ?: return null
            if (!Prefs.vehicleApiEnabled(c) || Prefs.simulate(c)) return null
            var key: String? = null
            keySearch@ for (groupKeys in refreshKeys) {
                for (candidate in groupKeys) {
                    if (VehicleChannels.funcOf(c, candidate) == functionId && VehicleChannels.zoneOf(c, candidate) == zone) {
                        key = candidate
                        break@keySearch
                    }
                }
            }
            if (key == null && zone == ZONE_GLOBAL) key = when (functionId) {
                FUNC_DEFROST_FRONT -> "defrost_front"
                FUNC_DEFROST_REAR -> "defrost_rear"
                else -> null
            }
            val selected = key ?: return null
            val group = groupMaskOf(selected)
            val ordinal = Integer.numberOfTrailingZeros(group)
            if (group == 0 || ordinal !in refreshRevisions.indices) return null
            return HvacConfirmationSample(functionId, zone, selected, refreshRevisions[ordinal], validationEpoch, VehicleBridge.connectionGeneration)
        }

        override fun accept(sample: HvacConfirmationSample, value: Number) {
            onMain {
                val c = appCtx ?: return@onMain
                if (sample.connection != VehicleBridge.connectionGeneration || sample.epoch != validationEpoch ||
                    !Prefs.vehicleApiEnabled(c) || Prefs.simulate(c)) return@onMain
                val group = groupMaskOf(sample.key)
                val ordinal = Integer.numberOfTrailingZeros(group)
                if (group == 0 || ordinal !in refreshRevisions.indices || refreshRevisions[ordinal] != sample.revision) return@onMain
                val temperature = if ((sample.key == "temp_driver" || sample.key == "temp_passenger") &&
                    (value is Float || value is Double)) HvacChannels.normalizeTemperature(value.toFloat()) else null
                val raw = if (value is Int || value is Short || value is Byte) value.toInt() else null
                if (temperature == null && (raw == null || !validReading(c, sample.key, raw))) return@onMain
                update(invalidateReads = false, observedProperties = HvacChannels.observationProperties(sample.key, value)) { before ->
                    if (temperature != null) {
                        if (sample.key == "temp_driver") {
                            if (before.driverTempC == temperature) before else before.copy(driverTempC = temperature)
                        } else {
                            if (before.passengerTempC == temperature) before else before.copy(passengerTempC = temperature)
                        }
                    } else mergeReading(before, sample.key, raw!!, c)
                }
            }
        }
    }

    init { HvacCommands.confirmations = confirmationReceiver }

    // =====================================================================
    // 生命周期
    // =====================================================================

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (Prefs.simulate(ctx) || !Prefs.vehicleApiEnabled(ctx)) {
            onMain { hvacState.clearTargets(); clearStaleComfortVisibility(ctx); publishDisplay() }
        }
        // 车机在空调关闭时通常返回 UNKNOWN；先恢复上次有效设定，避免 Dock 显示两个 OFF。
        val cachedDriverTemp = Prefs.lastDriverTempC(ctx)
        val cachedPassengerTemp = Prefs.lastPassengerTempC(ctx)
        if ((state.driverTempC == null && cachedDriverTemp != null) ||
            (state.passengerTempC == null && cachedPassengerTemp != null)
        ) {
            update(TEMPERATURE_GROUP) { current ->
                current.copy(
                    driverTempC = current.driverTempC ?: cachedDriverTemp,
                    passengerTempC = current.passengerTempC ?: cachedPassengerTemp
                )
            }
        }
        // ★★ v1.4.51 一级隔离开关：完全不碰厂商 API。
        //   连 Car.create() 都不调 —— 用于二分「闪退是不是厂商车控库 abort 引起的」。
        if (!Prefs.vehicleApiEnabled(ctx)) {
            Logx.w("车控通道已整体关闭（二分开关）：本次不调用任何厂商接口，空调状态保持上次缓存值")
            update { current -> current.copy(connected = false, backend = "已关闭（排查闪退）") }
            return
        }
        if (Prefs.simulate(ctx)) {
            Logx.d("车控：模拟模式，跳过厂商通道")
            // 给一组看起来合理的初值，方便直接调 UI
            update(FAN_GROUP or TEMPERATURE_GROUP) { current ->
                if (current.driverTempC == null) {
                    current.copy(
                        driverTempC = 22.0f,
                        passengerTempC = 22.0f,
                        fanLevel = 3,
                        fanMax = 9,
                        fanAuto = false
                    )
                } else current
            }
            update { current -> current.copy(connected = true, backend = "模拟数据") }
            return
        }
        dumpCarPermissions(ctx)
        runCatching {
            val ok = VehicleBridge.init(ctx)
            if (ok) {
                hal.execute { configureWatcher(ctx.applicationContext) }
                refresh(ctx)
            } else {
                update { current -> current.copy(connected = false, backend = "未连接") }
            }
        }.onFailure {
            Logx.e("车控初始化失败（真实通道）：${it.javaClass.simpleName} ${it.message}", it)
            update { current -> current.copy(connected = false, backend = "未连接") }
        }
    }

    /**
     * 输出本机车控相关权限，不能仅凭声明或取得 ADB shell 判断真实写入能力。
     * 标准 Android 9 的 CONTROL_CAR_CLIMATE 为 system|signature，普通 pm grant
     * 可能被系统拒绝；启动补权会按本机定义尝试缺项并回读，厂商接口是否校验另需验证。
     */
    private fun dumpCarPermissions(ctx: Context) {
        val perms = listOf(
            "android.car.permission.CONTROL_CAR_CLIMATE",
            "android.car.permission.CAR_INFO",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            "android.permission.WRITE_SECURE_SETTINGS"
        )
        for (p in perms) {
            val granted = runCatching {
                ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            val short = p.removePrefix("android.car.permission.").removePrefix("android.permission.")
            if (granted) {
                Logx.d("权限 $short = 已授予")
            } else {
                Logx.w("权限 $short = ★未授予（请查看启动权限检查结果；" +
                    "是否可授予及厂商接口是否校验，以本机系统为准）")
            }
        }
    }

    // 临时验证会话不持久化；原厂电源状态同时服务日常风向手势和验证报告。
    @Volatile private var validationMode: HvacValidationMode? = null
    @Volatile private var validationEpoch = 0L
    @Volatile private var validationDeadlineAt = 0L

    enum class PowerState { UNKNOWN, OFF, ON }
    internal enum class PowerTapResult { INACTIVE, UNAVAILABLE, UNKNOWN, WAITING, QUEUED }

    // 只用同一连接和代际内的真实 0/1 观测，绝不把试写目标合并进车辆观测。
    private data class PowerObservation(val raw: Int, val epoch: Long, val connection: Long)
    @Volatile private var powerObservation: PowerObservation? = null
    private var powerWriteBasis: PowerObservation? = null
    private var powerWriteNeedsObservation = false
    private var publishedPowerState = PowerState.UNKNOWN

    private fun isPowerOperationValid(ctx: Context, epoch: Long): Boolean =
        epoch == validationEpoch && Prefs.vehicleApiEnabled(ctx) && !Prefs.simulate(ctx) &&
            (validationMode == null || activeValidationMode() != null)

    internal fun powerState(): PowerState = when (currentPowerObservation()?.raw) {
        0 -> PowerState.OFF
        1 -> PowerState.ON
        else -> PowerState.UNKNOWN
    }

    private fun currentPowerObservation(): PowerObservation? = powerObservation?.takeIf {
        it.epoch == validationEpoch && it.connection == VehicleBridge.connectionGeneration && VehicleBridge.isReady
    }

    private fun clearPowerObservation() {
        powerObservation = null
        clearPowerWrite()
    }

    private fun clearPowerWrite() {
        powerWriteBasis = null
        powerWriteNeedsObservation = false
    }

    private fun observePower(raw: Int, epoch: Long, connection: Long): Boolean {
        if (powerWriteNeedsObservation) clearPowerWrite()
        val previous = powerObservation
        if (previous?.raw == raw && previous.epoch == epoch && previous.connection == connection) return false
        powerObservation = PowerObservation(raw, epoch, connection)
        return true
    }
    /** 风向短按只切换原厂总电源；未知状态不猜，不补写风量、风向、A/C 或 AUTO。 */
    internal fun togglePower(ctx: Context): PowerTapResult {
        if (Prefs.simulate(ctx)) return PowerTapResult.INACTIVE
        val epoch = validationEpoch
        fun record(detail: String) {
            if (validationMode == HvacValidationMode.POWER) {
                HvacDiagnostics.switchWrite(HvacValidationMode.POWER, "$detail entry=DOCK epoch=$epoch") {
                    epoch == validationEpoch
                }
            }
        }
        if (!isPowerOperationValid(ctx, epoch) || !HvacCommands.canSubmit(ctx, "togglePower")) {
            record("stage=SKIPPED reason=验证已结束或车控写入门禁拒绝")
            return PowerTapResult.UNAVAILABLE
        }
        val observed = currentPowerObservation()
        if (observed == null) {
            record("stage=SKIPPED reason=电源状态未知")
            return PowerTapResult.UNKNOWN
        }
        if (powerWriteBasis?.let { it.connection == observed.connection && it.epoch == epoch } == true) {
            record("stage=SKIPPED reason=前次写入仍待确认")
            return PowerTapResult.WAITING
        }
        val target = HvacChannels.powerToggleTarget(observed.raw) ?: run {
            record("stage=SKIPPED reason=电源状态不是合法裸值")
            return PowerTapResult.UNKNOWN
        }
        powerWriteBasis = observed
        powerWriteNeedsObservation = false
        record("stage=REQUEST target=$target")
        val queued = HvacCommands.submit(ctx, "hvac_power entry=DOCK target=$target", onSkipped = {
            record("stage=ABORT target=$target reason=未完成，见车控日志")
            onMain { if (powerWriteBasis === observed) clearPowerWrite() }
        }) {
            val accepted = VehicleBridge.writeInt(VehicleChannels.FUNC_POWER, ZONE_GLOBAL, target) {
                isPowerOperationValid(ctx, epoch) && currentPowerObservation() === observed
            }
            val sent = HvacCommands.operationNativeWrites() != 0
            if (sent) record("stage=RETURN target=$target accepted=$accepted")
            else record("stage=SKIPPED target=$target reason=会话或观测已变化，或写入门禁拒绝")
            onMain {
                if (powerWriteBasis !== observed) return@onMain
                if (sent && isPowerOperationValid(ctx, epoch) && observed.connection == VehicleBridge.connectionGeneration) {
                    // 回调可能先于厂商返回；若没有更新的状态，等下一次有效观测再接受短按。
                    if (powerObservation === observed) powerWriteNeedsObservation = true else clearPowerWrite()
                    requestRefresh(ctx, CLIMATE_GROUP)
                } else clearPowerWrite()
            }
        }
        return if (queued) PowerTapResult.QUEUED else PowerTapResult.UNAVAILABLE
    }

    @Volatile private var watcherConfiguration = 0L
    @Volatile private var watcherConnection = -1L
    @Volatile private var subscriptionAccepted = false
    @Volatile private var configuredEventMask = 0
    private var eventProofConnection = Long.MIN_VALUE
    private var initialSnapshotConnection = Long.MIN_VALUE
    private var seenEventMask = 0
    private val refreshIntervals = LongArray(REFRESH_GROUPS.size) { REFRESH_GROUPS[it].intervalMs }
    private val validationTimeout = Runnable { stopHvacValidation("验证窗口到期") }
    private val validationPoll = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            if (validationMode == null) return
            val mode = activeValidationMode()
            if (mode == null) {
                stopHvacValidation("验证窗口到期或车控已关闭")
                return
            }
            if (mode == HvacValidationMode.POWER && powerState() != publishedPowerState) publishDisplay()
            // 舒适订阅验证只驱动舒适组，避免额外启动传感器组及其自动座椅逻辑。
            poll(c, validationGroups(mode))
            main.postDelayed(this, 250L)
        }
    }

    private fun validationGroups(mode: HvacValidationMode): Int =
        when {
            mode == HvacValidationMode.AC_MAX || mode == HvacValidationMode.POWER -> CLIMATE_GROUP
            mode.comfortReadOnly -> COMFORT_GROUP
            else -> ALL_GROUPS
        }

    /** 只在用户明确开始验证后增加订阅和浮点读取，绝不自动发出车控写命令。 */
    internal fun startHvacValidation(ctx: Context, mode: HvacValidationMode) {
        onMain {
            val c = ctx.applicationContext
            appCtx = c
            val powerModeChanged = validationMode == HvacValidationMode.POWER || mode == HvacValidationMode.POWER
            validationEpoch++
            val epoch = validationEpoch
            main.removeCallbacks(validationTimeout)
            main.removeCallbacks(validationPoll)
            validationMode = mode
            validationDeadlineAt = SystemClock.elapsedRealtime() + 120_000L
            HvacDiagnostics.begin(c, mode)
            if (!Prefs.vehicleApiEnabled(c) || Prefs.simulate(c)) {
                HvacDiagnostics.registration(VehicleBridge.backend.label, mode.keys, false, "车控API关闭或模拟模式，未访问车辆")
                stopHvacValidation("无法开始：请启用真实车控API，退出模拟模式")
                return@onMain
            }
            if (powerModeChanged) {
                clearPowerObservation()
                publishDisplay()
            }
            main.postDelayed(validationTimeout, 120_000L)
            main.post(validationPoll)
            hal.execute {
                if (validationEpoch != epoch || activeValidationMode() == null) return@execute
                var connection = VehicleBridge.connectionGeneration
                runCatching {
                    if (!VehicleBridge.isReady && !VehicleBridge.init(c)) {
                        if (validationEpoch != epoch || connection != VehicleBridge.connectionGeneration || activeValidationMode() == null) return@runCatching
                        HvacDiagnostics.registration(VehicleBridge.backend.label, mode.keys, false, "车控连接不可用")
                        return@runCatching
                    }
                    connection = VehicleBridge.connectionGeneration
                    if (validationEpoch != epoch || activeValidationMode() == null) return@runCatching
                    configureWatcher(c)
                    for (key in mode.keys) {
                        if (validationEpoch != epoch || connection != VehicleBridge.connectionGeneration || activeValidationMode() == null) return@runCatching
                        val fid = VehicleChannels.observationFuncOf(c, key)
                        val zone = VehicleChannels.observationZoneOf(c, key)
                        val status = if (fid > 0) VehicleBridge.supportOf(fid, zone) else "未配置"
                        if (validationEpoch != epoch || connection != VehicleBridge.connectionGeneration || activeValidationMode() == null) return@runCatching
                        val values = if (fid > 0) VehicleBridge.supportedValues(fid, zone) else null
                        if (validationEpoch != epoch || connection != VehicleBridge.connectionGeneration || activeValidationMode() == null) return@runCatching
                        HvacDiagnostics.capability(key, status, values)
                    }
                    if (validationEpoch == epoch && connection == VehicleBridge.connectionGeneration && activeValidationMode() != null) {
                        requestRefresh(c, validationGroups(mode))
                    }
                }.onFailure {
                    if (validationEpoch == epoch && connection == VehicleBridge.connectionGeneration && activeValidationMode() != null) {
                        HvacDiagnostics.registration(VehicleBridge.backend.label, mode.keys, false,
                            "${it.javaClass.simpleName}: ${it.message}")
                    }
                }
            }
        }
    }

    internal fun stopHvacValidation(reason: String = "用户停止") {
        onMain {
            if (validationMode == null) return@onMain
            val wasPower = validationMode == HvacValidationMode.POWER
            validationMode = null
            validationEpoch++
            val epoch = validationEpoch
            main.removeCallbacks(validationTimeout)
            main.removeCallbacks(validationPoll)
            HvacDiagnostics.finish(reason)
            if (wasPower) {
                clearPowerObservation()
                publishDisplay()
            }
            val c = appCtx ?: return@onMain
            hal.execute {
                if (validationEpoch != epoch) return@execute
                val restored = runCatching { configureWatcher(c) }.getOrElse {
                    Logx.e("HVAC_TEST stage=RESTORE_FAILED", it)
                    false
                }
                if (validationEpoch == epoch) HvacDiagnostics.restoration(restored,
                    if (restored) "基线订阅配置调用已完成，仍不等于收到事件" else "旧订阅卸载或基线恢复未确认，查看后端日志")
            }
        }
    }

    private fun watcherKeys(c: Context): List<String> = buildList {
        if (Prefs.vehicleWatcherEnabled(c)) {
            addAll(listOf("fan_manual", "fan_auto", "recirc", "air_direction", "ac",
                "seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger"))
        }
        if (Prefs.vehicleTempWatcherEnabled(c)) addAll(listOf("temp_driver", "temp_passenger"))
        addAll(Prefs.hvacEventKeys(c))
        // 双温同步和原厂电源是 Dock 实际显示/手势的基础状态，不受日常通道选择影响。
        add("temp_dual")
        add(POWER_VALIDATION_KEY)
        activeValidationMode()?.let { addAll(it.keys) }
    }.distinct()

    internal fun reloadHvacSubscriptions(ctx: Context) {
        onMain {
            appCtx = ctx.applicationContext
            validationEpoch++
            clearPowerObservation()
            if (!Prefs.vehicleApiEnabled(ctx) || Prefs.simulate(ctx)) hvacState.clearTargets()
            publishDisplay()
            val c = ctx.applicationContext
            hal.execute {
                configureWatcher(c)
                refresh(c)
            }
        }
    }

    private fun activeValidationMode(): HvacValidationMode? {
        val c = appCtx ?: return null
        return validationMode?.takeIf { SystemClock.elapsedRealtime() < validationDeadlineAt &&
            Prefs.vehicleApiEnabled(c) && !Prefs.simulate(c) }
    }


    private fun isAcMaxValidation(epoch: Long): Boolean =
        activeValidationMode() == HvacValidationMode.AC_MAX && validationEpoch == epoch

    internal fun acMaxValidationEpoch(): Long? = validationEpoch.takeIf(::isAcMaxValidation)

    /** 只写原厂 MAX 开关；不修改温度、风量或 Dock 显示目标。 */
    internal fun writeAcMaxForValidation(ctx: Context, epoch: Long, on: Boolean, entry: String): Boolean {
        val value = if (on) 1 else 0
        fun record(stage: String, detail: String = "") {
            HvacDiagnostics.switchWrite(HvacValidationMode.AC_MAX, "stage=$stage entry=$entry epoch=$epoch target=$value $detail".trimEnd()) {
                validationEpoch == epoch
            }
        }
        record("REQUEST")
        if (!isAcMaxValidation(epoch)) {
            record("SKIPPED", "验证窗口已失效，未调用厂商接口")
            return false
        }
        val connection = VehicleBridge.connectionGeneration
        return HvacCommands.submit(ctx, "ac_max_validation entry=$entry target=$value", onSkipped = {
            record("ABORT", "提交或执行未完成，原因见 HVAC_WRITE / HVAC_TRACE")
        }) {
            val accepted = VehicleBridge.writeInt(VehicleChannels.FUNC_AC_MAX, ZONE_GLOBAL, value) {
                isAcMaxValidation(epoch)
            }
            if (HvacCommands.operationNativeWrites() == 0) {
                record("SKIPPED", "未调用厂商接口，原因见 HVAC_WRITE / HVAC_TRACE")
            } else {
                record("RETURN", "accepted=$accepted；生效看事件、读回和原车状态")
                onMain {
                    if (isAcMaxValidation(epoch) && connection == VehicleBridge.connectionGeneration) {
                        requestRefresh(ctx, CLIMATE_GROUP)
                    }
                }
            }
        }
    }

    /** 基础监听和验证监听共用唯一集合，包含共用功能号的不同区域。 */
    private fun configureWatcher(c: Context): Boolean {
        val keys = if (Prefs.vehicleApiEnabled(c) && !Prefs.simulate(c)) watcherKeys(c) else emptyList()
        val requested = keys.toSet()
        val configuration = ++watcherConfiguration
        val epoch = validationEpoch
        val connection = VehicleBridge.connectionGeneration
        // 配置目录仅在订阅重配时生成，不在每次轮询或回调重建。
        val pollingKeys = refreshKeys.flatMapTo(mutableSetOf()) { it.asList() }
        pollingKeys.add(POWER_VALIDATION_KEY)
        if (AC_MAX_VALIDATION_KEY in requested) pollingKeys.add(AC_MAX_VALIDATION_KEY)
        val evidenceKeys = (VehicleChannels.ALL.map { it.key } + pollingKeys).distinct()
        val functions = linkedSetOf<Int>()
        val evidenceConfigs = evidenceKeys.map { key ->
            val fid = VehicleChannels.observationFuncOf(c, key)
            if (fid > 0 && key in requested) functions.add(fid)
            HvacChannelEvidence.ChannelConfig(key, fid, VehicleChannels.observationZoneOf(c, key), key in pollingKeys)
        }
        val funcs = functions.toIntArray()
        HvacChannelEvidence.connection(connection, VehicleBridge.backend.label, VehicleBridge.isReady)
        HvacChannelEvidence.registration(connection, configuration, evidenceConfigs, requested, null)
        Logx.w("HVAC_TEST stage=SUBSCRIBE_BEGIN backend=${VehicleBridge.backend.label} keys=$keys funcs=${funcs.joinToString { "0x" + it.toString(16) }}")
        val ok = VehicleBridge.registerWatcher(funcs) { fid, zone, value ->
            val receivedAt = SystemClock.elapsedRealtime()
            main.post {
                if (configuration != watcherConfiguration || epoch != validationEpoch ||
                    connection != VehicleBridge.connectionGeneration) return@post
                if (validationMode != null && activeValidationMode() == null) return@post
                runCatching {
                    val key = evidenceConfigs.firstOrNull {
                        it.key in requested && it.fid == fid && it.configuredZone == zone
                    }?.key
                    if (key == null) {
                        for (config in evidenceConfigs) {
                            if (config.key in requested && config.fid == fid) {
                                HvacChannelEvidence.event(connection, configuration, config.key, fid, zone,
                                    value as? Number, false, receivedAt)
                                if (config.key == AC_MAX_VALIDATION_KEY || config.key == POWER_VALIDATION_KEY) {
                                    HvacDiagnostics.event(config.key, value, false, false, zone,
                                        SystemClock.elapsedRealtime() - receivedAt)
                                }
                            }
                        }
                        if (HvacDiagnostics.active) Logx.w("HVAC_TEST stage=EVENT_IGNORED fid=0x${fid.toString(16)} zone=$zone raw=$value reason=未订阅或区域不匹配")
                        return@runCatching
                    }
                    val before = state
                    val temperature = key == "temp_driver" || key == "temp_passenger"
                    val float = if (temperature && (value is Float || value is Double)) HvacChannels.normalizeTemperature((value as Number).toFloat()) else null
                    val raw = if (value is Int || value is Short || value is Byte) (value as Number).toInt() else null
                    val accepted = float != null || (raw != null && validReading(c, key, raw))
                    HvacChannelEvidence.event(connection, configuration, key, fid, zone, value as? Number, accepted, receivedAt)
                    if (key == AC_MAX_VALIDATION_KEY || key == POWER_VALIDATION_KEY) {
                        if (accepted && raw != null) {
                            HvacCommands.observe(fid, zone, raw, "EVENT", receivedAt, connection)
                            if (key == POWER_VALIDATION_KEY) {
                                // 同组较早的轮询不能覆盖新收到的电源事件，即使事件值相同。
                                invalidateGroups(CLIMATE_GROUP, 0)
                                if (observePower(raw, epoch, connection)) publishDisplay()
                            }
                        }
                        HvacDiagnostics.event(key, raw ?: value, accepted, false, zone,
                            SystemClock.elapsedRealtime() - receivedAt)
                        return@runCatching
                    }
                    if (accepted) {
                        update(groupMaskOf(key), observedProperties = HvacChannels.observationProperties(key, value)) { current ->
                            val next = if (float != null) {
                                if (key == "temp_driver") {
                                    if (current.driverTempC == float) current else current.copy(driverTempC = float)
                                } else {
                                    if (current.passengerTempC == float) current else current.copy(passengerTempC = float)
                                }
                            } else mergeReading(current, key, raw!!, c)
                            next
                        }
                    }
                    if (accepted) recordEventEvidence(key, value, connection)
                    if (accepted && value is Number) HvacCommands.observe(fid, zone, value, "EVENT", receivedAt, connection)
                    HvacDiagnostics.event(key, value, accepted, before != state, zone,
                        SystemClock.elapsedRealtime() - receivedAt)
                    // 重复事件由观测器聚合；不在主线程为每个回调同步落盘。
                    if (evidenceOn() && fid == VehicleChannels.FUNC_CIRCULATION && raw != null) {
                        Logx.d("★ 取证：内外循环事件 zone=$zone raw=$raw 解析=${recircTextOf(raw)}")
                    }
                }.onFailure { Logx.e("车控事件处理异常 fid=0x${fid.toString(16)} zone=$zone", it) }
            }
        }
        val currentConnection = connection == VehicleBridge.connectionGeneration
        if (currentConnection) HvacChannelEvidence.registration(connection, configuration, evidenceConfigs, requested, ok)
        if (currentConnection) {
            watcherConnection = connection
            subscriptionAccepted = ok
            configuredEventMask = HvacRefreshPolicy.mask(keys)
        }
        if (epoch == validationEpoch && currentConnection && activeValidationMode() != null) {
            HvacDiagnostics.registration(VehicleBridge.backend.label, keys, ok,
                if (ok) "订阅调用被接受，仍需实际回调验证" else "订阅失败，保留分组轮询；查看前面的厂商返回或异常")
        }
        Logx.d("车控选择性订阅 keys=$keys accepted=$ok")
        return ok && currentConnection
    }

    private fun validReading(c: Context, key: String, raw: Int): Boolean =
        if (key == "temp_driver" || key == "temp_passenger") {
            HvacChannels.validRaw(key, raw, Prefs.tempBase(c), Prefs.tempStep(c), Prefs.tempIndexBase(c))
        } else HvacChannels.validRaw(key, raw)

    private fun mergeReading(base: State, key: String, raw: Int, c: Context): State =
        if (key == "temp_driver" || key == "temp_passenger") {
            HvacChannels.merge(base, key, raw, Prefs.tempBase(c), Prefs.tempStep(c), Prefs.tempIndexBase(c))
        } else HvacChannels.merge(base, key, raw)

    /** watcher 回调：把某个通道的原始值并入当前 State（无效读数不覆盖已知状态） */
    private fun applyRaw(key: String, raw: Int) {
        val c = appCtx ?: return
        if (!validReading(c, key, raw)) return
        update(groupMaskOf(key), observedProperties = HvacChannels.observationProperties(key, raw)) {
            current -> mergeReading(current, key, raw, c)
        }
    }

    // =====================================================================
    // 刷新
    // =====================================================================

    private data class GroupReading(
        val values: IntArray,
        val present: Int,
        val ignition: Int? = null,
        val indoor: Float? = null,
        val ambient: Float? = null,
        val temperatures: FloatArray? = null,
        val validationEpoch: Long = 0L,
        val unavailableComfort: Int? = null,
        val powerRaw: Int? = null,
    )

    private val EMPTY_READING = GroupReading(IntArray(0), 0)

    /** 显式刷新仍覆盖全部分组，但每组独立读取和发布，不再携带整份旧状态。 */
    fun refresh(ctx: Context? = null) {
        val c = ctx ?: appCtx ?: return
        requestRefresh(c, ALL_GROUPS)
    }

    /** 弹窗打开就读取舒适组；无事件时也复用原有调度做短周期兜底。 */
    internal fun setSeatClimatePopupVisible(ctx: Context, visible: Boolean) {
        onMain {
            if (seatClimatePopupVisible == visible) return@onMain
            seatClimatePopupVisible = visible
            if (visible) requestRefresh(ctx, COMFORT_GROUP)
        }
    }

    /** 高频调用只检查期限；实际厂商读取仍由唯一 HAL 队列串行执行。 */
    private fun recordEventEvidence(key: String, raw: Any?, connection: Long) {
        if (HvacChannels.observationProperties(key, raw) == 0) return
        if (eventProofConnection != connection) {
            eventProofConnection = connection
            seenEventMask = 0
        }
        seenEventMask = seenEventMask or HvacRefreshPolicy.bit(key)
    }

    private fun applyPollingPolicy(now: Long, groups: Int) {
        val connection = VehicleBridge.connectionGeneration
        if (eventProofConnection != connection) { eventProofConnection = connection; seenEventMask = 0 }
        if (groups == ALL_GROUPS && initialSnapshotConnection != connection) {
            initialSnapshotConnection = connection
            for (group in REFRESH_GROUPS) refreshQueue.request(group)
        }
        val registered = subscriptionAccepted && watcherConnection == connection
        if (!registered) seenEventMask = 0
        seenEventMask = seenEventMask and configuredEventMask
        val mode = validationMode
        for (group in REFRESH_GROUPS) {
            val interval = when {
                group == VehicleRefreshGroup.CLIMATE &&
                    (mode == HvacValidationMode.AC_MAX || mode == HvacValidationMode.POWER) -> group.intervalMs
                group == VehicleRefreshGroup.TEMPERATURE && temperatureNeedsConfirmation(now) -> 500L
                group == VehicleRefreshGroup.COMFORT && seatClimatePopupVisible -> 500L
                else -> HvacRefreshPolicy.intervalMs(group, configuredEventMask, seenEventMask, registered)
            }
            refreshQueue.setInterval(group, interval, now)
            if (refreshIntervals[group.ordinal] != interval) {
                refreshIntervals[group.ordinal] = interval
                Logx.d("HVAC_POLL group=$group intervalMs=$interval registered=$registered seen=$seenEventMask")
            }
        }
    }

    /** 只有轮询发现新值时先回到快兜底；静默且未变化不判事件失效。 */
    private fun loseEventEvidence(properties: Int) {
        val previous = seenEventMask
        for (property in HvacProperty.entries) {
            if (properties and property.mask == 0) continue
            val bits = if (property == HvacProperty.FAN) {
                HvacRefreshPolicy.bit("fan_manual") or HvacRefreshPolicy.bit("fan_auto")
            } else HvacRefreshPolicy.bit(property.key)
            seenEventMask = seenEventMask and bits.inv()
        }
        if (previous != seenEventMask) Logx.d("HVAC_POLL 仅轮询发现新值 properties=$properties，暂回快兜底")
    }
    internal fun poll(ctx: Context) = poll(ctx, ALL_GROUPS)

    private fun poll(ctx: Context, groups: Int) {
        onMain {
            val c = ctx.applicationContext
            clearStaleComfortVisibility(c)
            if (!Prefs.vehicleApiEnabled(c)) return@onMain
            appCtx = c
            val now = SystemClock.elapsedRealtime()
            applyPollingPolicy(now, groups)
            refreshQueue.poll(now, groups)
            pumpRefreshQueue(c)
        }
    }

    private fun requestRefresh(ctx: Context, groups: Int) {
        onMain {
            val c = ctx.applicationContext
            clearStaleComfortVisibility(c)
            if (!Prefs.vehicleApiEnabled(c)) return@onMain
            appCtx = c
            forcedProbeGroups = forcedProbeGroups or groups
            for (group in REFRESH_GROUPS) {
                if (groups and (1 shl group.ordinal) != 0) refreshQueue.request(group)
            }
            pumpRefreshQueue(c)
        }
    }

    private fun pumpRefreshQueue(c: Context) {
        if (!Prefs.vehicleApiEnabled(c)) return
        val group = refreshQueue.take() ?: return
        val revision = refreshRevisions[group.ordinal]
        val epoch = validationEpoch
        val connection = VehicleBridge.connectionGeneration
        val bit = 1 shl group.ordinal
        val forceProbe = forcedProbeGroups and bit != 0
        forcedProbeGroups = forcedProbeGroups and bit.inv()
        val queuedAt = SystemClock.elapsedRealtime()
        hal.execute {
            val startedAt = SystemClock.elapsedRealtime()
            val reading = if (epoch == validationEpoch && connection == VehicleBridge.connectionGeneration) {
                runCatching {
                    if (VehicleBridge.isReady && watcherConnection != connection) configureWatcher(c)
                    if (epoch == validationEpoch && connection == VehicleBridge.connectionGeneration) {
                        readGroup(c, group, forceProbe)
                    } else null
                }.onFailure { Logx.e("车控分组读取失败：$group", it) }.getOrNull()
            } else null
            val ioMs = SystemClock.elapsedRealtime() - startedAt
            main.post {
                try {
                    if (reading != null && epoch == validationEpoch && connection == VehicleBridge.connectionGeneration &&
                        (validationMode == null || activeValidationMode() != null)) {
                        applyReading(c, group, revision, connection, reading, startedAt - queuedAt, ioMs)
                    }
                } finally {
                    refreshQueue.complete(group, SystemClock.elapsedRealtime())
                    pumpRefreshQueue(c)
                }
            }
        }
    }

    /** 这里只读厂商接口，不修改可观察状态；分组结果回主线程后才归并。 */
    private fun readGroup(c: Context, group: VehicleRefreshGroup, forceProbe: Boolean): GroupReading {
        if (!Prefs.vehicleApiEnabled(c) || Prefs.simulate(c)) return EMPTY_READING
        if (group == VehicleRefreshGroup.SENSORS) {
            return GroupReading(
                EMPTY_READING.values, 0, SensorBridge.readIgnitionState(),
                SensorBridge.readFloat(SensorBridge.TEMP_INDOOR),
                SensorBridge.readFloat(SensorBridge.TEMP_AMBIENT)
            )
        }
        if (!VehicleBridge.isReady) return EMPTY_READING
        val keys = refreshKeys[group.ordinal]
        val values = IntArray(keys.size)
        val epoch = validationEpoch
        val connection = VehicleBridge.connectionGeneration
        val evidenceSubscription = watcherConfiguration
        val switchMode = if (group == VehicleRefreshGroup.CLIMATE) activeValidationMode() else null
        val switchKey = when (switchMode) {
            HvacValidationMode.AC_MAX -> AC_MAX_VALIDATION_KEY
            HvacValidationMode.POWER -> POWER_VALIDATION_KEY
            else -> null
        }
        val powerReadKey = switchKey ?: POWER_VALIDATION_KEY.takeIf { group == VehicleRefreshGroup.CLIMATE }
        var powerRaw: Int? = null
        if (powerReadKey != null && epoch == validationEpoch && connection == VehicleBridge.connectionGeneration) {
            val key = powerReadKey
            val fid = VehicleChannels.observationFuncOf(c, key)
            val zone = VehicleChannels.observationZoneOf(c, key)
            val observedAt = SystemClock.elapsedRealtime()
            val raw = readEvidenceInt(c, key, fid, zone, zone, epoch, connection, evidenceSubscription)
            if (epoch == validationEpoch && connection == VehicleBridge.connectionGeneration &&
                (switchMode == null || activeValidationMode() == switchMode)) {
                val valid = raw != null && validReading(c, key, raw)
                HvacDiagnostics.read(key, raw, valid, "INT_READ zone=$zone", observedAt)
                if (key == POWER_VALIDATION_KEY && valid) powerRaw = raw
            }
        }
        val normalKeys = Prefs.hvacEventKeys(c)
        val temperatures = if (group == VehicleRefreshGroup.TEMPERATURE &&
            (activeValidationMode() == HvacValidationMode.TEMPERATURE || normalKeys.any { it == "temp_driver" || it == "temp_passenger" } ||
                floatingConfirmation("temp_driver") || floatingConfirmation("temp_passenger"))) FloatArray(keys.size) { Float.NaN } else null
        var present = 0
        for (i in keys.indices) {
            if (epoch != validationEpoch || connection != VehicleBridge.connectionGeneration ||
                !Prefs.vehicleApiEnabled(c) || Prefs.simulate(c) ||
                (validationMode != null && activeValidationMode() == null)) break
            val key = keys[i]
            val observedAt = SystemClock.elapsedRealtime()
            if (temperatures != null && (normalKeys.contains(key) || activeValidationMode() == HvacValidationMode.TEMPERATURE || floatingConfirmation(key)) && epoch == validationEpoch) {
                val fid = VehicleChannels.funcOf(c, key)
                val zone = VehicleChannels.zoneOf(c, key)
                Logx.d("HVAC_TEST stage=FLOAT_READ_BEGIN key=$key fid=0x${fid.toString(16)} zone=$zone")
                val rawFloat = if (fid > 0) VehicleBridge.readTempProperty(fid, zone) else null
                if (epoch != validationEpoch || connection != VehicleBridge.connectionGeneration ||
                    (validationMode != null && activeValidationMode() == null)) break
                val temp = rawFloat?.let(HvacChannels::normalizeTemperature)
                if (epoch == validationEpoch) HvacDiagnostics.read(key, rawFloat, temp != null, "FLOAT_READ zone=$zone", observedAt)
                if (fid > 0) HvacChannelEvidence.read(connection, evidenceSubscription, key, fid, zone, zone,
                    rawFloat, temp != null, "FLOAT_READ", observedAt, SystemClock.elapsedRealtime() - observedAt)
                if (temp != null) {
                    temperatures[i] = temp
                    continue
                }
            }
            var actualZone = VehicleChannels.observationZoneOf(c, key)
            val raw = when (key) {
                "defrost_front", "defrost_rear", "steering_heat" -> readEvidenceInt(c, key,
                    VehicleChannels.observationFuncOf(c, key), actualZone, actualZone, epoch, connection, evidenceSubscription)
                else -> {
                    val now = SystemClock.elapsedRealtime()
                    val probe = forceProbe || now >= nextFallbackProbeAt[group.ordinal][i]
                    if (probe) nextFallbackProbeAt[group.ordinal][i] = now + FALLBACK_PROBE_INTERVAL_MS
                    val result = read(key, c, allowZoneFallback = probe, evidenceEpoch = epoch)
                    actualZone = result.zone
                    result.value
                }
            }
            if (raw != null) {
                values[i] = raw
                present = present or (1 shl i)
            }
            if (epoch == validationEpoch && connection == VehicleBridge.connectionGeneration &&
                (validationMode == null || activeValidationMode() != null)) {
                HvacDiagnostics.read(key, raw, raw != null && validReading(c, key, raw),
                    "INT_READ zone=$actualZone", observedAt)
            }
        }
        if (group == VehicleRefreshGroup.CLIMATE && evidenceOn()) evidencePoll(c)
        val unavailable = if (group == VehicleRefreshGroup.COMFORT) readUnavailableComfort(c, forceProbe, epoch, connection) else null
        return GroupReading(values, present, temperatures = temperatures, validationEpoch = epoch,
            unavailableComfort = unavailable, powerRaw = powerRaw)
    }

    private fun readUnavailableComfort(c: Context, forceProbe: Boolean, epoch: Long, connection: Long): Int? {
        val now = SystemClock.elapsedRealtime()
        if (!forceProbe && connection == comfortProbeConnection && now < nextComfortProbeAt) return null
        var unavailable = 0
        for (property in comfortCapabilities) {
            if (epoch != validationEpoch || connection != VehicleBridge.connectionGeneration ||
                !VehicleBridge.isReady || !Prefs.vehicleApiEnabled(c) || Prefs.simulate(c) ||
                (validationMode != null && activeValidationMode() == null)) return null
            val function = VehicleChannels.observationFuncOf(c, property.key)
            // 方向盘按原厂查询全局区；座椅查询配置中的各自区域，不借另一侧读回推断。
            val area = VehicleChannels.observationZoneOf(c, property.key)
            val requireArea = property != HvacProperty.STEERING_HEAT
            if (function > 0 && VehicleBridge.isComfortUnavailable(function, area, requireArea)) {
                unavailable = unavailable or property.mask
            }
        }
        comfortProbeConnection = connection
        nextComfortProbeAt = SystemClock.elapsedRealtime() + VehicleRefreshGroup.COMFORT.intervalMs
        return unavailable
    }

    private fun clearStaleComfortVisibility(c: Context) {
        if (state.unavailableComfort == 0) return
        if (comfortVisibilityConnection != VehicleBridge.connectionGeneration ||
            !VehicleBridge.isReady || !Prefs.vehicleApiEnabled(c) || Prefs.simulate(c)) {
            update(invalidateReads = false) { it.copy(unavailableComfort = 0) }
        }
    }

    private fun applyReading(
        c: Context,
        group: VehicleRefreshGroup,
        revision: Long,
        connection: Long,
        reading: GroupReading,
        waitMs: Long,
        ioMs: Long
    ) {
        if (!Prefs.vehicleApiEnabled(c)) return
        clearStaleComfortVisibility(c)
        // 档位事件可能使同组的值过期，但不能使独立的能力查询失效。
        if (!Prefs.simulate(c) && VehicleBridge.isReady && connection == VehicleBridge.connectionGeneration) {
            reading.unavailableComfort?.let { unavailable ->
                comfortVisibilityConnection = connection
                if (state.unavailableComfort != unavailable) {
                    update(invalidateReads = false) { it.copy(unavailableComfort = unavailable) }
                }
            }
        }
        // 同组回调或用户操作比本次读取新时，整组旧结果不得覆盖新状态。
        if (refreshRevisions[group.ordinal] != revision) return
        val simulated = Prefs.simulate(c)
        val connected = simulated || VehicleBridge.isReady
        val backend = if (simulated) "模拟数据" else if (connected) VehicleBridge.backend.label else "未连接"
        val previous = state
        var sensorsChanged = false
        if (group == VehicleRefreshGroup.SENSORS && !simulated) {
            reading.ignition?.let {
                if (ignitionState != it) { ignitionState = it; sensorsChanged = true }
            }
            reading.indoor?.takeIf { it.isFinite() && it > -40f && it < 90f }?.let {
                if (cabinTempC == null || kotlin.math.abs(cabinTempC!! - it) >= 0.05f) {
                    cabinTempC = it
                    sensorsChanged = true
                }
            }
            reading.ambient?.takeIf { it.isFinite() && it > -40f && it < 90f }?.let {
                if (ambientTempC != it) { ambientTempC = it; sensorsChanged = true }
            }
        }
        val power = reading.powerRaw
        val powerChanged = power != null && reading.validationEpoch == validationEpoch && connected && !simulated &&
            observePower(power, reading.validationEpoch, connection)
        update(forceNotify = sensorsChanged || powerChanged, invalidateReads = false, observedProperties = readingProperties(c, group, reading)) { current ->
            var next = current
            if (connected && !simulated) {
                val keys = refreshKeys[group.ordinal]
                for (i in keys.indices) {
                    val temperature = reading.temperatures?.getOrNull(i)
                    if (temperature != null && temperature.isFinite() &&
                        reading.validationEpoch == validationEpoch
                    ) {
                        next = if (keys[i] == "temp_driver") {
                            if (next.driverTempC == temperature) next else next.copy(driverTempC = temperature)
                        } else {
                            if (next.passengerTempC == temperature) next else next.copy(passengerTempC = temperature)
                        }
                        continue
                    }
                    if (reading.present and (1 shl i) == 0) continue
                    val raw = reading.values[i]
                    if (HvacChannels.isSentinel(raw)) continue
                    next = when (keys[i]) {
                        "defrost_front" -> HvacChannels.boolValue(raw)?.let { next.copy(defrostFront = it) } ?: next
                        "defrost_rear" -> HvacChannels.boolValue(raw)?.let { next.copy(defrostRear = it) } ?: next
                        "steering_heat" -> {
                            val level = raw and 0xFF
                            if (level in 0..SEAT_LEVEL_MAX) next.copy(steeringHeat = level) else next
                        }
                        else -> mergeReading(next, keys[i], raw, c)
                    }
                }
            }
            if (next.connected != connected || next.backend != backend) {
                next = next.copy(connected = connected, backend = backend)
            }
            next
        }
        if (group == VehicleRefreshGroup.SENSORS && connected && !simulated) applyAmbientSeatOnce(c)
        val now = SystemClock.elapsedRealtime()
        if (previous != state || sensorsChanged || now >= nextRefreshLogAt[group.ordinal]) {
            nextRefreshLogAt[group.ordinal] = now + FALLBACK_PROBE_INTERVAL_MS
            Logx.d(buildString {
                append("车控分组刷新 ").append(group).append(" wait=").append(waitMs)
                    .append("ms io=").append(ioMs).append("ms raw:")
                val keys = refreshKeys[group.ordinal]
                for (i in keys.indices) {
                    if (reading.present and (1 shl i) != 0) {
                        append(' ').append(keys[i]).append("=0x").append(reading.values[i].toString(16))
                    }
                }
                append(" | 风速=").append(state.fanLevel).append(if (state.fanAuto) "(AUTO)" else "")
            })
        }
    }


    private fun floatingConfirmation(key: String): Boolean {
        if (temperatureConfirmationConnection != VehicleBridge.connectionGeneration) return false
        val deadline = when (key) {
            "temp_driver" -> driverFloatConfirmationUntil
            "temp_passenger" -> passengerFloatConfirmationUntil
            else -> return false
        }
        return SystemClock.elapsedRealtime() < deadline
    }

    private fun temperatureNeedsConfirmation(now: Long): Boolean {
        if (temperatureConfirmationConnection != VehicleBridge.connectionGeneration) return false
        val pending = hvacState.pendingProperties
        return (pending and HvacProperty.TEMP_DRIVER.mask != 0 && now < driverConfirmationUntil) ||
            (pending and HvacProperty.TEMP_PASSENGER.mask != 0 && now < passengerConfirmationUntil)
    }

    /** 确认期限从实际写动作完成后起算；不把排队当已经发出。 */
    private fun completedTemperatureWrite(ctx: Context, driver: Boolean, sequence: Long, connection: Long, floating: Boolean) {
        onMain {
            if (connection != VehicleBridge.connectionGeneration || !Prefs.vehicleApiEnabled(ctx) || Prefs.simulate(ctx)) return@onMain
            val now = SystemClock.elapsedRealtime()
            if (temperatureConfirmationConnection != connection) {
                temperatureConfirmationConnection = connection
                driverConfirmationUntil = 0L
                passengerConfirmationUntil = 0L
                driverFloatConfirmationUntil = 0L
                passengerFloatConfirmationUntil = 0L
            }
            val deadline = now + 3500L
            val property = if (driver) HvacProperty.TEMP_DRIVER else HvacProperty.TEMP_PASSENGER
            if (driver) {
                driverConfirmationUntil = deadline
                if (floating) driverFloatConfirmationUntil = deadline
            } else {
                passengerConfirmationUntil = deadline
                if (floating) passengerFloatConfirmationUntil = deadline
            }
            hvacState.renew(sequence, property.mask, now, connection)
            publishDisplay()
            scheduleRefresh(ctx, TEMPERATURE_GROUP)
        }
    }

    private fun sendTemperatureTarget(ctx: Context, driver: Boolean, target: Float, sequence: Long) {
        val connection = VehicleBridge.connectionGeneration
        val writes = HvacCommands.operationNativeWrites()
        val floats = HvacCommands.operationFloatWrites()
        HvacWriteActions.writeTemperature(ctx, driver, target)
        if (HvacCommands.operationNativeWrites() > writes) {
            completedTemperatureWrite(ctx, driver, sequence, connection, HvacCommands.operationFloatWrites() > floats)
        }
    }

    private fun sendIntegerTemperatureStep(ctx: Context, driver: Boolean, direction: Int, sync: Boolean, sequence: Long) {
        val connection = VehicleBridge.connectionGeneration
        val writes = HvacCommands.operationNativeWrites()
        HvacWriteActions.writeIntegerTemperatureStep(ctx, driver, direction, sync)
        if (HvacCommands.operationNativeWrites() > writes) {
            completedTemperatureWrite(ctx, driver, sequence, connection, false)
            if (sync) completedTemperatureWrite(ctx, !driver, sequence, connection, false)
        }
    }


    private fun readingProperties(c: Context, group: VehicleRefreshGroup, reading: GroupReading): Int {
        var properties = 0
        val keys = refreshKeys[group.ordinal]
        for (i in keys.indices) {
            val temperature = reading.temperatures?.getOrNull(i)
            val valid = if (temperature != null && temperature.isFinite()) true
            else if (reading.present and (1 shl i) != 0) when (keys[i]) {
                "defrost_front", "defrost_rear" -> !HvacChannels.isSentinel(reading.values[i]) && HvacChannels.boolValue(reading.values[i]) != null
                "steering_heat" -> !HvacChannels.isSentinel(reading.values[i]) && (reading.values[i] and 0xff) in 0..SEAT_LEVEL_MAX
                else -> validReading(c, keys[i], reading.values[i])
            } else false
            if (valid) {
                val raw: Any? = reading.temperatures?.getOrNull(i)?.takeIf { it.isFinite() } ?: reading.values[i]
                properties = properties or HvacChannels.observationProperties(keys[i], raw)
            }
        }
        return properties
    }
    private fun groupMaskOf(key: String): Int = when (key) {
        "fan_manual", "fan_auto" -> FAN_GROUP
        "recirc", "air_direction", "ac", "temp_dual" -> CLIMATE_GROUP
        "temp_driver", "temp_passenger" -> TEMPERATURE_GROUP
        "seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger",
        "defrost_front", "defrost_rear", "steering_heat" -> COMFORT_GROUP
        else -> 0
    }


    private data class ChannelRead(val value: Int?, val zone: Int)
    private data class ReadZone(val function: Int, val configured: Int, val actual: Int)
    private val readZoneCache = HashMap<String, ReadZone>()
    private var readZoneConnection = Long.MIN_VALUE
    /** 沿用当前真实读取；日志记录逐次区域尝试，不把失败回退样本吞成最终成功。 */
    private fun readEvidenceInt(c: Context, key: String, fid: Int, configured: Int, zone: Int,
                                epoch: Long?, connection: Long, subscription: Long): Int? {
        val started = SystemClock.elapsedRealtime()
        val raw = VehicleBridge.readInt(fid, zone)
        if (epoch != null && epoch == validationEpoch && connection == VehicleBridge.connectionGeneration &&
            (validationMode == null || activeValidationMode() != null)) {
            HvacChannelEvidence.read(connection, subscription, key, fid, configured, zone, raw,
                raw != null && validReading(c, key, raw), "INT_READ", started, SystemClock.elapsedRealtime() - started)
        }
        return raw
    }

    /** 读取命中仅缓存本连接；绝不因读到值就改写控制区域配置。 */
    private fun read(key: String, ctx: Context, allowZoneFallback: Boolean = true, evidenceEpoch: Long? = null): ChannelRead {
        val f = VehicleChannels.funcOf(ctx, key)
        val configured = VehicleChannels.zoneOf(ctx, key)
        if (f <= 0) return ChannelRead(null, configured)
        val connection = VehicleBridge.connectionGeneration
        val subscription = watcherConfiguration
        if (connection != readZoneConnection) {
            readZoneCache.clear()
            readZoneConnection = connection
        }
        val cached = readZoneCache[key]?.takeIf { it.function == f && it.configured == configured }
        val firstZone = cached?.actual ?: configured
        val first = readEvidenceInt(ctx, key, f, configured, firstZone, evidenceEpoch, connection, subscription)
        if (connection != VehicleBridge.connectionGeneration) return ChannelRead(null, firstZone)
        if (first != null && validReading(ctx, key, first)) return ChannelRead(first, firstZone)
        readZoneCache.remove(key)
        if (firstZone != configured) {
            val primary = readEvidenceInt(ctx, key, f, configured, configured, evidenceEpoch, connection, subscription)
            if (connection != VehicleBridge.connectionGeneration) return ChannelRead(null, configured)
            if (primary != null && validReading(ctx, key, primary)) return ChannelRead(primary, configured)
        }
        if (!allowZoneFallback) return ChannelRead(first, firstZone)
        for (zone in HvacChannels.readZones(key, configured)) {
            if (zone == firstZone || (firstZone != configured && zone == configured)) continue
            val value = readEvidenceInt(ctx, key, f, configured, zone, evidenceEpoch, connection, subscription)
            if (connection != VehicleBridge.connectionGeneration) return ChannelRead(null, zone)
            if (value != null && validReading(ctx, key, value)) {
                readZoneCache[key] = ReadZone(f, configured, zone)
                Logx.d("HVAC_READ_ZONE key=$key configured=$configured actual=$zone（仅本连接读取缓存，不修改写入配置）")
                return ChannelRead(value, zone)
            }
        }
        return ChannelRead(first, firstZone)
    }

    /** 直读固定 funcId（不走 VehicleChannels 配置） */
    private fun readBare(func: Int): Int? = VehicleBridge.readInt(func, ZONE_GLOBAL)
    /** 设置绝对显示目标；厂商写与真实确认分别由共享命令和观测流程处理。 */
    fun setTempC(ctx: Context, driver: Boolean, target: Float) {
        if (Looper.myLooper() != main.looper) { main.post { setTempC(ctx, driver, target) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setTempC")) return
        if (!target.isFinite()) { Logx.w("设温未执行：目标不是有限温度"); return }
        val next = target.coerceIn(TEMP_MIN_C, TEMP_MAX_C)
        val key = if (driver) "temp_driver" else "temp_passenger"
        if (Prefs.simulate(ctx)) {
            preview(TEMPERATURE_GROUP) { current ->
                if (driver) current.copy(driverTempC = next, connected = true)
                else current.copy(passengerTempC = next, connected = true)
            }
            return
        }
        // ★★ v1.4.77：**直接写目标值**，不再「逐档步进」。
        //   原因：温度写的是**绝对设定温度**（float ℃），系统空调也是直接写目标值
        //   （读当前 +0.5 再写）。而旧实现把滑动跨 5℃ 拆成 10 次 tempStep，
        //   每次都要做多次 HAL 写 + 读 → 慢到来不及「选中」，用户以为没生效。
        val cur = (if (driver) state.driverTempC else state.passengerTempC) ?: Prefs.tempBase(ctx)
        if (Math.abs(next - cur) < 0.05f) {
            Logx.d("设温：已是 ${"%.1f".format(cur)}℃，无需调整")
            return
        }
        Logx.d("设温：${"%.1f".format(cur)}℃ → ${"%.1f".format(next)}℃（直接写目标值）")
        val sequence = preview(TEMPERATURE_GROUP) { current ->
            if (driver) current.copy(driverTempC = next) else current.copy(passengerTempC = next)
        }
        HvacCommands.submitLatest(ctx, key, sequence, { cancelTarget(sequence) }) { sendTemperatureTarget(ctx, driver, next, sequence) }
    }
    /** 双温同步使用真实状态作为目标，写入和写后回读均走共享车控队列。 */
    fun setTempDual(ctx: Context, on: Boolean) {
        if (Looper.myLooper() != main.looper) { main.post { setTempDual(ctx, on) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setTempDual")) return
        val sequence = preview(CLIMATE_GROUP) { current -> current.copy(tempDualOn = on) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "temp_dual target=$on targetSeq=$sequence", { cancelTarget(sequence) }) {
            if (!HvacWriteActions.writeDual(ctx, on)) cancelTarget(sequence)
        }
        scheduleRefresh(ctx, CLIMATE_GROUP)
    }

    /** ★ v1.4.35：前挡加热 开/关（0x10040100）*/
    fun toggleDefrostFront(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { toggleDefrostFront(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "toggleDefrostFront")) return
        val next = !state.defrostFront
        if (Prefs.simulate(ctx)) {
            preview(COMFORT_GROUP) { current -> current.copy(defrostFront = next, connected = true) }
            return
        }
        val sequence = preview(COMFORT_GROUP) { current -> current.copy(defrostFront = next) }   // 乐观更新
        // ★ v1.4.60：改走 writeSwitch（复合编码优先，裸值兜底）。
        //   旧实现 writeBareGuarded 只发裸值 0/1，真车 40 次全 FAIL。
        val ctxBg = ctx
        HvacCommands.submit(ctxBg, "defrost_front target=$next", { cancelTarget(sequence) }) { HvacWriteActions.writeSwitch(ctxBg, "defrost_front", FUNC_DEFROST_FRONT, ZONE_GLOBAL, next, "前挡加热") }
        scheduleRefresh(ctx, COMFORT_GROUP)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** ★ v1.4.35：后挡加热 开/关（0x10040300） */
    fun toggleDefrostRear(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { toggleDefrostRear(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "toggleDefrostRear")) return
        val next = !state.defrostRear
        if (Prefs.simulate(ctx)) {
            preview(COMFORT_GROUP) { current -> current.copy(defrostRear = next, connected = true) }
            return
        }
        val sequence = preview(COMFORT_GROUP) { current -> current.copy(defrostRear = next) }
        // ★ v1.4.60：同前挡，改走 writeSwitch（复合编码优先）
        val ctxBg = ctx
        HvacCommands.submit(ctxBg, "defrost_rear target=$next", { cancelTarget(sequence) }) { HvacWriteActions.writeSwitch(ctxBg, "defrost_rear", FUNC_DEFROST_REAR, ZONE_GLOBAL, next, "后挡加热") }
        scheduleRefresh(ctx, COMFORT_GROUP)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** ★ v1.4.35：方向盘加热 —— 默认关；打开即 3 档，再点 2 档 → 1 档 → 关（用户要求从高档往低调） */
    fun steeringHeatCycle(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { steeringHeatCycle(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "steeringHeatCycle")) return
        val next = if (state.steeringHeat <= 0) SEAT_LEVEL_MAX else state.steeringHeat - 1
        setSteeringHeatLevel(ctx, next)
    }

    /**
     * ★ v3.1：方向盘加热 —— 直接设到指定档位（0=关 1..3），供 Dock 条目「长按升档」用。
     * 通道：functionId=0x10090100（HVAC_FUNC_STEERING_WHEEL_HEAT），zone=1（主驾）。
     */
    fun setSteeringHeatLevel(ctx: Context, level: Int) {
        if (Looper.myLooper() != main.looper) { main.post { setSteeringHeatLevel(ctx, level) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setSteeringHeatLevel")) return
        val next = level.coerceIn(0, SEAT_LEVEL_MAX)
        if (next == state.steeringHeat) return
        if (Prefs.simulate(ctx)) {
            preview(COMFORT_GROUP) { current -> current.copy(steeringHeat = next, connected = true) }
            return
        }
        val sequence = preview(COMFORT_GROUP) { current -> current.copy(steeringHeat = next) }
        val ctxBg = ctx
        HvacCommands.submit(ctxBg, "steering_heat target=$next", { cancelTarget(sequence) }) {
            val ok = HvacWriteActions.writeChannel(ctxBg, "steering_heat", next)
            Logx.d("写 方向盘加热 idx=$next → ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
        }
        scheduleRefresh(ctx, COMFORT_GROUP)
    }
    /** 独立座椅记忆实验入口，位置偏好不等同车辆位置已确认。 */
    fun applySeatMemory(ctx: Context, pos: Int) {
        if (pos !in 1..3 || Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "seat_memory position=$pos") {
            var accepted = false
            for (zone in SEAT_MEMORY_ZONES) {
                val composite = (FUNC_SEAT_POSITION_SET and 0xffffff00.toInt()) or pos
                for (value in intArrayOf(pos, composite)) {
                    if (VehicleBridge.verifyWrite(FUNC_SEAT_POSITION_SET, zone, value)) { accepted = true; break }
                }
                if (accepted) break
            }
            if (accepted) Prefs.setSeatMemoryIndex(ctx, pos)
            Logx.d("座椅记忆 position=$pos accepted=$accepted（仅调用接受，真实位置仍待车辆证据）")
        }
        scheduleRefresh(ctx, COMFORT_GROUP)
    }

    /**
     * 读当前座椅记忆位置号（1/2/3），读不到返回 null。
     *
     * ★ v1.4.50：座椅弹窗原来只能「点了才高亮 1.5 秒」，看不出当前究竟在哪个位置。
     *   现在主动读 `SEAT_NUM (0x2d410f00)`。
     *
     * 取值编码未实测，故两种都认：
     *  - 复合编码 `0x2d410f01/02/03` → 取低 8 位
     *  - 裸值 `1/2/3`
     * zone 也逐个试（主驾 1 / 全局 0x80000000 / 前排 0x8），取第一个有效值。
     */
    fun readSeatMemoryIndex(ctx: Context): Int? {
        if (Prefs.simulate(ctx)) return null
        // ★★ v1.4.89：**优先读 SEAT_POSITION_SAVE (0x2d400100)** —— 这才是「当前在第几号位」。
        //   裸值 1/2/3 与复合编码 0x2d400101/02/03 都认，zone 逐个试。
        for (z in SEAT_MEMORY_ZONES) {
            val raw = VehicleBridge.readInt(FUNC_SEAT_POSITION_SAVE, z) ?: continue
            val n = if (raw > FUNC_SEAT_POSITION_SAVE) raw and 0xFF else raw
            if (n in 1..SEAT_MEMORY_SLOTS) {
                Logx.d(
                    "座椅记忆位置：读 SAVE 0x" + Integer.toHexString(FUNC_SEAT_POSITION_SAVE) +
                    " zone=0x" + Integer.toHexString(z) + " raw=0x" + Integer.toHexString(raw) +
                    " → 第 " + n + " 号位"
                )
                return n
            }
            Logx.d(
                "座椅记忆位置：SAVE zone=0x" + Integer.toHexString(z) +
                " raw=0x" + Integer.toHexString(raw) + " 不在 1..$SEAT_MEMORY_SLOTS"
            )
        }
        // ★ v1.4.64：复合编码兜底。
        //   本车其它通道（风量/循环/座椅通风加热）全部吃复合编码 `functionId | 值`，
        //   座椅记忆大概率同样 —— 值 1/2/3，即 0x2d410f01/02/03。
        //   同时 zone 扩大到 1 / 4 / 0x8 / 全局 四种。
        val zones = intArrayOf(1, ZONE_GLOBAL, 0x8, 4)
        for (z in zones) {
            val raw = VehicleBridge.readInt(FUNC_SEAT_NUM, z) ?: continue
            val n = if (raw > FUNC_SEAT_NUM) raw and 0xFF else raw
            if (n in 1..3) {
                Logx.d("座椅记忆位置：读 0x${FUNC_SEAT_NUM.toString(16)} zone=$z raw=0x${raw.toString(16)} → 位置 $n")
                return n
            }
        }
        // 复合编码：0x2d410f01 / 02 / 03
        for (n in 1..3) {
            val composite = (FUNC_SEAT_NUM and 0xFFFFFF00.toInt()) or n
            val raw = VehicleBridge.readInt(FUNC_SEAT_NUM, 1) ?: continue
            if (raw == composite || raw == n) {
                Logx.d("座椅记忆位置：复合编码命中 → 位置 $n")
                return n
            }
        }
        // ★ v1.4.74：0x2d410f00 读不到（该 ID 属于「设置」段，ECarX 侧可能不开放）。
        //   换个思路：ISensor 里有**座椅位置的连续量**（真车 ISensor 扫描实锤有值）：
        //     0x102600 主驾坐垫倾角 / 0x102700 主驾靠背倾角 / 0x102800 副驾座椅高度
        //     0x102900 副驾座椅前后 / 0x102A00 副驾坐垫倾角 / 0x102500 副驾座椅前后
        //   靠背/坐垫倾角在座椅记忆切换时变化最明显 → 用它做一个「变了就是换了位置」的启发式。
        val seatSig = seatSignature()
        if (seatSig != null) {
            Logx.d("座椅记忆位置：0x2d410f00 读不到，改用 ISensor 座椅特征 = $seatSig")
        }
        Logx.d("座椅记忆位置：未读到有效值（SEAT_NUM 0x${FUNC_SEAT_NUM.toString(16)}，" +
            "HAL 可能不开放该通道；已试裸值+复合 × 4 zone）")
        // ★ v1.4.97 #12：车机通道读不到 → 退回「上次我们自己调用过的记忆位」。
        //   这样座椅弹窗至少能正确高亮我们刚选的那一个，不再三个都灰着。
        val local = Prefs.seatMemoryIndex(ctx)
        if (local in 1..SEAT_MEMORY_SLOTS) {
            Logx.d("座椅记忆位置：车机读不到，用本地记录 → 第 $local 号位")
            return local
        }
        return null
    }

    /**
     * ★ v1.4.74：ISensor 座椅特征（主/副驾靠背与坐垫倾角组合）。
     * 座椅记忆切换时这些值会变 → 可用来判断「当前是不是记忆位 1/2/3」。
     */
    private fun seatSignature(): String? {
        val ids = intArrayOf(0x102600, 0x102700, 0x102800, 0x102900, 0x102A00, 0x102500)
        val vals = ids.map { SensorBridge.readFloat(it) }
        if (vals.all { it == null }) return null
        return vals.joinToString(",") { it?.let { v -> "%.0f".format(v) } ?: "-" }
    }
    /** 组合目标使用同一保序操作；各属性接受和观测结果分别记录。 */
    fun fastCool(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { fastCool(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "fastCool")) return
        HvacCommands.supersede("fan")
        val sequence = preview(FAN_GROUP or CLIMATE_GROUP or TEMPERATURE_GROUP) { it.copy(acOn = true, fanAuto = false, fanLevel = 9, fanMax = 9, driverTempC = TEMP_MIN_C, passengerTempC = TEMP_MIN_C) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "fast_cool targetSeq=$sequence", { cancelTarget(sequence) }) {
            HvacWriteActions.writeChannel(ctx, "ac", 1)
            HvacWriteActions.writeChannel(ctx, "fan_manual", 9)
            sendTemperatureTarget(ctx, true, TEMP_MIN_C, sequence)
            sendTemperatureTarget(ctx, false, TEMP_MIN_C, sequence)
        }
        scheduleRefresh(ctx, FAN_GROUP or CLIMATE_GROUP or TEMPERATURE_GROUP)
    }
    /** 组合目标使用同一保序操作；各属性接受和观测结果分别记录。 */
    fun fastHeat(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { fastHeat(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "fastHeat")) return
        HvacCommands.supersede("fan")
        val sequence = preview(FAN_GROUP or TEMPERATURE_GROUP) { it.copy(fanAuto = false, fanLevel = 9, fanMax = 9, driverTempC = TEMP_MAX_C, passengerTempC = TEMP_MAX_C) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "fast_heat targetSeq=$sequence", { cancelTarget(sequence) }) {
            HvacWriteActions.writeChannel(ctx, "fan_manual", 9)
            sendTemperatureTarget(ctx, true, TEMP_MAX_C, sequence)
            sendTemperatureTarget(ctx, false, TEMP_MAX_C, sequence)
        }
        scheduleRefresh(ctx, FAN_GROUP or TEMPERATURE_GROUP)
    }

    /** ★ v1.4.35：副驾座椅通风档位（0..3，通道 seat_vent_passenger / zone=4） */
    fun setSeatVentLevelPassenger(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_vent_passenger", level,
        set = { s, v -> s.copy(seatVentPassenger = v) },
        label = "副驾座椅通风"
    )

    /** ★ v1.4.35：副驾座椅加热档位（0..3，通道 seat_heat_passenger / zone=4） */
    fun setSeatHeatLevelPassenger(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_heat_passenger", level,
        set = { s, v -> s.copy(seatHeatPassenger = v) },
        label = "副驾座椅加热"
    )

    // =====================================================================
    // 操作
    // =====================================================================


    // =====================================================================
    // 通道扫描（找真实 functionId —— 内外循环读回 0xff 时的唯一解）
    // =====================================================================

    /**
     * ★★ v1.4.52：暴力扫描车控通道，把「哪些 functionId 真的有值」列出来。
     *
     * 为什么必须扫：内外循环 0x10030100 在本车**读回恒为 0xff**（HAL 没暴露），
     * 写也全部 FAIL —— 强烈提示 functionId 本身不对（oneOS_Dock 那张表是别的车型/版本）。
     * 与其猜，不如把整片 ID 空间读一遍：读通道是安全操作（读不到只返回哨兵，不会崩）。
     *
     * 扫描范围：`0x10000000` 起，步长 `0x100`，共 `count` 个；每个 ID 在 [zones] 里逐个试。
     * 判定「有效」：返回非 null 且不是 0xff / 0xfe / 0xfd 哨兵。
     *
     * 结果打进日志并可由调用方上传。
     */
    fun scanChannels(
        ctx: Context,
        zones: IntArray = intArrayOf(0x8, 1, 4),
        startFunc: Int = 0x10000000,
        count: Int = 0xA00,          // 0x10000000 .. 0x100A0000
        step: Int = 0x100
    ): String {
        if (!Prefs.vehicleApiEnabled(ctx)) return "车控通道已关闭，无法扫描"
        if (!VehicleBridge.isReady) return "车控未连接，无法扫描"
        val t0 = android.os.SystemClock.elapsedRealtime()
        val hits = mutableListOf<String>()
        var tried = 0
        for (i in 0 until count) {
            val f = startFunc + i * step
            for (z in zones) {
                tried++
                val v = VehicleBridge.readInt(f, z) ?: continue
                if (v == 0xff || v == 0xfe || v == 0xfd) continue
                // 复合编码（func|n）与裸值都算命中；标注出来方便判读
                val kind = when {
                    v > f && (v and 0xFFFFFF00.toInt()) == (f and 0xFFFFFF00.toInt()) -> "复合 n=${v and 0xFF}"
                    v == 0 -> "0"
                    else -> "裸值"
                }
                hits.add("0x%08X zone=0x%X → 0x%08X (%d) [%s]".format(f, z, v, v, kind))
            }
        }
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        return buildString {
            append("===== 车控通道扫描 =====\n")
            append("范围 0x").append(startFunc.toString(16)).append(" 起 ")
                .append(count).append(" 个 ID × ").append(zones.size).append(" 个 zone，")
            append("共 ").append(tried).append(" 次读，耗时 ").append(ms).append("ms\n\n")
            if (hits.isEmpty()) {
                append("★ 没有任何通道返回有效值（全是 0xff 哨兵）\n")
            } else {
                append("命中 ").append(hits.size).append(" 条：\n")
                hits.forEach { append("  ").append(it).append('\n') }
            }
            append("\n提示：对照这张表，把「内外循环 / 温度」的 functionId 改到真有值的那个 ID 上\n")
            append("（主界面 → 车控通道 → 长按条目改数字保存）。\n")
            append("========================\n")
        }
    }

    /** ★ v1.4.90：给调温调用栈日志用的当前状态描述 */
    private fun stateMachineStateName(): String =
        "connected=" + state.connected + " auto=" + state.fanAuto + " recirc=" + state.recircInside

    /**
     * 只在第一次读到有效车外温度时做一次：
     * 高于 25℃ 开主驾通风 1 档；低于 15℃ 开主驾和副驾加热 1 档。
     * 开关关掉的那一项跳过。15～25℃ 什么都不开，同样记为已处理。
     */
    private fun applyAmbientSeatOnce(ctx: Context) {
        if (Prefs.ambientSeatDone(ctx)) return
        val t = ambientTempC ?: return
        Prefs.setAmbientSeatDone(ctx, true)
        when {
            t > 25f && Prefs.autoSeatVentHot(ctx) && state.seatVent <= 0 -> {
                Logx.d("首次启动：车外 ${t}℃ > 25 → 主驾座椅通风 1 档")
                setSeatVentLevel(ctx, 1)
            }
            t < 15f && Prefs.autoSeatHeatCold(ctx) -> {
                Logx.d("首次启动：车外 ${t}℃ < 15 → 主驾/副驾座椅加热 1 档")
                if (state.seatHeat <= 0) setSeatHeatLevel(ctx, 1)
                if (state.seatHeatPassenger <= 0) setSeatHeatLevelPassenger(ctx, 1)
            }
            else -> Logx.d("首次启动：车外 ${t}℃，座椅保持原状")
        }
    }

    /** ★ v1.4.74：车外环境温度（ISensor 0x100B00） */
    @Volatile
    var ambientTempC: Float? = null
        private set

    /**
     * ★ v1.4.75：**车内实际温度**（ISensor 0x100C00，座舱环境温度）。
     * 与「空调设定温度」（driverTempC/passengerTempC）是两回事，不可混用。
     */
    @Volatile
    var cabinTempC: Float? = null
        private set

    /** ★ v1.4.72：最近一次读到的点火状态（null = 读不到） */
    @Volatile
    var ignitionState: Int? = null
        private set

    /** 点火状态文字（熄火 / ACC / 点火 / 启动中；读不到显示 "--"） */
    fun ignitionLabel(): String =
        ignitionState?.let { SensorBridge.Ignition.label(it) } ?: "--"

    /** 当前是否已点火（ACC/ON/START 都算） */
    fun isIgnitionOn(): Boolean =
        ignitionState != null && ignitionState != SensorBridge.Ignition.OFF

    fun tempStep(ctx: Context, driver: Boolean, step: Int): Boolean {
        if (Looper.myLooper() != main.looper) { main.post { tempStep(ctx, driver, step) }; return true }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "tempStep")) return false
        val key = if (driver) "temp_driver" else "temp_passenger"
        val current = (if (driver) state.driverTempC else state.passengerTempC) ?: Prefs.tempBase(ctx)
        val next = (current + step * Prefs.tempStep(ctx)).coerceIn(TEMP_MIN_C, TEMP_MAX_C)
        if (next == current || !next.isFinite()) return false
        val sync = state.tempDualOn == true
        val sequence = preview(TEMPERATURE_GROUP) { s ->
            when {
                sync -> s.copy(driverTempC = next, passengerTempC = next)
                driver -> s.copy(driverTempC = next)
                else -> s.copy(passengerTempC = next)
            }
        }
        if (Prefs.simulate(ctx)) return true
        if (Prefs.tempCustomizeWriteEnabled(ctx)) {
            val action = {
                sendTemperatureTarget(ctx, driver, next, sequence)
                if (sync) sendTemperatureTarget(ctx, !driver, next, sequence)
            }
            if (sync) HvacCommands.submit(ctx, "temperature_sync targetSeq=$sequence", { cancelTarget(sequence) }, action)
            else HvacCommands.submitLatest(ctx, key, sequence, { cancelTarget(sequence) }, action)
        } else {
            // 旧整数步进不是可替换绝对命令，逐次执行，避免合并后丢步数。
            val direction = if (step > 0) 1 else 2
            HvacCommands.submit(ctx, "$key legacy_step=$direction targetSeq=$sequence", { cancelTarget(sequence) }) {
                sendIntegerTemperatureStep(ctx, driver, direction, sync, sequence)
            }
        }
        return true
    }
    /** 风量模式与对应目标在同一保序操作内发送，各子调用独立记录实际结果。 */
    fun toggleFanAuto(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { toggleFanAuto(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "toggleFanAuto")) return
        val auto = !state.fanAuto
        val level = if (auto) Prefs.fanAutoLevel(ctx).coerceIn(1, 5) else 3
        val key = if (auto) "fan_auto" else "fan_manual"
        if (!Prefs.simulate(ctx) && VehicleChannels.funcOf(ctx, key) <= 0) return
        HvacCommands.supersede("fan")
        val sequence = preview(FAN_GROUP) { it.copy(fanAuto = auto, fanLevel = level, fanMax = if (auto) 5 else 9) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "fan_mode auto=$auto level=$level targetSeq=$sequence", { cancelTarget(sequence) }) {
            val master = VehicleChannels.funcOf(ctx, "auto_master")
            if (master > 0) HvacWriteActions.writeSwitch(ctx, "auto_master", master,
                VehicleChannels.zoneOf(ctx, "auto_master"), auto, "AUTO 总开关", bareFirst = true)
            HvacWriteActions.writeChannel(ctx, key, level)
        }
        scheduleRefresh(ctx, FAN_GROUP)
    }
    /** 增减先累计显示目标；仅未执行的绝对风量目标可被后续目标替换。 */
    fun fanStep(ctx: Context, delta: Int) {
        if (Looper.myLooper() != main.looper) { main.post { fanStep(ctx, delta) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "fanStep")) return
        val auto = state.fanAuto
        val current = if (auto) state.fanLevel.takeIf { it in 1..5 } ?: Prefs.fanAutoLevel(ctx) else state.fanLevel
        val next = (current.toLong() + delta).coerceIn(if (auto) 1L else 0L, if (auto) 5L else 9L).toInt()
        if (next == current) return
        if (auto) { setFanAutoLevel(ctx, next); return }
        val sequence = preview(FAN_GROUP) { it.copy(fanLevel = next, fanAuto = false, fanMax = 9) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submitLatest(ctx, "fan", sequence, { cancelTarget(sequence) }) {
            HvacWriteActions.writeChannel(ctx, "fan_manual", next)
        }
        scheduleRefresh(ctx, FAN_GROUP)
    }
    /** 循环目标只用已验证值；实际结果由事件或定向回读提供。 */
    fun toggleRecirc(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { toggleRecirc(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "toggleRecirc")) return
        val target = !state.recircInside
        val sequence = preview(CLIMATE_GROUP) { it.copy(recircInside = target) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "recirc target=$target targetSeq=$sequence", { cancelTarget(sequence) }) {
            HvacWriteActions.writeRecirculation(ctx, target)
        }
        scheduleRefresh(ctx, CLIMATE_GROUP)
    }


    /**
     * ★ v3.13.15：**严格按文档判定**读回来的循环状态。
     *
     * 文档原文：「内循环的判断条件是返回值**精确等于 0x10030101**；
     * **未知或非激活值不应当作外循环确认**。」
     *
     * ⇒ 内循环 = 精确 0x10030101；外循环 = **精确裸值 0**；
     *   其它（0xff / 0x10030100 / 1 / 10 / 11 …）一律 = **未知（null）**。
     *
     * ⚠ 与旧实现的差别：旧版按低字节判（low==0 → 外），会把 **0x10030100**
     *   （低字节 0 但高位非零、HAL 根本不认的非激活值）误判成「外循环已生效」。
     */
    private fun classifyRecirc(f: Int, raw: Int?): Boolean? {
        if (raw == null) return null
        if (raw == 0) return false                                  // 外循环（裸值 0）
        if (raw == ((f and 0xFFFFFF00.toInt()) or 1)) return true   // 内循环（0x10030101）
        return null                                                 // 未知 / 非激活值
    }
    /** 设置专用 AUTO 档位，编码与观测确认由统一模块处理。 */
    fun setFanAutoLevel(ctx: Context, level: Int) {
        if (Looper.myLooper() != main.looper) { main.post { setFanAutoLevel(ctx, level) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setFanAutoLevel")) return
        val next = level.coerceIn(1, 5)
        Prefs.setFanAutoLevel(ctx, next)
        val sequence = preview(FAN_GROUP) { it.copy(fanAuto = true, fanLevel = next, fanMax = 5) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submitLatest(ctx, "fan", sequence, { cancelTarget(sequence) }) {
            HvacWriteActions.writeChannel(ctx, "fan_auto", next)
        }
        scheduleRefresh(ctx, FAN_GROUP)
    }

    /** 把回读的循环状态转成文字（true=内循环 false=外循环 null=读不到） */
    private fun recircText(back: Boolean?): String = when (back) {
        true -> "内循环"
        false -> "外循环"
        null -> "读不到"
    }

    // =====================================================================
    // ★ v3.7 取证窗口（默认关闭）
    // =====================================================================

    /**
     * 取证窗口的截止时间戳（`System.currentTimeMillis()`）。
     * **默认 0 = 关闭** —— 平时不产生任何额外开销、不打任何取证日志、
     * 也不注册任何额外的 watcher 通道。
     *
     * 只在用户点「抓循环取证（系统 Dock/空调 App）」按钮后才打开，
     * 窗口内才做「额外取证动作」，到期自动失效。
     */
    @Volatile
    private var evidenceUntil = 0L

    /** 取证窗口是否开启 */
    fun evidenceOn(): Boolean = evidenceUntil > System.currentTimeMillis()

    /**
     * 打开取证窗口。
     * @param durationMs 窗口时长（默认 2 分钟）
     */
    fun openEvidence(durationMs: Long = 120_000L) {
        evidenceUntil = System.currentTimeMillis() + durationMs
        Logx.d("★ 取证窗口已开启：${durationMs / 1000} 秒内做取证（到期自动关闭）")
    }

    /** 手动关闭取证窗口 */
    fun closeEvidence() {
        evidenceUntil = 0L
        Logx.d("★ 取证窗口已关闭")
    }

    /**
     * 取证窗口内的**主动轮询读**：把循环 / AUTO 总开关的原始值打出来。
     *
     * 为什么要主动读而不是只靠 watcher 回调：
     *   · watcher 在本车**默认是关闭的**（`Prefs.vehicleWatcherEnabled`，
     *     因为「收到第一次回调时原生层可能拖崩进程」）；
     *   · 就算开着，回调给的是**回调时刻的值**，可能已经变了。
     *   → 主动读能拿到「此刻」的确切原始值，是最硬的证据。
     *
     * ⚠ 只在窗口内调用；窗口外一次 binder 调用都不多。
     */
    fun evidencePoll(ctx: Context) {
        if (!evidenceOn()) return
        val c = appCtx ?: ctx
        // ① 内外循环：把几个候选 zone 都读一遍（看哪个 zone 有值、值是什么形态）
        val f = VehicleChannels.FUNC_CIRCULATION
        for (z in intArrayOf(ZONE_GLOBAL, 0x8)) {
            val raw = VehicleBridge.readInt(f, z)
            if (raw == null) continue
            val hex = "0x" + Integer.toHexString(raw)
            Logx.d(
                "★ 取证[轮询]：内外循环 zone=0x${z.toString(16)} value=$raw ($hex) " +
                "→ ${recircTextOf(raw)}"
            )
        }
        // ② AUTO 总开关（0x10010200，全局 zone）
        val rawAuto = VehicleBridge.readInt(VehicleChannels.FUNC_AUTO, ZONE_GLOBAL)
        if (rawAuto != null) {
            Logx.d(
                "★ 取证[轮询]：AUTO总开关 zone=0x${ZONE_GLOBAL.toString(16)} " +
                "value=$rawAuto (0x${Integer.toHexString(rawAuto)})"
            )
        }
    }

    /** 按系统权威值域判断一个原始值代表什么（外/内/看不懂） */
    private fun recircTextOf(raw: Int): String = when {
        raw == 0 -> "外循环（裸值 0）"
        raw == 0x10030101 -> "内循环（复合 0x10030101）"
        raw == 0x10030100 -> "外循环？复合 0x10030100（HAL 多半不认）"
        (raw and 0xFF) == 0 -> "外循环（低字节 0）"
        (raw and 0xFF) == 1 -> "内循环（低字节 1）"
        (raw and 0xFF) == 10 -> "外循环（10/11 枚举）"
        (raw and 0xFF) == 11 -> "内循环（10/11 枚举）"
        else -> "看不懂"
    }

    /** 读当前内外循环状态（true=内循环，false=外循环，null=读不到） */
    private fun readRecircState(ctx: Context): Boolean? {
        val f = VehicleChannels.funcOf(ctx, "recirc")
        if (f <= 0) return null
        // ★ v3.13.15：优先用**文档口径的无 area 读** getFunctionValue(functionId)
        VehicleBridge.readIntNoArea(f)?.let { return classifyRecirc(f, it) }
        // ★ v3.13.6：recirc 的 zone 已是权威的全局 0x80000000（zoneOf 对 recirc 强制返回它），
        //   这里仍保留 0x8 / 1 作兜底（万一某些车机版本读的是分区 zone）。
        val zones = intArrayOf(ZONE_GLOBAL, 0x8, 1)
        for (z in zones) {
            val raw = VehicleBridge.readInt(f, z) ?: continue
            // ★ v3.13.15：**严格判定**（文档口径）：只有精确 0 / 0x10030101 才算数，
            //   其它值（0xff / 0x10030100 / 1 / 10 / 11 …）= 未知，继续试下一个 zone。
            val st = classifyRecirc(f, raw)
            if (st != null) return st
        }
        return null
    }
    /** AC 目标与真实状态独立，不从厂商布尔返回推断车辆效果。 */
    fun toggleAc(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { toggleAc(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "toggleAc")) return
        val target = !state.acOn
        val sequence = preview(CLIMATE_GROUP) { it.copy(acOn = target) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "ac target=$target targetSeq=$sequence", { cancelTarget(sequence) }) {
            HvacWriteActions.writeChannel(ctx, "ac", if (target) 1 else 0)
        }
        scheduleRefresh(ctx, CLIMATE_GROUP)
    }
    /** OFF 是明确绝对目标；关闭写入时不制造车辆状态。 */
    /** 只停风；恢复时使用同一车控连接内的原档位与风向，不切换 A/C 或 AUTO 总开关。 */
    fun toggleAirflow(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { toggleAirflow(ctx) }; return }
        val simulated = Prefs.simulate(ctx)
        if (!simulated && !HvacCommands.canSubmit(ctx, "toggleAirflow")) return
        appCtx = ctx.applicationContext
        val current = state
        val stop = current.fanAuto || current.fanLevel > 0
        val connection = VehicleBridge.connectionGeneration
        val resume = if (stop) {
            val level = if (current.fanAuto && current.fanLevel !in 1..5) {
                Prefs.fanAutoLevel(ctx).coerceIn(1, 5)
            } else current.fanLevel
            AirflowResume(level, current.fanAuto, current.airDirection, connection, simulated)
        } else {
            airflowResume?.takeIf { it.connection == connection && it.simulated == simulated } ?: return
        }
        val auto = !stop && resume.auto
        val level = if (stop) 0 else resume.level
        val key = if (auto) "fan_auto" else "fan_manual"
        if (!simulated && VehicleChannels.funcOf(ctx, key) <= 0) return
        val direction = resume.direction.takeIf { !stop && it in 1..7 && it != current.airDirection }
        val groups = if (direction == null) FAN_GROUP else FAN_GROUP or CLIMATE_GROUP
        if (stop) airflowResume = resume
        HvacCommands.supersede("fan")
        val sequence = preview(groups) {
            it.copy(fanLevel = level, fanAuto = auto, fanMax = if (auto) 5 else 9,
                airDirection = direction ?: it.airDirection)
        }
        if (simulated) return
        val cancel = {
            onMain {
                if (stop && airflowResume === resume) airflowResume = null
                cancelTarget(sequence)
            }
        }
        HvacCommands.submit(ctx, "airflow $key=$level direction=$direction targetSeq=$sequence", cancel) {
            if (connection != VehicleBridge.connectionGeneration) {
                cancel()
                return@submit
            }
            if (!HvacWriteActions.writeChannel(ctx, key, level)) cancel()
            else if (direction != null && (connection != VehicleBridge.connectionGeneration ||
                    !HvacWriteActions.writeChannel(ctx, "air_direction", direction))) {
                cancelTarget(sequence, HvacProperty.AIR_DIRECTION.mask)
            }
            scheduleRefresh(ctx, groups)
        }
    }

    fun setFanOff(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { setFanOff(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setFanOff")) return
        HvacCommands.supersede("fan")
        val sequence = preview(FAN_GROUP) { it.copy(fanLevel = 0, fanAuto = false, fanMax = 9) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submitLatest(ctx, "fan", sequence, { cancelTarget(sequence) }) {
            HvacWriteActions.writeChannel(ctx, "fan_manual", 0)
        }
        scheduleRefresh(ctx, FAN_GROUP)
    }

    /** 风向循环切换（按 7 种组合位掩码循环） */
    fun cycleAirDirection(ctx: Context) {
        val order = AIR_MODES
        val cur = state.airDirection
        val idx = order.indexOf(cur)
        val next = if (idx < 0) order.first() else order[(idx + 1) % order.size]
        setAirDirection(ctx, next)
    }
    /** 风向仍保留已知 1..7 组合；未知厂商模式不猜测。 */
    fun setAirDirection(ctx: Context, bits: Int) {
        if (Looper.myLooper() != main.looper) { main.post { setAirDirection(ctx, bits) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setAirDirection")) return
        val next = bits and 0x07
        if (next == 0) return
        val sequence = preview(CLIMATE_GROUP) { it.copy(airDirection = next) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "air_direction target=$next targetSeq=$sequence", { cancelTarget(sequence) }) {
            HvacWriteActions.writeChannel(ctx, "air_direction", next)
        }
        scheduleRefresh(ctx, CLIMATE_GROUP)
    }

    /** 主驾座椅通风 ±1 档（0 = 关） */
    fun seatVentStep(ctx: Context, delta: Int) {
        val next = (state.seatVent + delta).coerceIn(0, SEAT_LEVEL_MAX)
        if (next == state.seatVent) return
        setSeatVentLevel(ctx, next)
    }

    /** 主驾座椅加热 ±1 档（0 = 关） */
    fun seatHeatStep(ctx: Context, delta: Int) {
        val next = (state.seatHeat + delta).coerceIn(0, SEAT_LEVEL_MAX)
        if (next == state.seatHeat) return
        setSeatHeatLevel(ctx, next)
    }

    /**
     * 主驾座椅通风：直接设到指定档位（0=关 1=弱 2=中 3=强），供「高档→低档」循环用。
     * ★ 通道：functionId=0x10050100（HVAC_FUNC_SEAT_VENTILATION），zone=1（主驾）
     *   取值 = functionId + 档位。
     */
    fun setSeatVentLevel(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_vent", level,
        set = { s, v -> s.copy(seatVent = v) },
        label = "座椅通风"
    )

    /**
     * 主驾座椅加热：直接设到指定档位（0=关 1..3）。
     * ★ 通道：functionId=0x10050200（HVAC_FUNC_SEAT_HEATING），zone=1（主驾）
     */
    fun setSeatHeatLevel(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_heat", level,
        set = { s, v -> s.copy(seatHeat = v) },
        label = "座椅加热"
    )

    /** 默认只写所选档位；同侧关闭时独立提交加热和通风的关闭请求。 */
    fun setSeatComfortLevel(ctx: Context, driver: Boolean, heating: Boolean, level: Int) {
        if (Looper.myLooper() != main.looper) { main.post { setSeatComfortLevel(ctx, driver, heating, level) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setSeatComfortLevel")) return
        val next = level.coerceIn(0, SEAT_LEVEL_MAX)
        val heat = if (heating) next else 0
        val vent = if (heating) 0 else next
        val sequence = preview(COMFORT_GROUP) {
            if (driver) it.copy(seatHeat = heat, seatVent = vent)
            else it.copy(seatHeatPassenger = heat, seatVentPassenger = vent)
        }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "seatComfort driver=$driver heat=$heat vent=$vent targetSeq=$sequence", { cancelTarget(sequence) }) {
            val mode = activeValidationMode()?.seatWriteMode ?: SeatWriteValidationMode.ECARX_DIRECT
            val rejected = HvacWriteActions.writeSeatComfort(ctx, driver, heating, next, mode)
            if (rejected != 0) cancelTarget(sequence, if (next == 0) rejected else -1)
        }
        scheduleRefresh(ctx, COMFORT_GROUP)
    }
    private fun writeSeatLevel(
        ctx: Context,
        key: String,
        level: Int,
        set: (State, Int) -> State,
        label: String
    ) {
        if (Looper.myLooper() != main.looper) { main.post { writeSeatLevel(ctx, key, level, set, label) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, label)) return
        val next = level.coerceIn(0, SEAT_LEVEL_MAX)
        val sequence = preview(COMFORT_GROUP) { set(it, next) }
        if (Prefs.simulate(ctx)) return
        HvacCommands.submit(ctx, "$key target=$next targetSeq=$sequence", { cancelTarget(sequence) }) {
            HvacWriteActions.writeChannel(ctx, key, next)
        }
        scheduleRefresh(ctx, COMFORT_GROUP)
    }

    // =====================================================================

    fun addListener(l: (State) -> Unit) {
        onMain {
            listeners.add(l)
            l(state)
        }
    }

    fun removeListener(l: (State) -> Unit) {
        onMain { listeners.remove(l) }
    }

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }

    /** 真实观测只归并到 observed，显示目标由独立账本合成。 */
    private fun update(
        groups: Int = 0,
        forceNotify: Boolean = false,
        invalidateReads: Boolean = true,
        observedProperties: Int = 0,
        change: (State) -> State
    ) {
        onMain {
            val before = hvacState.observed
            val next = change(before)
            val changed = HvacState.changedProperties(before, next)
            if (next === before && !forceNotify && observedProperties == 0 && (!invalidateReads || groups == 0)) return@onMain
            if (invalidateReads) invalidateGroups(groups, changed)
            hvacState.observe(next, observedProperties, SystemClock.elapsedRealtime(), VehicleBridge.connectionGeneration)
            if (!invalidateReads) loseEventEvidence(changed)
            appCtx?.let { ctx ->
                if (next.driverTempC != before.driverTempC) {
                    next.driverTempC?.takeIf { it.isFinite() && it in 5f..45f }?.let { Prefs.setLastDriverTempC(ctx, it) }
                }
                if (next.passengerTempC != before.passengerTempC) {
                    next.passengerTempC?.takeIf { it.isFinite() && it in 5f..45f }?.let { Prefs.setLastPassengerTempC(ctx, it) }
                }
            }
            publishDisplay(forceNotify)
        }
    }

    /** 所有操作先在主线程累计目标；绝不把目标保存为车辆真实读数。 */
    private fun preview(groups: Int, change: (State) -> State): Long {
        if (Looper.myLooper() != main.looper) { main.post { preview(groups, change) }; return 0L }
        val c = appCtx
        if (c != null && Prefs.simulate(c)) {
            hvacState.clearTargets()
            update(groups, change = change)
            return 0L
        }
        val before = state
        val next = change(before)
        val sequence = hvacState.target(before, next, SystemClock.elapsedRealtime(), VehicleBridge.connectionGeneration)
        if (sequence != 0L) invalidateGroups(groups, HvacState.changedProperties(before, next))
        publishDisplay()
        return sequence
    }

    private fun invalidateGroups(groups: Int, properties: Int) {
        var changed = groups
        for (property in HvacProperty.entries) {
            if (properties and property.mask != 0) changed = changed or groupMaskOf(property.key)
        }
        for (group in REFRESH_GROUPS) {
            if (changed and (1 shl group.ordinal) != 0) refreshRevisions[group.ordinal]++
        }
    }

    private fun cancelTarget(sequence: Long, properties: Int = -1) {
        onMain { hvacState.cancel(sequence, properties); publishDisplay() }
    }

    private fun publishDisplay(forceNotify: Boolean = false) {
        val c = appCtx
        if (c != null && !Prefs.simulate(c) && (!Prefs.vehicleApiEnabled(c) || !Prefs.vehicleWriteEnabled(c))) {
            hvacState.clearTargets()
            airflowResume = null
        }
        val connection = VehicleBridge.connectionGeneration
        val next = hvacState.display(SystemClock.elapsedRealtime(), connection)
        val resume = airflowResume
        if (resume != null && (resume.connection != connection ||
                (c != null && resume.simulated != Prefs.simulate(c)) ||
                ((next.fanAuto || next.fanLevel > 0) && hvacState.pendingProperties and HvacProperty.FAN.mask == 0))) {
            // 风量被恢复（包括车机侧恢复）后不再沿用旧记录；确认前保留，以便失败后重试。
            airflowResume = null
        }
        val previous = state
        state = next
        val power = powerState()
        val powerChanged = power != publishedPowerState
        publishedPowerState = power
        main.removeCallbacks(targetExpiry)
        val deadline = hvacState.nextDeadlineMs
        if (deadline != Long.MAX_VALUE) main.postDelayed(targetExpiry, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
        if (next != previous || forceNotify || powerChanged) listeners.toList().forEach { listener -> runCatching { listener(next) } }
    }
}
