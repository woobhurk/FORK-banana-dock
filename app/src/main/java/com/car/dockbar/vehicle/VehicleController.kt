package com.car.dockbar.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.car.dockbar.config.VehicleChannels
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
        // ★ v1.4.35：温度弹窗 / 座椅弹窗所需
        val seatVentPassenger: Int = 0,
        val seatHeatPassenger: Int = 0,
        val defrostFront: Boolean = false,      // 前挡除霜/加热（0x10040100）
        val defrostRear: Boolean = false,       // 后挡除霜/加热（0x10040300）
        val steeringHeat: Int = 0               // 方向盘加热档位（0x10090100）
    )

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

    /**
     * ★ v1.4.92：温度回读的防抖延迟（ms）。
     * 连点 ± 时每次都会重置这个定时器，**只有最后一次操作后过这么久才真正回读**。
     */
    private const val TEMP_READBACK_DELAY_MS = 1000L
    const val TEMP_MAX_C = 28.5f

    /** 座椅通风 / 加热档位上限（0=关，1..3） */
    const val SEAT_LEVEL_MAX = 3

    // ★ v1.4.48 修正（用户真车日志）：前除霜真车 funcId 是 0x10040200（事件映射表），
    //   而非 oneOS_Hvac 常量表里的 0x10040100；三个信号的 areaId 在真车都是全局区
    //   0x80000000（日志实锤：0x10010300 / 0x10030100 事件 zone=-2147483648），不再用 zone=1
    /** 前挡除霜/加热（HVAC_DEFROST_FRONT） */
    private const val FUNC_DEFROST_FRONT = 0x10040200
    /** 后挡除霜/加热（HVAC_DEFROST_REAR） */
    private const val FUNC_DEFROST_REAR = 0x10040300
    /** 方向盘加热（STEERING_WHEEL_HEAT） */
    private const val FUNC_STEERING_HEAT = 0x10090100
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

    /** 浮点温度的「可信区间」——挡掉空调关闭时返回的伪值（实测为 5.0） */
    const val TEMP_VALID_MIN = 10f
    const val TEMP_VALID_MAX = 40f

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
        arrayOf("recirc", "air_direction", "ac"),
        arrayOf("temp_driver", "temp_passenger"),
        arrayOf("seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger",
            "defrost_front", "defrost_rear", "steering_heat"),
        emptyArray()
    )
    private val nextFallbackProbeAt = Array(REFRESH_GROUPS.size) { LongArray(refreshKeys[it].size) }
    private val nextRefreshLogAt = LongArray(REFRESH_GROUPS.size)
    private var forcedProbeGroups = 0
    private var pendingRefreshGroups = 0
    private val pendingRefresh = Runnable {
        val ctx = appCtx ?: return@Runnable
        val groups = pendingRefreshGroups
        pendingRefreshGroups = 0
        requestRefresh(ctx, groups)
    }

    /** 连点合并为一次定向回读；不同分组的请求不会互相丢失。 */
    private fun scheduleRefresh(ctx: Context, groups: Int, delayMs: Long = REFRESH_DEBOUNCE_MS) {
        onMain {
            appCtx = ctx.applicationContext
            pendingRefreshGroups = pendingRefreshGroups or groups
            main.removeCallbacks(pendingRefresh)
            main.postDelayed(pendingRefresh, delayMs)
        }
    }

    @Volatile
    var state: State = State()
        private set
    private var hvacState = HvacState(state)
    private val targetExpiry = Runnable { publishDisplay() }


    private var appCtx: Context? = null
    // 所有监听均通过唯一订阅集合配置，避免温度监听吞掉后续新增通道。
    private var lastFanAuto = false

    // =====================================================================
    // 生命周期
    // =====================================================================

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (Prefs.simulate(ctx) || !Prefs.vehicleApiEnabled(ctx)) {
            onMain { hvacState.clearTargets(); publishDisplay() }
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

    // 临时验证会话不持久化；重启后仅恢复用户原有基础/温度监听。
    @Volatile private var validationMode: HvacValidationMode? = null
    @Volatile private var validationEpoch = 0L
    @Volatile private var validationDeadlineAt = 0L
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
            if (activeValidationMode() == null) {
                stopHvacValidation("验证窗口到期或车控已关闭")
                return
            }
            poll(c)
            main.postDelayed(this, 250L)
        }
    }

    /** 只在用户明确开始验证后增加订阅和浮点读取，绝不自动发出车控写命令。 */
    internal fun startHvacValidation(ctx: Context, mode: HvacValidationMode) {
        onMain {
            val c = ctx.applicationContext
            appCtx = c
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
                        val fid = VehicleChannels.funcOf(c, key)
                        val zone = VehicleChannels.zoneOf(c, key)
                        val status = if (fid > 0) VehicleBridge.supportOf(fid, zone) else "未配置"
                        if (validationEpoch != epoch || connection != VehicleBridge.connectionGeneration || activeValidationMode() == null) return@runCatching
                        val values = if (fid > 0) VehicleBridge.supportedValues(fid, zone) else null
                        if (validationEpoch != epoch || connection != VehicleBridge.connectionGeneration || activeValidationMode() == null) return@runCatching
                        HvacDiagnostics.capability(key, status, values)
                    }
                    if (validationEpoch == epoch && connection == VehicleBridge.connectionGeneration && activeValidationMode() != null) refresh(c)
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
            validationMode = null
            validationEpoch++
            val epoch = validationEpoch
            main.removeCallbacks(validationTimeout)
            main.removeCallbacks(validationPoll)
            HvacDiagnostics.finish(reason)
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
        activeValidationMode()?.let { addAll(it.keys) }
    }.distinct()

    internal fun reloadHvacSubscriptions(ctx: Context) {
        onMain {
            appCtx = ctx.applicationContext
            validationEpoch++
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

    /** 基础监听和验证监听共用唯一集合，包含共用功能号的不同区域。 */
    private fun configureWatcher(c: Context): Boolean {
        val keys = if (Prefs.vehicleApiEnabled(c) && !Prefs.simulate(c)) watcherKeys(c) else emptyList()
        val funcs = keys.map { VehicleChannels.funcOf(c, it) }.filter { it > 0 }.distinct().toIntArray()
        val configuration = ++watcherConfiguration
        val epoch = validationEpoch
        val connection = VehicleBridge.connectionGeneration
        Logx.w("HVAC_TEST stage=SUBSCRIBE_BEGIN backend=${VehicleBridge.backend.label} keys=$keys funcs=${funcs.joinToString { "0x" + it.toString(16) }}")
        val ok = VehicleBridge.registerWatcher(funcs) { fid, zone, value ->
            val receivedAt = SystemClock.elapsedRealtime()
            main.post {
                if (configuration != watcherConfiguration || epoch != validationEpoch ||
                    connection != VehicleBridge.connectionGeneration) return@post
                if (validationMode != null && activeValidationMode() == null) return@post
                runCatching {
                    val key = keys.firstOrNull {
                        VehicleChannels.funcOf(c, it) == fid && VehicleChannels.zoneOf(c, it) == zone
                    }
                    if (key == null) {
                        if (HvacDiagnostics.active) Logx.w("HVAC_TEST stage=EVENT_IGNORED fid=0x${fid.toString(16)} zone=$zone raw=$value reason=未订阅或区域不匹配")
                        return@runCatching
                    }
                    val before = state
                    val temperature = key == "temp_driver" || key == "temp_passenger"
                    val float = if (temperature && (value is Float || value is Double)) HvacChannels.normalizeTemperature((value as Number).toFloat()) else null
                    val raw = if (value is Int || value is Short || value is Byte) (value as Number).toInt() else null
                    val accepted = float != null || (raw != null && validReading(c, key, raw))
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
                    HvacDiagnostics.event(key, value, accepted, before != state, zone,
                        SystemClock.elapsedRealtime() - receivedAt)
                    Logx.d("车控事件 key=$key fid=0x${fid.toString(16)} zone=$zone raw=$value accepted=$accepted changed=${before != state}")
                    if (evidenceOn() && fid == VehicleChannels.FUNC_CIRCULATION && raw != null) {
                        Logx.d("★ 取证：内外循环事件 zone=$zone raw=$raw 解析=${recircTextOf(raw)}")
                    }
                }.onFailure { Logx.e("车控事件处理异常 fid=0x${fid.toString(16)} zone=$zone", it) }
            }
        }
        val currentConnection = connection == VehicleBridge.connectionGeneration
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
        val validationEpoch: Long = 0L
    )

    private val EMPTY_READING = GroupReading(IntArray(0), 0)

    /** 显式刷新仍覆盖全部分组，但每组独立读取和发布，不再携带整份旧状态。 */
    fun refresh(ctx: Context? = null) {
        val c = ctx ?: appCtx ?: return
        requestRefresh(c, ALL_GROUPS)
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

    private fun applyPollingPolicy(now: Long) {
        val connection = VehicleBridge.connectionGeneration
        if (initialSnapshotConnection != connection) {
            initialSnapshotConnection = connection
            if (eventProofConnection != connection) { eventProofConnection = connection; seenEventMask = 0 }
            for (group in REFRESH_GROUPS) refreshQueue.request(group)
        }
        val registered = subscriptionAccepted && watcherConnection == connection
        if (!registered) seenEventMask = 0
        seenEventMask = seenEventMask and configuredEventMask
        for (group in REFRESH_GROUPS) {
            val interval = HvacRefreshPolicy.intervalMs(group, configuredEventMask, seenEventMask, registered)
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
    internal fun poll(ctx: Context) {
        onMain {
            val c = ctx.applicationContext
            if (!Prefs.vehicleApiEnabled(c)) return@onMain
            appCtx = c
            val now = SystemClock.elapsedRealtime()
            applyPollingPolicy(now)
            refreshQueue.poll(now)
            pumpRefreshQueue(c)
        }
    }

    private fun requestRefresh(ctx: Context, groups: Int) {
        onMain {
            val c = ctx.applicationContext
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
                        applyReading(c, group, revision, reading, startedAt - queuedAt, ioMs)
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
        val normalKeys = Prefs.hvacEventKeys(c)
        val temperatures = if (group == VehicleRefreshGroup.TEMPERATURE &&
            (activeValidationMode() == HvacValidationMode.TEMPERATURE || normalKeys.any { it == "temp_driver" || it == "temp_passenger" })) FloatArray(keys.size) { Float.NaN } else null
        var present = 0
        for (i in keys.indices) {
            if (epoch != validationEpoch || connection != VehicleBridge.connectionGeneration ||
                !Prefs.vehicleApiEnabled(c) || Prefs.simulate(c) ||
                (validationMode != null && activeValidationMode() == null)) break
            val key = keys[i]
            val observedAt = SystemClock.elapsedRealtime()
            if (temperatures != null && (normalKeys.contains(key) || activeValidationMode() == HvacValidationMode.TEMPERATURE) && epoch == validationEpoch) {
                val fid = VehicleChannels.funcOf(c, key)
                val zone = VehicleChannels.zoneOf(c, key)
                Logx.d("HVAC_TEST stage=FLOAT_READ_BEGIN key=$key fid=0x${fid.toString(16)} zone=$zone")
                val rawFloat = if (fid > 0) VehicleBridge.readTempProperty(fid, zone) else null
                if (epoch != validationEpoch || connection != VehicleBridge.connectionGeneration ||
                    (validationMode != null && activeValidationMode() == null)) break
                val temp = rawFloat?.let(HvacChannels::normalizeTemperature)
                if (epoch == validationEpoch) HvacDiagnostics.read(key, rawFloat, temp != null, "FLOAT_READ zone=$zone", observedAt)
                if (temp != null) {
                    temperatures[i] = temp
                    continue
                }
            }
            var actualZone = if (key == "defrost_front" || key == "defrost_rear" || key == "steering_heat") ZONE_GLOBAL else VehicleChannels.zoneOf(c, key)
            val raw = when (key) {
                "defrost_front" -> readBare(FUNC_DEFROST_FRONT)
                "defrost_rear" -> readBare(FUNC_DEFROST_REAR)
                "steering_heat" -> readBare(FUNC_STEERING_HEAT)
                else -> {
                    val now = SystemClock.elapsedRealtime()
                    val probe = forceProbe || now >= nextFallbackProbeAt[group.ordinal][i]
                    if (probe) nextFallbackProbeAt[group.ordinal][i] = now + FALLBACK_PROBE_INTERVAL_MS
                    val result = read(key, c, allowZoneFallback = probe)
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
        return GroupReading(values, present, temperatures = temperatures, validationEpoch = epoch)
    }

    private fun applyReading(
        c: Context,
        group: VehicleRefreshGroup,
        revision: Long,
        reading: GroupReading,
        waitMs: Long,
        ioMs: Long
    ) {
        if (!Prefs.vehicleApiEnabled(c)) return
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
        update(forceNotify = sensorsChanged, invalidateReads = false, observedProperties = readingProperties(c, group, reading)) { current ->
            var next = current
            if (connected && !simulated) {
                val keys = refreshKeys[group.ordinal]
                for (i in keys.indices) {
                    val temperature = reading.temperatures?.getOrNull(i)
                    if (temperature != null && temperature.isFinite() &&
                        reading.validationEpoch == validationEpoch && floatTemperatureEnabled(c, keys[i])
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


    private fun floatTemperatureEnabled(c: Context, key: String): Boolean =
        Prefs.hvacEventKeys(c).contains(key) || activeValidationMode() == HvacValidationMode.TEMPERATURE

    private fun readingProperties(c: Context, group: VehicleRefreshGroup, reading: GroupReading): Int {
        var properties = 0
        val keys = refreshKeys[group.ordinal]
        for (i in keys.indices) {
            val temperature = reading.temperatures?.getOrNull(i)
            val valid = if (temperature != null && temperature.isFinite() && floatTemperatureEnabled(c, keys[i])) true
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
        "recirc", "air_direction", "ac" -> CLIMATE_GROUP
        "temp_driver", "temp_passenger" -> TEMPERATURE_GROUP
        "seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger",
        "defrost_front", "defrost_rear", "steering_heat" -> COMFORT_GROUP
        else -> 0
    }


    private data class ChannelRead(val value: Int?, val zone: Int)
    private data class ReadZone(val function: Int, val configured: Int, val actual: Int)
    private val readZoneCache = HashMap<String, ReadZone>()
    private var readZoneConnection = Long.MIN_VALUE

    /** 读取命中仅缓存本连接；绝不因读到值就改写控制区域配置。 */
    private fun read(key: String, ctx: Context, allowZoneFallback: Boolean = true): ChannelRead {
        val f = VehicleChannels.funcOf(ctx, key)
        val configured = VehicleChannels.zoneOf(ctx, key)
        if (f <= 0) return ChannelRead(null, configured)
        val connection = VehicleBridge.connectionGeneration
        if (connection != readZoneConnection) {
            readZoneCache.clear()
            readZoneConnection = connection
        }
        val cached = readZoneCache[key]?.takeIf { it.function == f && it.configured == configured }
        val firstZone = cached?.actual ?: configured
        val first = VehicleBridge.readInt(f, firstZone)
        if (connection != VehicleBridge.connectionGeneration) return ChannelRead(null, firstZone)
        if (first != null && validReading(ctx, key, first)) return ChannelRead(first, firstZone)
        readZoneCache.remove(key)
        if (firstZone != configured) {
            val primary = VehicleBridge.readInt(f, configured)
            if (connection != VehicleBridge.connectionGeneration) return ChannelRead(null, configured)
            if (primary != null && validReading(ctx, key, primary)) return ChannelRead(primary, configured)
        }
        if (!allowZoneFallback) return ChannelRead(first, firstZone)
        for (zone in HvacChannels.readZones(key, configured)) {
            if (zone == firstZone || (firstZone != configured && zone == configured)) continue
            val value = VehicleBridge.readInt(f, zone)
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

    /**
     * ★ v1.4.48 新信号写入守卫：写前探测 HAL 支持状态，非 active 一律不写。
     * 真车日志实锤：对不支持的 (funcId, zone, value) 调 setFunctionValue 会直接
     * native abort（用户「闪退 2-3 次才能打开」= 连续触发后写入熔断才恢复正常）。
     * 读取不受影响（getFunctionValue 返回哨兵不崩）。
     */
    /**
     * ★ v1.4.60：布尔开关通道的统一写入（A/C / 前挡 / 后挡 / ECO 等）。
     *
     * 与 [writeBareGuarded] 的区别：**会依次试复合编码和裸值**。
     * 真车日志实锤：成功的通道（风量 0x10020101、循环 0x10030101、座椅 0x10050102）
     * 全是复合编码 `functionId | 档位`；而前挡/后挡旧实现只发裸值 0/1，40 次全 FAIL。
     *
     * ⚠ 这三个通道读回恒 `0xff`（UNKNOWN），回读校验失效，
     *   `setFunctionValue` 返回 false 也不代表被拒 —— 用户 2026-10-20 确认车机真实响应。
     */
    private fun writeSwitch(
        ctx: Context, key: String, func: Int, zone: Int, on: Boolean, label: String,
        bareFirst: Boolean = false
    ): Boolean {
        if (Prefs.simulate(ctx)) return true
        if (!Prefs.vehicleWriteEnabled(ctx)) {
            Logx.w("写 $label 跳过：车控写入熔断中")
            return false
        }
        val support = VehicleBridge.supportOf(func, zone)
        val v = if (on) 1 else 0
        val composite = (func and 0xFFFFFF00.toInt()) or v
        // ★★★ v3.6：加 bareFirst 开关 —— 有些通道系统的权威写法就是**裸值 0/1**，
        //   不做 functionId|value 的复合编码。
        //   依据（oneOS_Dock.apk dex）：
        //     AutoSwitchSignal.setValue → setFunctionValue(0x10010200, 0x80000000, 0|1)
        //     RecycleSignalClick 的外循环也是写裸值 0。
        //   这类通道若先发复合值，HAL 会拒（我们「怎么切都是内循环」就是这么来的）。
        var ok: Boolean
        if (bareFirst) {
            // ① 裸值（系统权威形态）
            ok = VehicleBridge.verifyWrite(func, zone, v)
            // ② 复合编码（兜底）
            if (!ok) ok = VehicleBridge.verifyWrite(func, zone, composite)
        } else {
            // ① 复合编码（本车主力形态）
            ok = VehicleBridge.verifyWrite(func, zone, composite)
            // ② 裸值
            if (!ok) ok = VehicleBridge.verifyWrite(func, zone, v)
        }
        Logx.d(
            "写 $label target=${if (on) "开" else "关"} " +
            "func=0x${func.toString(16)} zone=0x${zone.toString(16)} " +
            "顺序=${if (bareFirst) "裸值优先" else "复合优先"} " +
            "复合=0x${composite.toString(16)} 裸值=$v 支持状态=$support → " +
            if (ok) "调用已接受，生效等待真实观测" else "调用未接受；不能据此推断车辆状态或权限原因"
        )
        return ok
    }

    /**
     * ★ v1.4.35 温度弹窗：直接把温度设到目标值（点温度档位列表用）。
     * 与 [tempStep] 同一编码（int 通道 ×0.2），带双温同步支持。
     */
    /**
     * ★ v1.4.35 温度弹窗：直接把温度设到目标值（点温度档位列表用）。
     *
     * ★★ v1.4.54：绝对设温已废弃为「多次档位步进」。
     *   真车日志实锤：复合编码低字节 = round(℃/0.2)（16℃=80、20℃=100、32℃=160），
     *   全部超出 HAL 的小编码枚举范围 → 8 个 zone 全 FAIL。绝对设温在本车不可行。
     *   现在改为：算出与当前值的档位差，逐档步进到位。
     */
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
        HvacCommands.submitLatest(ctx, key, sequence, { cancelTarget(sequence) }) { writeTempValue(ctx, driver, next) }
        scheduleRefresh(ctx, TEMPERATURE_GROUP)
    }

    /**
     * ★★ v1.4.79：判断一个回读温度**是否可信**。
     *
     * 背景：`0x10060100`（设定温度）的**读取本身不可靠** ——
     *   真车实测恒回 15.5（= TEMP_MIN_C，是「空调关闭/无值」时的伪值）。
     *   之前「读到就覆盖」→ 把正确的乐观值顶成 15.5，
     *   弹窗和 Dock 显示全错（用户反馈「选对了、发对了，就是显示不对」）。
     *
     * 采信条件：
     *  1. 落在上下限**内部**（排除 15.5 / 28.5 这类边界伪值）
     *  2. 与目标值相差 ≤ 1.5℃（否则说明车机还没生效）
     */
    private fun isPlausibleTemp(back: Float, target: Float): Boolean =
        back > TEMP_MIN_C + 0.4f && back < TEMP_MAX_C - 0.4f &&
                Math.abs(back - target) <= 1.5f

    /**
     * ★★ v1.4.77：写入一个**绝对设定温度**（℃），并做乐观更新。
     *
     * ⚠ 关键：**乐观更新必须在写之前无条件执行**。
     *   HAL 的返回值不可信 —— A/C 通道实测「返回 false 但车机真生效」，
     *   温度的 Customize 通道同样是「返回 false 但生效」。
     *   之前写成 `if (ok) update(...)` → HAL 返回 false 时本地状态不更新
     *   → Dock 显示的还是旧温度 → 「跟实际温度不统一」。
     */
    private fun writeTempValue(ctx: Context, driver: Boolean, target: Float) {
        val key = if (driver) "temp_driver" else "temp_passenger"
        val f = VehicleChannels.funcOf(ctx, key)
        if (f <= 0) {
            Logx.e("设温失败：通道 $key 未配置 functionId")
            return
        }
        val zone = VehicleChannels.zoneOf(ctx, key)
        val v = target.coerceIn(TEMP_MIN_C, TEMP_MAX_C)


        // ② 写入
        var wrote = false
        if (Prefs.tempCustomizeWriteEnabled(ctx)) {
            VehicleBridge.customizeWriteEnabled = true
            wrote = VehicleBridge.writeTempViaGlyCar(f, zone, v) ||
                    VehicleBridge.writeTempCustomize(f, zone, v)
        } else {
            // int 通道：低字节 = round(℃/0.2)，走复合编码
            val idx = Math.round(v / 0.2f).toInt().coerceIn(0, 255)
            wrote = writeChannel(ctx, key, idx)
        }
        Logx.d("设温 ${if (driver) "主驾" else "副驾"} → ${"%.1f".format(v)}℃ zone=0x${zone.toString(16)} 写入=$wrote")

        // ③ **延迟**回读校正（★ 关键：回读不可无条件采信）
        //
        // 真车日志实锤（2026-10-20 22:47）：
        //   ```
        //   温度 Customize 写：0x10060100 zone=0x4 value=255.0℃ → 返回=false
        //   温度 Customize 读：0x10060100 zone=0x4 → 15.5℃      ← ★ 伪值！
        //   ```
        //   0x10060100 的**读取本身不可靠**（恒回 15.5 / 28.5 这类边界值或关闭态伪值）。
        //   之前「读到就覆盖」→ 把正确的乐观值顶成 15.5 →
        //   弹窗和 Dock 显示都不对（用户反馈：选对了、发对了，就是显示不对）。
        //
        // 现在：等 600ms 让车机生效后再读，且**只在读数落在合理区间内**才采信：
        //   · 排除上下限边界值（15.5 / 28.5 —— 正是伪值的形态）
        //   · 排除与目标值相差过大的值（>1.5℃ 说明还没生效）
        // 读不到/不合理 → 保持乐观值（那才是用户选的值）。
        // ★ v1.4.92：改成**防抖**回读（连点只在停手 1 秒后读一次），不再每次都排一个 600ms 任务
        scheduleTempReadback(driver, f, zone, v)
    }

    /**
     * ★ v1.4.52：切车机自身的「双温 / 主副驾同步」通道（HVAC_FUNC_TEMP_DUAL 0x10060500）。
     *
     * 原来「双温同步」只是本项目的一个本地开关（[Prefs.syncTemp]），
     * 只决定要不要顺带写另一侧温度，**从来没有真正告诉车机**。现在补上。
     */
    fun setTempDual(ctx: Context, on: Boolean) {
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setTempDual")) return
        if (!Prefs.simulate(ctx) && !HvacCommands.isWorkerThread()) {
            hal.execute { setTempDual(ctx, on) }
            return
        }
        if (Prefs.simulate(ctx)) return
        val key = "temp_dual"
        if (!VehicleChannels.enabled(ctx, key)) {
            Logx.d("双温通道未启用（functionId<=0），只切本地同步开关")
            return
        }
        // ★★ v1.4.83：**先读回当前状态，一致就不写**。
        //   该通道（0x10060500，全局 zone）在车机侧可能是**翻转**语义 —— 每写一次翻一次。
        //   现在：回读 == 目标状态 → 直接不写；读不到才按原逻辑写。
        val cur = readDualState(ctx)
        if (cur != null && cur == on) {
            Logx.d("双温同步：车机已是" + (if (on) "开" else "关") + "，跳过写入（避免翻转）")
            return
        }
        // ★ v1.4.54：0x10060500 这个 ID 本身没验证过（oneOS_Dock 表里抄的），
        //   把 0/1/2/3 全试一遍，哪个回读生效就用哪个；全失败至少留下日志证据。
        val ok = writeChannelCandidates(ctx, key, listOf(if (on) 1 else 0))
        Logx.d("写 车机双温同步 target=${if (on) "开" else "关"} func=0x${VehicleChannels.funcOf(ctx, key).toString(16)} → ${if (ok) "OK" else "FAIL"}")
        if (!ok) {
            Logx.w("双温同步写入失败：0x10060500 可能不是本车的双温通道，" +
                "请用「扫描车控通道」找真实 ID，或先用「长按副驾温度」的本地同步（写入两侧温度）")
        }
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
        preview(COMFORT_GROUP) { current -> current.copy(defrostFront = next) }   // 乐观更新
        // ★ v1.4.60：改走 writeSwitch（复合编码优先，裸值兜底）。
        //   旧实现 writeBareGuarded 只发裸值 0/1，真车 40 次全 FAIL。
        val ctxBg = ctx
        hal.execute { writeSwitch(ctxBg, "defrost_front", FUNC_DEFROST_FRONT, ZONE_GLOBAL, next, "前挡加热") }
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
        preview(COMFORT_GROUP) { current -> current.copy(defrostRear = next) }
        // ★ v1.4.60：同前挡，改走 writeSwitch（复合编码优先）
        val ctxBg = ctx
        hal.execute { writeSwitch(ctxBg, "defrost_rear", FUNC_DEFROST_REAR, ZONE_GLOBAL, next, "后挡加热") }
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
        preview(COMFORT_GROUP) { current -> current.copy(steeringHeat = next) }
        val ctxBg = ctx
        hal.execute {
            val ok = writeChannel(ctxBg, "steering_heat", next)
            Logx.d("写 方向盘加热 idx=$next → ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
        }
        scheduleRefresh(ctx, COMFORT_GROUP)
    }

    /**
     * ★ v1.4.36：座椅记忆 —— 应用第 [pos] 个记忆位（1..3）。
     * 信号：SETTING_FUNC_SEAT_POSITION_SET = 0x2d400200（写位置号）。
     * ⚠ 车机未实测，写无效时看日志（HVAC_WRITE 行）定位编码问题。
     */
    fun applySeatMemory(ctx: Context, pos: Int) {
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "applySeatMemory")) return
        if (!Prefs.simulate(ctx) && !HvacCommands.isWorkerThread()) {
            hal.execute { applySeatMemory(ctx, pos) }
            return
        }
        if (pos !in 1..3) return
        if (Prefs.simulate(ctx)) {
            Logx.d("座椅记忆：模拟模式，应用位置 $pos（仅日志）")
            return
        }
        // ★★ v1.4.89：**直接写位置号 pos（1/2/3）**。
        //   之前这里调的是 `writeSwitch(..., pos > 0, ...)`，而 writeSwitch 内部
        //   `val v = if (on) 1 else 0` → **M1/M2/M3 写出去的值一模一样（都是 1）**，
        //   所以「选用记忆位」从来没生效过。
        //   现在：zone 依次试 1 / 4 / 0x8 / 全局，每个 zone 都试「裸值」和「复合编码」。
        var ok = false
        for (z in SEAT_MEMORY_ZONES) {
            val compositePos = (FUNC_SEAT_POSITION_SET and 0xFFFFFF00.toInt()) or pos
            for (v in intArrayOf(pos, compositePos)) {
                val r = VehicleBridge.verifyWrite(FUNC_SEAT_POSITION_SET, z, v)
                Logx.d(
                    "座椅记忆：调用位置 $pos  func=0x" + Integer.toHexString(FUNC_SEAT_POSITION_SET) +
                    " zone=0x" + Integer.toHexString(z) + " value=" +
                    (if (v == pos) "裸值$pos" else "复合0x" + Integer.toHexString(v)) +
                    " → " + r
                )
                if (r) { ok = true; break }
            }
            if (ok) break
        }
        Logx.d("座椅记忆：调用位置 $pos 最终结果=$ok")
        // ★ v1.4.97 #12：车机的「当前记忆位」通道在这台车上读不回来，
        //   所以把我们自己刚调用的这个位置记到本地，弹窗下次打开就知道该高亮哪个。
        Prefs.setSeatMemoryIndex(ctx, pos)
        scheduleRefresh(ctx, COMFORT_GROUP)   // ★ v3.4：合并刷新（连点只跑最后一次）
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

    /**
     * ★ v1.4.35：极速降温 —— 无独立信号，组合动作：
     *   **AC 开 + 风量最大 + 温度最低（主驾 + 副驾）**（用户 v3.13.12 明确要求两侧都设）。
     */
    fun fastCool(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { fastCool(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "fastCool")) return
        if (Prefs.simulate(ctx)) {
            preview(FAN_GROUP or CLIMATE_GROUP or TEMPERATURE_GROUP) { current ->
                current.copy(
                    acOn = true, fanAuto = false, fanLevel = 9,
                    driverTempC = TEMP_MIN_C, passengerTempC = TEMP_MIN_C, connected = true
                )
            }
            return
        }
        hal.execute {
            runCatching {
                writeChannel(ctx, "ac", 1)
                writeChannel(ctx, "fan_manual", 9)
                writeTempValue(ctx, true, TEMP_MIN_C)
                writeTempValue(ctx, false, TEMP_MIN_C)
            }.onFailure { Logx.e("空调组合写入异常", it) }
        }
        preview(FAN_GROUP or CLIMATE_GROUP or TEMPERATURE_GROUP) { current ->
            current.copy(
                acOn = true, fanAuto = false, fanLevel = 9,
                driverTempC = TEMP_MIN_C, passengerTempC = TEMP_MIN_C
            )
        }
        Logx.d("极速降温：AC 开 + 风量最大 + 主副驾温度最低")
        scheduleRefresh(ctx, FAN_GROUP or CLIMATE_GROUP or TEMPERATURE_GROUP)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /**
     * ★ v3.13.12：极速升温 —— 组合动作：
     *   **风量最大 + 温度最高（主驾 + 副驾）**（用户 v3.13.12 明确规格，**不动 AC**）。
     * 车机侧没有独立的 PTC「加热」开关信号（通道表里只有 ac），
     * 所以升温靠「大风量 + 两侧温度设到最高」组合实现。
     */
    fun fastHeat(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { fastHeat(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "fastHeat")) return
        if (Prefs.simulate(ctx)) {
            preview(FAN_GROUP or TEMPERATURE_GROUP) { current ->
                current.copy(
                    fanAuto = false, fanLevel = 9,
                    driverTempC = TEMP_MAX_C, passengerTempC = TEMP_MAX_C, connected = true
                )
            }
            return
        }
        hal.execute {
            runCatching {
                writeChannel(ctx, "fan_manual", 9)
                writeTempValue(ctx, true, TEMP_MAX_C)
                writeTempValue(ctx, false, TEMP_MAX_C)
            }.onFailure { Logx.e("空调组合写入异常", it) }
        }
        preview(FAN_GROUP or TEMPERATURE_GROUP) { current ->
            current.copy(
                fanAuto = false, fanLevel = 9,
                driverTempC = TEMP_MAX_C, passengerTempC = TEMP_MAX_C
            )
        }
        Logx.d("极速升温：风量最大 + 主副驾温度最高")
        scheduleRefresh(ctx, FAN_GROUP or TEMPERATURE_GROUP)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** ★ v1.4.35：副驾座椅通风档位（0..3，通道 seat_vent_passenger / zone=4） */
    fun setSeatVentLevelPassenger(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_vent_passenger", state.seatVentPassenger, level,
        set = { s, v -> s.copy(seatVentPassenger = v) },
        label = "副驾座椅通风"
    )

    /** ★ v1.4.35：副驾座椅加热档位（0..3，通道 seat_heat_passenger / zone=4） */
    fun setSeatHeatLevelPassenger(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_heat_passenger", state.seatHeatPassenger, level,
        set = { s, v -> s.copy(seatHeatPassenger = v) },
        label = "副驾座椅加热"
    )

    // =====================================================================
    // 操作
    // =====================================================================

    /**
     * 统一写 int 通道。
     *
     * ★ 权威调用形态（用户 2026-10-16 提供，内外循环实测）：
     *   `ICar.getICarFunction().setFunctionValue(functionId, areaId, value)`
     *   value 是**裸枚举/裸档位**，不是 `functionId | value` 的复合编码。
     *   例：开内循环 = `setFunctionValue(0x10030100, 0x8, 1)`。
     *
     * 修正前本项目所有通道都写 `(functionId & 0xFFFFFF00) | value`（0x10030101 这种），
     * 这是从反编译 oneOS_Dock 的「func + 值」记号误推出来的，实车写被拒。
     *
     * ★★ v1.4.54 顺序与策略（真车日志逐条实锤）：
     * ```
     * 风量  0x10020100 | 1 → OK      座椅 0x10050100 | 2 → OK
     * 风向  0x10070100 | 7 → OK      循环 0x10030100 | 1 → OK（zone 必须用全局 0x80000000）
     * 温度  0x10060100 | 100(=20℃) → 全部 zone 全 FAIL   ← 低字节只能是小编码
     * ```
     * 规律：**复合编码的低字节只能是小档位（风量 1..9、座椅 0..3、循环 0/1、风向 1..7）**。
     * 温度的低字节是 round(℃/0.2)，20℃ 就是 100，远超 HAL 枚举范围 → 一律被拒。
     * ⇒ 温度必须按「相对档位」写，见 [tempStep] 的说明。
     *
     * 顺序：① 复合编码 ② 裸值 ③ 换 zone 再试复合。
     *
     * ⚠ 熔断：连续崩溃 2 次后 [Prefs.vehicleWriteEnabled] 会被置 false，这里直接跳过写入。
     *   setFunctionValue 是厂商私有 HAL 反射调用，传错值可能直接 native abort，
     *   Java 层拦不住，只能从源头停写。
     */
    private fun writeChannel(ctx: Context, key: String, value: Int): Boolean {
        if (!HvacCommands.canWrite(ctx, key)) return false
        val function = VehicleChannels.funcOf(ctx, key)
        if (function <= 0) { Logx.w("写 $key 未执行：通道未配置"); return false }
        val zone = VehicleChannels.zoneOf(ctx, key)
        val encoded = HvacChannels.encodeInt(function, key, value)
        if (encoded == null) { Logx.w("写 $key 未执行：目标越界 value=$value"); return false }
        val accepted = VehicleBridge.verifyWrite(function, zone, encoded)
        if (accepted) return true
        // 尚未逐车型验证的旧舒适/整数路径仅保留同一区域的表示兼容，不跨区盲试。
        val verified = key == "fan_manual" || key == "fan_auto" || key == "ac" || key == "recirc" || key == "air_direction"
        return !verified && encoded != value && VehicleBridge.verifyWrite(function, zone, value)
    }


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

    /**
     * ★★ v1.4.54：调温改为「相对档位」写入。
     *
     * 为什么必须这么改（2026-10-20 真车日志实锤）：
     * ```
     * HVAC_WRITE 0x10060100 zone=1 value=268829028 → false   ← 复合，低字节=100(20℃)
     * HVAC_WRITE 0x10060100 zone=1 value=100        → false   ← 裸索引
     * zone=8/4/16/32/64/全局 全部 false
     * ```
     * 而同一份日志里：
     * ```
     * 0x10020100 | 1  → OK      0x10050100 | 2  → OK
     * 0x10070100 | 7  → OK      0x10030100 | 1  → OK
     * ```
     *  ⇒ 复合编码的低字节 HAL 只认**小编码**（风量 1..9 / 座椅 0..3 / 风向 1..7 / 循环 0..1）。
     *    温度的低字节 = round(℃/0.2)，16℃=80、32℃=160，全部越界 → 绝对索引写法不可能成功。
     *
     *  这也是系统空调面板只有「温度 +/−」两个按钮、没有数字键盘的原因：
     *  HAL 侧温度就是按档位步进的。
     *
     *  另外：温度**读不到**（int 通道恒 0xff，Customize 回调也收不到），
     *  所以这里是「盲调」——发一档步进，本地状态按 step 自己推算。
     */
    /**
     * ★★ v1.4.59 调温：`0x10060100` 在本车**只读不可写**，改为返回 false 让调用方打开系统空调界面。
     *
     * 证据链（2026-10-20 真车日志 + logcat）：
     * ```
     * isFunctionSupported 0x10060100 zone=1/4/8/16/32/64/全局 → notavailable
     * HVAC_WRITE 0x10060100 zone=4 value=268828929(=0x10060101) → false
     * HVAC_WRITE 0x10060100 zone=4 value=268828930(=0x10060102) → false
     * HVAC_WRITE 0x10060100 zone=4 value=1 / value=2           → false
     * 8 个 zone × 复合/裸值 全部 FAIL
     * ```
     * 对比同期**成功**的通道：
     * ```
     * isFunctionSupported 0x10020100 zone=8 → active     ← 风量是可写的
     * 0x10020100|1 → OK   0x10030100|1 → OK   0x10050100|2 → OK
     * ```
     * 而温度的**监听回调完全正常**（实体按键调时实时收到 15.5/18.5/22.5/28.5）
     * → HAL 只开放了温度的通知，没开放写入。这是 HAL 能力限制，
     * `CONTROL_CAR_CLIMATE` 是 signature 级，adb 也授不了 → 普通 App 无解。
     *
     * @return true = 已自行写入；false = 不可写，调用方应打开系统空调界面
     */
    /**
     * ★★ v1.4.92：**温度回读防抖** —— 连点 ± 时只在「最后一次操作后 1 秒」回读一次。
     *
     * 起因（用户反馈）：快速连点温度 ± 时，**显示的温度很不连贯**。
     *   原先有两处回读，每次点击都会触发：
     *     ① `tempStepWrite` 里**写完立即读** → 连点 5 次就读 5 次，
     *        每次都拿到「车机还没跟上」的中间值 → 显示来回跳
     *     ② `writeTempValue` 里 `postDelayed(600)` 读 → 还会再堆叠 5 个延迟回读
     *   两者叠加，显示就像在抖。
     *
     * 现在：每次调温都 `removeCallbacks` + `postDelayed(1000)`，
     *   **连点只会在停止 1 秒后触发一次回读**，用那一次的最终状态刷新显示。
     *   过程中显示一律用乐观值（用户点了多少次就显示多少）—— 反而更连贯。
     */
    private var tempReadDriver = true
    private var tempReadFunc = 0
    private var tempReadZone = 0
    private var tempReadTarget = 0f

    private val pendingTempReadback = Runnable {
        val driver = tempReadDriver
        val f = tempReadFunc
        val zone = tempReadZone
        val target = tempReadTarget
        val revision = refreshRevisions[VehicleRefreshGroup.TEMPERATURE.ordinal]
        hal.execute {
            val back = VehicleBridge.readTempCustomize(f, zone)
                ?: VehicleBridge.readTempViaGlyCar(f, zone)
            main.post {
                if (refreshRevisions[VehicleRefreshGroup.TEMPERATURE.ordinal] != revision) return@post
                if (back == null) {
                    Logx.d("温度回读：读不到 → 保持本地值 %.1f℃".format(target))
                } else if (isPlausibleTemp(back, target)) {
                    update(invalidateReads = false, observedProperties = if (driver) HvacProperty.TEMP_DRIVER.mask else HvacProperty.TEMP_PASSENGER.mask) { current ->
                        if (driver) current.copy(driverTempC = back) else current.copy(passengerTempC = back)
                    }
                    Logx.d("温度回读：%.1f℃（采信，停手 1 秒后的最终值）".format(back))
                } else {
                    Logx.d("温度回读：%.1f℃ 不可信（伪值或未生效）→ 保持 %.1f℃".format(back, target))
                }
            }
        }
    }

    /** ★ v1.4.92：安排一次防抖回读（连点只会保留最后一个） */
    private fun scheduleTempReadback(driver: Boolean, f: Int, zone: Int, target: Float) {
        onMain {
            tempReadDriver = driver
            tempReadFunc = f
            tempReadZone = zone
            tempReadTarget = target
            main.removeCallbacks(pendingTempReadback)
            main.postDelayed(pendingTempReadback, TEMP_READBACK_DELAY_MS)
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
        val sync = Prefs.syncTemp(ctx)
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
                writeTempValue(ctx, driver, next)
                if (sync) writeTempValue(ctx, !driver, next)
            }
            if (sync) hal.execute(action)
            else HvacCommands.submitLatest(ctx, key, sequence, { cancelTarget(sequence) }, action)
        } else {
            // 旧整数步进不是可替换绝对命令，逐次执行，避免合并后丢步数。
            val direction = if (step > 0) 1 else 2
            hal.execute { tempStepWrite(ctx, driver, step, next, direction) }
        }
        scheduleRefresh(ctx, TEMPERATURE_GROUP)
        return true
    }

    /**
     * ★★ v1.4.78：调温的**实际写入**（跑在后台线程）。
     *
     * 分两条路：
     *  A. Customize/GlyCar 浮点通道（系统空调实际走的路）—— 写**目标温度 float ℃**
     *  B. int 通道复合编码 `0x10060100 | round(℃/0.2)`（备用）
     */
    private fun tempStepWrite(
        ctx: Context, driver: Boolean, step: Int, next: Float, dir: Int
    ) {
        val key = if (driver) "temp_driver" else "temp_passenger"
        val f = VehicleChannels.funcOf(ctx, key)
        if (f <= 0) {
            Logx.e("调温失败：通道 $key 未配置 functionId")
            return
        }
        // ★★ v1.4.68：**严格照抄系统空调的调用序列**（反编译 + 真车 logcat 双重确认）：
        // ```
        // isFunctionSupported HVAC_FUNC_TEMP [0x10060100] zone ZONE_ROW_1_LEFT [0x1] status active
        // setCustomizeFunctionValue HVAC_FUNC_TEMP [0x10060100] zone ZONE_ROW_1_LEFT [0x1] value 18.0
        // getFunctionValue HVAC_FUNC_TEMP_MAX [0x10060200] zone ZONE_ALL [0x80000000] value 28.5
        // getFunctionValue HVAC_FUNC_TEMP_MIN [0x10060300] zone ZONE_ALL [0x80000000] value 15.5
        // setCustomizeFunctionValue: ... results:true
        // getCustomizeFunctionValue HVAC_FUNC_TEMP [0x10060100] zone [0x1] results:19.5
        // ```
        // 之前全写错了：方法名、value 形态（应是目标温度 float ℃）、zone 都对不上。
        if (Prefs.tempCustomizeWriteEnabled(ctx)) {
            val zone = VehicleChannels.zoneOf(ctx, key)
            // ① 上下限（zone=全局），用于夹取目标值
            // ★ v1.4.74：**必须过滤哨兵**！真车日志实锤：
            //   `调温 副驾 ECarX Customize：... 目标=255.0℃ (上下限 min=255 max=255)`
            //   0x10060200 / 0x10060300 读回的是 255（=0xff UNKNOWN 哨兵），
            //   被当成真实上下限 → coerceIn(255,255) → **目标温度变成 255℃** → 必然被拒。
            val maxRaw = VehicleBridge.readInt(VehicleChannels.FUNC_TEMP_MAX, ZONE_GLOBAL)
            val minRaw = VehicleBridge.readInt(VehicleChannels.FUNC_TEMP_MIN, ZONE_GLOBAL)
            val maxT = maxRaw?.takeIf { it in 16..40 }
            val minT = minRaw?.takeIf { it in 16..40 }
            val target = next.coerceIn(
                (minT ?: 15.5f).toFloat(),
                (maxT ?: 28.5f).toFloat()
            )
            VehicleBridge.customizeWriteEnabled = true

            // ② **优先走 GlyCar 浮点属性** —— 系统空调实际走的就是这条
            //    （logcat: `GlyCarImpl set HVAC_FUNC_TEMP propertyId=268828928 areaId=1 val=18.0`）
            //    本车 ECarX 侧 isFunctionSupported(0x10060100, zone)=notavailable（只读），
            //    两套 API 同编号但权限检查可能不同 → 这就是突破口。
            var cok = VehicleBridge.writeTempViaGlyCar(f, zone, target)
            var via = "车控通道 B 浮点属性"
            if (!cok) {
                // ③ 回退 ECarX Customize
                cok = VehicleBridge.writeTempCustomize(f, zone, target)
                via = "车控通道 A Customize"
            }
            Logx.d(
                "调温 ${if (driver) "主驾" else "副驾"} $via：0x${f.toString(16)} " +
                "zone=0x${zone.toString(16)} 目标=${"%.1f".format(target)}℃ " +
                "(上下限 min=$minT max=$maxT) → $cok"
            )
            // ④ 回读：★ v1.4.92 **不再立即读、也不再用它刷新显示**。
            //   原来这里写完就立刻读一次并 `update(...)`，连点时每次都读到
            //   「车机还没跟上」的中间值 → 显示来回跳（用户反馈「很不连贯」）。
            //   现在：显示一律用乐观值（点了多少显示多少），
            //   回读交给防抖任务 —— 只有停手 1 秒后才读一次最终状态来刷新。
            Logx.d("调温已下发：目标 %.1f℃（zone=0x%x），回读延后到停手 1 秒后".format(target, zone))
            scheduleTempReadback(driver, f, zone, target)
            if (Prefs.syncTemp(ctx)) {
                val otherKey = if (driver) "temp_passenger" else "temp_driver"
                val otherZone = VehicleChannels.zoneOf(ctx, otherKey)
                VehicleBridge.writeTempViaGlyCar(f, otherZone, target) ||
                    VehicleBridge.writeTempCustomize(f, otherZone, target)
                // ★★ v1.4.83：调温时不再写 DUAL 通道 —— 该通道在车机侧是「翻转」语义，
                //   每次调温都写会导致「每第二次调节把双温同步关掉」。
                //   「同步」的本意是同时写另一侧温度（上面已写），无需重设车机双温开关。
            }
            // ★ v1.4.97 #3：合并刷新（连点只跑最后一次）
            scheduleRefresh(ctx, TEMPERATURE_GROUP)
            return
        }

        val ok = writeChannelCandidates(ctx, key, listOf(dir))
        if (ok) {
            if (Prefs.syncTemp(ctx)) {
                val otherKey = if (driver) "temp_passenger" else "temp_driver"
                val otherDir = if (step > 0) 1 else 2
                writeChannelCandidates(ctx, otherKey, listOf(otherDir))
                // 双温同步本身也试着告诉车机
                // ★★ v1.4.83：调温时不再写 DUAL 通道 —— 该通道在车机侧是「翻转」语义，
                //   每次调温都写会导致「每第二次调节把双温同步关掉」。
                //   「同步」的本意是同时写另一侧温度（上面已写），无需重设车机双温开关。
            }
            scheduleRefresh(ctx, TEMPERATURE_GROUP)   // ★ v3.4：合并刷新（连点只跑最后一次）
            Logx.d("调温 ${if (driver) "主驾" else "副驾"} ${if (step > 0) "+" else ""}$step 档 → 档位=$dir OK（本地推算 ${"%.1f".format(next)}℃）")
            return
        }
        Logx.w("调温 ${if (driver) "主驾" else "副驾"} 不可写：0x${f.toString(16)} " +
            "通道支持探测=notavailable，HAL 未开放温度写入")
        // ★★ v1.4.62：**不再自动发 keyevent**。
        //   2026-10-20 实测教训：`input keyevent` 是直接注入当前焦点窗口的，
        //   用户点了一下「试发温度+」车机就黑屏了（弹出黑色图片）。
        //   ⇒ keyevent 是**破坏性操作**，只有在用户明确配置了 keycode、
        //      且明确知道该键作用时才允许发，绝不自动触发。
        //   温度要通，只能靠「抓实体按键」被动拿到真实 keycode 后由用户手动配置。
        Logx.w("调温：温度通道 HAL 不可写。如需用实体按键方案，" +
            "请先在主界面「抓实体按键」抓到真实 keycode 并手动填入保存，" +
            "之后调温才会发送该 keycode。")
        return
    }

    /**
     * ★★ v1.4.54：带候选值的写入 —— 按顺序试多个 value，哪个回读生效就用哪个。
     *
     * 用于「关」这类没有明显编码的场合（如外循环：复合 0x10030100 退化成 functionId 本身，
     * HAL 不认；裸值 0 也不行）。把所有候选一次试完，命中即返回。
     */
    private fun writeChannelCandidates(ctx: Context, key: String, values: List<Int>): Boolean {
        for (v in values) {
            if (writeChannel(ctx, key, v)) return true
        }
        return false
    }

    /** 切换风量 AUTO 档（开/关）。开时写 fan_auto 通道，关时写 fan_manual 通道 */
    /**
     * ★ v1.4.64：切 AUTO 风速。
     *
     * ⚠️ 之前的行为：`writeChannel(ctx, key, level)` —— level 是**手动档 0..9**，
     *    而 AUTO 通道 `0x10020200` 的取值范围是 **1..5**（`mSupportValues=[1,5]`），
     *    把手动档（如 9）写进 AUTO 通道 → HAL 拒绝/忽略 → 车机风速被顶到 9 档
     *    看起来像"只是把风速调到 9，并没有 auto"。
     *
     *    真车日志（1.4.52，19:09:37）实锤成功写入形态：
     *    `写 fan_auto：复合 0x10020201 → OK`  —— 1 档 AUTO。
     *    即 AUTO 通道也吃复合编码，值域 1..5。
     *
     * 修正：
     *  - 开 AUTO：把**当前手动档位按比例折算成 1..5**（9 档 → 5 档 AUTO），
     *    写 `0x10020200 | autoLevel`
     *  - 关 AUTO：写**手动通道** `0x10020100`，恢复原档位
     */
    fun toggleFanAuto(ctx: Context) {
        HvacCommands.supersede("fan")
        if (Looper.myLooper() != main.looper) { main.post { toggleFanAuto(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "toggleFanAuto")) return
        val next = !state.fanAuto
        if (Prefs.simulate(ctx)) {
            preview(FAN_GROUP) { current ->
                current.copy(fanAuto = next, fanLevel = current.fanLevel.coerceAtLeast(1), fanMax = if (next) 5 else 9, connected = true)
            }
            return
        }
        // ★★ v3.5：**先接 AUTO 总开关 0x10010200**（HVAC_FUNC_AUTO，
        //   系统 Dock 里对应 AutoSwitchSignalClick / code 400002）。
        //   之前我们只写 AUTO 风量通道 0x10020200，从没碰过这个总开关。
        //   走 writeSwitch，zone 用全局。
        //
        // ★★★ v3.6：按系统反编译 → **裸值优先**。
        //   `AutoSwitchSignal.setValue` → `setFunctionValue(0x10010200, 0x80000000, 0|1)`
        //   系统写的就是**裸值 0/1**，不做 functionId|value 的复合编码。
        //   注册处：`AutoSwitchSignal(0x10010200, areaId=0x80000000, 0)` ✔ 全局 zone
        val ctxBg = ctx
        hal.execute {
            val okMaster = writeSwitch(
                ctxBg, "auto_master", VehicleChannels.FUNC_AUTO, ZONE_GLOBAL, next, "AUTO 总开关",
                bareFirst = true
            )
            Logx.d("AUTO 总开关 0x10010200 → ${if (next) "开" else "关"}：${if (okMaster) "OK" else "HAL 返回 false"}")
        }
        if (next) {
            // ★★ v1.4.81：**AUTO 写在「手动风量」通道上，值 = 10**。
            //
            // 依据：
            //  1) 这台车**没有** AUTO 风量通道 —— 0x10020200 在**系统自己的**日志里就是
            //     `isFunctionSupported ... status notactive` + `getFunctionValue ... value 255`
            //     （DBPLAY改/研究结果/空调/空调日志整理.txt，10-15 23:45:57）。
            //     我们一直往这个 notactive 通道写 → HAL 直接拒 → 什么都不会发生。
            //  2) oneOS_Dock 常量 `MANUAL_FAN_SPEED_LEVEL_AUTO = "10"`
            //     → AUTO 档位值是 10，**写在手动风量通道 0x10020100 上**，
            //       复合编码即 0x1002010A。
            //  3) 回读能验证：fan_manual 读回 0x1002010A（低字节 10）= 车机处于 AUTO。
            // AUTO 风道档位（1..5 档）
            val manualNow = state.fanLevel.coerceAtLeast(1)
            val autoLevel = Prefs.fanAutoLevel(ctx)
                .takeIf { it in 1..5 }
                ?: ((manualNow + 1) * 5 / 9).coerceIn(1, 5)
            preview(FAN_GROUP) { current -> current.copy(fanAuto = true, fanLevel = autoLevel, fanMax = 5) }
            val ctxBg = ctx
            hal.execute {
                val fAuto = VehicleChannels.funcOf(ctxBg, "fan_auto")
                val zoneA = VehicleChannels.zoneOf(ctxBg, "fan_auto")
                if (fAuto > 0) {
                    val value = HvacChannels.encodeInt(fAuto, "fan_auto", autoLevel) ?: return@execute
                    VehicleBridge.writeInt(fAuto, zoneA, value)
                    val back = VehicleBridge.readInt(fAuto, zoneA)
                    val backLvl = back?.let { HvacChannels.fanAutoLevelOf(it) } ?: 0
                    Logx.d(
                        "写 风量 AUTO（0x10020200 乱序映射）：档位=" + autoLevel +
                        " value=0x" + value.toString(16) + " zone=0x" + zoneA.toString(16) +
                        " 回读=0x" + (back?.toString(16) ?: "读不到") +
                        " 回读档位=" + backLvl +
                        (if (backLvl == autoLevel) "（✓ 一致）" else "")
                    )
                }
            }
        } else {
            val key = "fan_manual"
            val f = VehicleChannels.funcOf(ctx, key)
            if (f <= 0) return
            // ★ v1.4.81：关 AUTO → 写一个**具体手动档**。
            //   注意：AUTO 状态下 state.fanLevel = 0（见 withRaw 的 AUTO 识别），
            //   所以不能再从 fanLevel 折算，直接用 3 档（中速）作为退出 AUTO 后的档位。
            val manual = if (state.fanAuto) 3 else state.fanLevel.coerceIn(1, 9)
            preview(FAN_GROUP) { current -> current.copy(fanAuto = false, fanLevel = manual, fanMax = 9) }
            val ctxBg = ctx
            hal.execute {
                val key = "fan_manual"
                val f = VehicleChannels.funcOf(ctxBg, key)
                if (f <= 0) return@execute
                val composite = (f and 0xFFFFFF00.toInt()) or manual
                val zone = VehicleChannels.zoneOf(ctxBg, key)
                val ok = VehicleBridge.writeInt(f, zone, composite)
                Logx.d(
                    "写 风量 手动：→ 手动 $manual 档 " +
                    "value=0x${composite.toString(16)} zone=0x${zone.toString(16)} → " +
                    if (ok) "OK" else "HAL 返回 false"
                )
            }
        }
        scheduleRefresh(ctx, FAN_GROUP)
    }

    /** 风量 ±1。AUTO 开着时只改 AUTO 风道 1..5 档，不退回手动风速。 */
    fun fanStep(ctx: Context, delta: Int) {
        if (Looper.myLooper() != main.looper) { main.post { fanStep(ctx, delta) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "fanStep")) return
        if (state.fanAuto) {
            val cur = state.fanLevel.takeIf { it in 1..5 } ?: Prefs.fanAutoLevel(ctx).coerceIn(1, 5)
            val next = (cur + delta).coerceIn(1, 5)
            if (next == cur) return
            setFanAutoLevel(ctx, next)
            return
        }
        val start = state.fanLevel
        val next = (start + delta).coerceIn(0, 9)
        if (next == start) return
        if (Prefs.simulate(ctx)) {
            preview(FAN_GROUP) { current -> current.copy(fanLevel = next, fanAuto = false, fanMax = 9, connected = true) }
            return
        }
        val f = VehicleChannels.funcOf(ctx, "fan_manual")
        if (f <= 0) return
        // 乐观更新：图标立即响应（解决"调节后 2-3 秒才显示"）
        val sequence = preview(FAN_GROUP) { current -> current.copy(fanLevel = next, fanAuto = false, fanMax = 9) }
        // ★ v3.13.18：记下用户刚点的目标档位 —— 1.5s 内的旧回读不许覆盖它
        //   （否则连点时数字会在乐观值与回读旧值之间来回摆，见 withRaw 的 fan_manual 分支）
        // ★ v1.4.78：写入放后台（binder IPC 几十毫秒，跑主线程会「顿一下」）
        val ctxBg = ctx
        HvacCommands.submitLatest(ctxBg, "fan", sequence, { cancelTarget(sequence) }) {
            val ok = writeChannel(ctxBg, "fan_manual", next)
            Logx.d("写 风量 手动 idx=$next → ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
        }
        // ★ v1.4.97 #2：合并刷新。原来固定 400ms 后读一次 —— 车机还没跟上时读到旧值，
        //   把刚做的乐观更新顶回去，要再等 2.5s 的轮询才对上，看起来就是「读取延迟很高」。
        //   现在停手 900ms 后才读一次，且连点只跑最后一次。
        scheduleRefresh(ctx, FAN_GROUP)
    }

    /** 内外循环切换 */
    fun toggleRecirc(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { toggleRecirc(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "toggleRecirc")) return
        val target = !state.recircInside
        if (Prefs.simulate(ctx)) {
            preview(CLIMATE_GROUP) { current -> current.copy(recircInside = target, connected = true) }
            return
        }
        val f = VehicleChannels.funcOf(ctx, "recirc")
        if (f <= 0) {
            Logx.e("内外循环：通道未配置")
            return
        }
        // ★ 权威写法（用户提供）：setFunctionValue(0x10030100, 0x8, 1) = 开内循环
        //   → 真车实锤本车吃**复合编码** 0x10030101，且 zone 必须是**全局 0x80000000**
        //     （0x8 会被 HAL 静默丢弃）。zone 命中后已自动记住。
        //
        // ★★ v1.4.63：内外循环是**切换型**通道，不是开/关型。
        //   真车日志实锤（19:57:31）：
        //   ```
        //   target=外 → 试 0/2/3 全 FAIL → 试 1（0x10030101）→ OK
        //   ```
        //   也就是说 HAL 只认 `0x10030101` 这一个值，收到就**切换一次**，
        //   不管当前是内还是外。这解释了：
        //   · 为什么"开内"能成、"关"不成 —— 因为关的时候发的 0 系列 HAL 不认
        //   · 为什么回读校验会误判 —— 写 0x10030100 后回读 0x10030101，低字节不同判"未生效"
        //
        //   ⇒ 统一发 `0x10030101`（切换），且**不做回读校验**（切换型通道回读无意义），
        //     以 setFunctionValue 返回值为准。
        preview(CLIMATE_GROUP) { current -> current.copy(recircInside = target) }
        // ★★ v1.4.75：改成「写目标值 → 回读验证 → 不行就换编码」。
        //
        // 为什么不能盲写：HAL 的返回值不可信（A/C 通道实测「返回 false 但车机真生效」）。
        //   而 recirc 的**回读是可靠的**（能读到 0x10030101=内 / 0x0=外），
        //   所以正确做法是「写完读回来对账」。
        //
        // 已知真车事实：
        //   读：zone=0x8 读不到，zone=全局(0x80000000) 能读到 0x10030101
        //   写：全局 zone + 复合编码 0x10030101 → 返回 true（真·内循环）
        //       全局 zone + 复合编码 0x10030100 → 返回 false（外循环，需回读确认）
        // ★ v1.4.78：HAL 写入 + 回读对账全放后台（主线程只做上面的乐观更新）
        val ctxBg = ctx
        hal.execute { recircWrite(ctxBg, target) }
        scheduleRefresh(ctx, CLIMATE_GROUP)   // ★ v3.4：合并刷新（连点只跑最后一次）
        return
    }

    /** ★ v1.4.78：内外循环的实际写入（后台线程） */
    private fun recircWrite(ctx: Context, target: Boolean) {
        if (!HvacCommands.canWrite(ctx, "recirc")) return
        val function = VehicleChannels.funcOf(ctx, "recirc")
        if (function <= 0) return
        val zone = VehicleChannels.zoneOf(ctx, "recirc")
        val value = HvacChannels.encodeInt(function, "recirc", if (target) 1 else 0) ?: return
        // 全局无 area 与显式全局区是同一目标；仅兼容调用形态，不试其它值或区域。
        val noArea = if (zone == ZONE_GLOBAL && VehicleBridge.hasNoAreaOverload()) {
            VehicleBridge.writeIntNoArea(function, value)
        } else null
        val accepted = noArea == true || VehicleBridge.verifyWrite(function, zone, value)
        Logx.d("写 内外循环 target=${if (target) "内" else "外"} zone=$zone raw=0x${value.toString(16)} accepted=$accepted（生效看真实观测）")
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


    /**
     * ★ v1.4.83：读车机当前的双温同步状态（true=开 false=关 null=读不到）。
     *
     * 通道 0x10060500（HVAC_FUNC_TEMP_DUAL），zone = 全局 0x80000000
     * —— 系统自己也是这么读的（logcat: `getFunctionValue HVAC_FUNC_TEMP_DUAL
     * [0x10060500] zone:VehicleZone.ZONE_ALL [0x80000000] value 0`）。
     * 值：0=关，1=开。
     */
    private fun readDualState(ctx: Context): Boolean? {
        val f = VehicleChannels.funcOf(ctx, "temp_dual")
        if (f <= 0) return null
        for (z in intArrayOf(ZONE_GLOBAL, 0x8, 1)) {
            val raw = VehicleBridge.readInt(f, z) ?: continue
            if (raw == 0xff || raw == 0xfe || raw == 0xfd) continue
            val v = raw and 0xFF
            if (v == 0) return false
            if (v == 1) return true
        }
        return null
    }

    /**
     * ★ v1.4.84：设置 AUTO 风道档位（1..5 档）—— **直接写车机**。
     * 供主界面「AUTO 风道档位」滑条调用（之前只存本地 Pref，车机没反应）。
     */
    fun setFanAutoLevel(ctx: Context, level: Int) {
        if (Looper.myLooper() != main.looper) { main.post { setFanAutoLevel(ctx, level) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setFanAutoLevel")) return
        val l = level.coerceIn(1, 5)
        Prefs.setFanAutoLevel(ctx, l)
        val sequence = preview(FAN_GROUP) { current -> current.copy(fanAuto = true, fanLevel = l, fanMax = 5) }
        if (Prefs.simulate(ctx)) return
        val ctxBg = ctx
        HvacCommands.submitLatest(ctxBg, "fan", sequence, { cancelTarget(sequence) }) {
            val f = VehicleChannels.funcOf(ctxBg, "fan_auto")
            if (f <= 0) {
                Logx.e("AUTO 风道档位：通道未配置")
                return@submitLatest
            }
            val zone = VehicleChannels.zoneOf(ctxBg, "fan_auto")
            val value = HvacChannels.encodeInt(f, "fan_auto", l) ?: return@submitLatest
            val r = VehicleBridge.writeInt(f, zone, value)
            val back = VehicleBridge.readInt(f, zone)
            val backLvl = back?.let { HvacChannels.fanAutoLevelOf(it) } ?: 0
            Logx.d(
                "设置 AUTO 风道档位：" + l + " 档 → value=0x" + value.toString(16) +
                " zone=0x" + zone.toString(16) + " 返回=" + r +
                " 回读档位=" + backLvl
            )
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

    /** A/C 压缩机开 / 关（长按主驾温度触发） */
    fun toggleAc(ctx: Context) {
        if (Looper.myLooper() != main.looper) { main.post { toggleAc(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "toggleAc")) return
        val target = !state.acOn
        if (Prefs.simulate(ctx)) {
            preview(CLIMATE_GROUP) { current -> current.copy(acOn = target, connected = true) }
            return
        }
        val f = VehicleChannels.funcOf(ctx, "ac")
        if (f <= 0) {
            Logx.e("A/C：通道未配置")
            return
        }
        // 与内外循环同一套布尔通道：value 用裸枚举 0/1
        val value = if (target) 1 else 0
        // 乐观更新：图标立即响应；写失败仅打日志，不阻塞 UI
        preview(CLIMATE_GROUP) { current -> current.copy(acOn = target) }
        // ★ v1.4.78：写入放后台（binder IPC 会让点击「顿一下」）
        val ctxBg = ctx
        hal.execute {
            val ok = writeChannel(ctxBg, "ac", value)
            Logx.d(
                "写 A/C target=${if (target) "开" else "关"} " +
                "调用=车控写入(0x${f.toString(16)}, 0x${VehicleChannels.zoneOf(ctxBg, "ac").toString(16)}, $value) → " +
                if (ok) "OK" else "HAL 返回 false（该通道读回 0xff，回读校验失效；实测车机会生效）"
            )
        }
        // ★ v1.4.60：A/C 通道读回恒 0xff（UNKNOWN），所以 HVAC_VERIFY 的回读校验对它无效
        //   （拿 0xff 比对永远判「未生效」）。用户 2026-10-20 确认：A/C / 前挡 / 后挡
        //   **车机真实响应**，尽管 setFunctionValue 返回 false。
        //   ⇒ 对这三个通道，setFunctionValue 返回 false 只表示「HAL 未确认」，不代表被拒。
        scheduleRefresh(ctx, CLIMATE_GROUP)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** 风速直接关到 0 档（长按风速触发）。乐观更新 + 400ms 后回读校准 */
    fun setFanOff(ctx: Context) {
        HvacCommands.supersede("fan")
        if (Looper.myLooper() != main.looper) { main.post { setFanOff(ctx) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setFanOff")) return
        if (Prefs.simulate(ctx)) {
            preview(FAN_GROUP) { current -> current.copy(fanLevel = 0, fanAuto = false, fanMax = 9, connected = true) }
            return
        }
        val f = VehicleChannels.funcOf(ctx, "fan_manual")
        if (f <= 0) return
        // 乐观更新：图标立即响应
        preview(FAN_GROUP) { current -> current.copy(fanLevel = 0, fanAuto = false, fanMax = 9) }
        hal.execute {
            val ok = writeChannel(ctx, "fan_manual", 0)
            Logx.d("写 风速 0（OFF）→ ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
        }
        scheduleRefresh(ctx, FAN_GROUP)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** 风向循环切换（按 7 种组合位掩码循环） */
    fun cycleAirDirection(ctx: Context) {
        val order = AIR_MODES
        val cur = state.airDirection
        val idx = order.indexOf(cur)
        val next = if (idx < 0) order.first() else order[(idx + 1) % order.size]
        setAirDirection(ctx, next)
    }

    /**
     * 直接设置风向为指定的位组合（1=吹脸 2=吹脚 4=吹前挡，至少选中一项）。
     * 供风向弹窗的 3 个独立开关使用（每个方向单独开/关，可任意组合）。
     */
    fun setAirDirection(ctx: Context, bits: Int) {
        if (Looper.myLooper() != main.looper) { main.post { setAirDirection(ctx, bits) }; return }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, "setAirDirection")) return
        val next = bits and 0x07
        if (next == 0) {
            Logx.d("风向：不能全部关闭，忽略")
            return
        }
        if (Prefs.simulate(ctx)) {
            preview(CLIMATE_GROUP) { current -> current.copy(airDirection = next, connected = true) }
            return
        }
        val f = VehicleChannels.funcOf(ctx, "air_direction")
        if (f <= 0) {
            Logx.e("风向：通道未配置")
            return
        }
        // 乐观更新：图标立即响应
        preview(CLIMATE_GROUP) { current -> current.copy(airDirection = next) }
        hal.execute {
            val ok = writeChannel(ctx, "air_direction", next)
            Logx.d("写 风向 mode=0x${next.toString(16)} (${AirFlowDrawable.label(next)}) → ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
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
        ctx, "seat_vent", state.seatVent, level,
        set = { s, v -> s.copy(seatVent = v) },
        label = "座椅通风"
    )

    /**
     * 主驾座椅加热：直接设到指定档位（0=关 1..3）。
     * ★ 通道：functionId=0x10050200（HVAC_FUNC_SEAT_HEATING），zone=1（主驾）
     */
    fun setSeatHeatLevel(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_heat", state.seatHeat, level,
        set = { s, v -> s.copy(seatHeat = v) },
        label = "座椅加热"
    )

    /** 座椅通风 / 加热共用的写档位逻辑 */
    private fun writeSeatLevel(
        ctx: Context,
        key: String,
        cur: Int,
        level: Int,
        set: (State, Int) -> State,
        label: String
    ) {
        if (Looper.myLooper() != main.looper) {
            main.post {
                val current = when (key) {
                    "seat_vent" -> state.seatVent
                    "seat_heat" -> state.seatHeat
                    "seat_vent_passenger" -> state.seatVentPassenger
                    else -> state.seatHeatPassenger
                }
                writeSeatLevel(ctx, key, current, level, set, label)
            }
            return
        }
        if (!Prefs.simulate(ctx) && !HvacCommands.canSubmit(ctx, label)) return
        val next = level.coerceIn(0, SEAT_LEVEL_MAX)
        if (next == cur) return
        if (Prefs.simulate(ctx)) {
            preview(COMFORT_GROUP) { current -> set(current, next).copy(connected = true) }
            return
        }
        val f = VehicleChannels.funcOf(ctx, key)
        if (f <= 0) {
            Logx.e("$label：通道未配置")
            return
        }
        // 取值：座椅通风/加热的 HAL 规范值是「纯档位 0..3」，不是 functionId|档位
        // （watcher 回传 seat_vent/seat_heat 的 value=0/1/2/3，而非 0x100501xx）。
        // 与内外循环统一：一律写裸档位，失败才回退复合编码。
        // 乐观更新：图标立即响应
        preview(COMFORT_GROUP) { current -> set(current, next) }
        val ctxBg = ctx
        hal.execute {
            val ok = writeChannel(ctxBg, key, next)
            Logx.d("写 $label idx=$next → ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
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

    private fun cancelTarget(sequence: Long) {
        onMain { hvacState.cancel(sequence); publishDisplay() }
    }

    private fun publishDisplay(forceNotify: Boolean = false) {
        val c = appCtx
        if (c != null && !Prefs.simulate(c) && (!Prefs.vehicleApiEnabled(c) || !Prefs.vehicleWriteEnabled(c))) {
            hvacState.clearTargets()
        }
        val next = hvacState.display(SystemClock.elapsedRealtime(), VehicleBridge.connectionGeneration)
        val previous = state
        state = next
        main.removeCallbacks(targetExpiry)
        val deadline = hvacState.nextDeadlineMs
        if (deadline != Long.MAX_VALUE) main.postDelayed(targetExpiry, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
        if (next != previous || forceNotify) listeners.toList().forEach { listener -> runCatching { listener(next) } }
    }
}
