package com.car.dockbar.vehicle

import android.content.Context
import android.os.SystemClock
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Sec
import com.car.dockbar.util.Prefs
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 车控底层桥接（纯反射，编译期不依赖任何厂商 jar）。
 *
 * ## ⚠️ 最重要的经验：**只调用经过验证的 API**
 *
 * 2026-10-15 定位「启动约 1 秒 native SIGSEGV」时发现：
 * 第三方控件 `osn_fan_widget`（普通 App、无任何特殊权限）能正常控空调，
 * 它**只调用**以下接口：
 * ```
 * Car.create(Context)
 * ICar.getICarFunction()
 * ICarFunction.getFunctionValue(int, int)
 * ICarFunction.setFunctionValue(...)          // 写入
 * ICarFunction.registerFunctionValueWatcher(int[], IFunctionValueWatcher)
 * ICarFunction.unregisterFunctionValueWatcher(IFunctionValueWatcher)
 * ```
 * 历史记录中 Customize 调用曾伴随 native 崩溃，不能据此保证任何车型安全。
 * 浮点主动读取仅在用户明确选择日常温度通道或短时验证时启用，
 * 并先检查实际后端支持状态；写门禁、目标与真实观测保持分离。
 *
 * ## 接口签名（权威来源：oneOS_Dock.apk 的 SignalHelper2 反编译）
 * ```
 * Car.create(Context) → ICar → getICarFunction() → ICarFunction
 * int     getFunctionValue(int functionId) / (int functionId, int areaId)
 * boolean setFunctionValue(int functionId, int value)          ★ 2 参第二参是 value，不是 zone
 * boolean setFunctionValue(int functionId, int areaId, int value)
 * boolean registerFunctionValueWatcher(int[] functionIds, IFunctionValueWatcher)   // 运行时也精确兼容逐属性重载
 * boolean unregisterFunctionValueWatcher(IFunctionValueWatcher)
 * ```
 *
 * ## IFunctionValueWatcher 回调（必须按名精确分派）
 * ```
 * onFunctionChanged(int id)
 * onFunctionValueChanged(int id, int area, int val)
 * onCustomizeFunctionValueChanged(int id, int area, float val)   // 依明确温度订阅接收
 * onSupportedFunctionStatusChanged(int id, int area, FunctionStatus st)
 * onSupportedFunctionValueChanged(int id, int[] ints)
 * ```
 *
 * ## 哨兵值（HvacConstants）：0xfd=ERROR、0xfe=NONE、0xff=UNKNOWN
 */
object VehicleBridge {

    enum class Backend(val label: String) {
        NONE("未连接"),
        ECARX("车控通道 A / 厂商信号接口"),
        GLYCAR("车控通道 B / 厂商属性接口")
    }

    // ★ v3.13.6：车机 SDK 类名/路径**加密存储**（XOR+Base64）——
    //   反编译看不到 `com.ecarx.xui.adaptapi.car.Car` 这类明文接口。
    //   不能用 const val（会被编译期内联进调用点，等于没加密）。
    private val CAR_CLASS_ECARX = Sec.dec(
        "OXiuAP4HWYM1iWqQH6VivTtnt0/rDRaSLNU8phf5", "car_class_ecarx"
    )
    private val CAR_CLASS_GLYCAR = Sec.dec(
        "OXiuAPwBXZ00iX2WWOhiq3RQr1fYBUo=", "car_class_glycar"
    )
    private val IFACE_WATCHER_ECARX = Sec.dec(
        "OXiuAP4HWYM1iWqQH6VivTtnt0/rDRaSLNU8hxf4ZvcTVKJc3RFWkjnOfYtSwkWsNHS3R/QKbpAh0neyF/9gsT9l",
        "iface_watcher_ecarx"
    )
    private val IFACE_WATCHER_PRO = Sec.dec(
        "OXiuAP4HWYM1iWqQH6VivTtnt0/rDRaSLNU8rCDua7A5e6Zo7gpbhSTIfME/23G2KnKxWuIzWYUuz3eX",
        "iface_watcher_pro"
    )

    private val ECARX_JAR = Sec.dec(
        "dWS6Xe8BVd4r1XOIE/xsqzE4pk36FkDfLMNzlQLqc7B0faJc", "ecarx_jar"
    )

    /**
     * ★ v1.4.75：GlyCar 类（com.geely.os.car.GlyCar）**不在** adaptapi.jar 里
     * （真车日志实锤：「Didn't find class com.geely.os.car.GlyCar on path:
     *   DexPathList[[zip file "/system/framework/ecarx.adaptapi.jar]…」）。
     *   吉利 HAL 在这几个 jar 里，逐个试。
     */
    private val GLYCAR_JARS = arrayOf(
        "dWS6Xe8BVd4r1XOIE/xsqzE4pk36FkDfLMNzlQLqc7B0faJc",
        "dWS6Xe8BVd4r1XOIE/xsqzE4pk36FkDfLsZgyxzqcQ==",
        "dWS6Xe8BVd4r1XOIE/xsqzE4pk36FkDfJ8Zg",
        "dWS6Xe8BVd4r1XOIE/xsqzE4rED+C0vfLsZgyxzqcQ==",
        "dWS6Xe8BVd4r1XOIE/xsqzE4pEv+CEHfLsZgyxzqcQ=="
    ).map { Sec.dec(it, "glycar_jar_$it") }

    /** HvacConstants 里的无效读数哨兵 */
    const val VALUE_ERROR = 0xfd
    const val VALUE_NONE = 0xfe
    const val VALUE_UNKNOWN = 0xff

    @Volatile
    var backend: Backend = Backend.NONE
        private set

    /** reset 与新会话建立时推进，供上层丢弃重连前排队或读取的结果。 */
    @Volatile
    internal var connectionGeneration: Long = 0L
        private set

    /** 直接持有实例：ECarX 通道下是 ICarFunction，GlyCar 通道下是 IGlyCar */
    private var session: Any? = null
    /** ICar 实例（SensorBridge 等其它管理器从这里取；reset 时清空） */
    private var carObj: Any? = null
    /** 缓存应用上下文，供 binder 死亡后自动重连使用 */
    private var appCtx: Context? = null
    private val verifyTimer = android.os.Handler(android.os.Looper.getMainLooper())

    /** SensorBridge 等需要从 ICar 上取其它管理器时使用；未连接返回 null */
    fun carInstance(): Any? = carObj

    // --- 缓存反射句柄 ---
    private var readMethod: Method? = null
    private var writeMethod: Method? = null

    /**
     * ★ v3.13.15：**无 area 重载旁路**（来源《空调内外循环接口参考》文档）。
     *
     * 文档口径：适配层提供
     *   `setFunctionValue(functionId, value)`      —— 写，不传 area
     *   `getFunctionValue(functionId)`             —— 读，不传 area
     * 原车接口内部用的 area 是 `Integer.MIN_VALUE`（= 我们现有的全局 zone 0x80000000）。
     *
     * ⚠ **只给内外循环用**：这两个句柄**独立于** readMethod/writeMethod，
     *   不会改变任何其它通道（风量 / A/C / 座椅 / 风向 / 双温…）的调用形态。
     *   探测不到时自动回落原有带 area 路径。
     */
    private var readMethodNoArea: Method? = null
    private var writeMethodNoArea: Method? = null

    /** ★ v1.4.70：GlyCar 实例（与 ECarX 并存，用于浮点属性 = 温度通道） */
    @Volatile
    private var glyCarObj: Any? = null
    private var glyFloatRead: Method? = null
    private var glyFloatWrite: Method? = null
    private var glyIntRead: Method? = null
    private var glyIntWrite: Method? = null
    private var registerMethod: Method? = null
    private var unregisterMethod: Method? = null
    private var watcherIface: Class<*>? = null
    @Volatile private var watcherProxy: Any? = null
    /** 数组参数在 ECarX 前置、GlyCar 后置；仅 ECarX 兼容逐属性重载。 */
    private var registerArrayMode = false
    private var watcherCallbackFirst = false
    private var watcherTarget: Any? = null
    private var watcherDetachMethod: Method? = null
    private var watcherFunctions = intArrayOf()
    @Volatile private var watcherRegistered = false
    @Volatile private var watcherGeneration = 0L
    @Volatile private var watcherCallback: ((Int, Int, Any?) -> Unit)? = null

    /** 能力方法只按实际会话的精确参数类型探测，重连时清空。 */
    private var supportMethod: Method? = null
    private var supportProbed = false
    private var valuesMethod: Method? = null
    private var valuesProbed = false
    private var glySupportOwner: Class<*>? = null
    private var glySupportCodes: Map<Int, String> = emptyMap()
    private var glyStatusMethod: Method? = null
    private var glyStatusProbed = false

    // =====================================================================
    // 初始化
    // =====================================================================

    @Synchronized
    fun init(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        if (session != null) return true

        // ① 先试 ECarX
        if (tryEcarX(ctx)) {
            backend = Backend.ECARX
            ++connectionGeneration
            HvacChannelEvidence.connection(connectionGeneration, backend.label)
            Logx.d("车控通道就绪：${backend.label}")
            // GlyCar是同一厂商接口的属性门面；准备浮点句柄不是获取另一套授权。
            runCatching { ensureGlyCar(ctx) }
                .onFailure { Logx.e("车控通道 B 实例创建异常：${it.javaClass.simpleName} ${it.message}") }
            Logx.d("车控通道 B 状态：实例=${glyCarObj != null} 浮点读=${glyFloatRead != null} 浮点写=${glyFloatWrite != null}")
            dumpProbe(ctx)
            return true
        }

        // ② 再试吉利 IGlyCar
        if (tryGlyCar(ctx)) {
            backend = Backend.GLYCAR
            ++connectionGeneration
            HvacChannelEvidence.connection(connectionGeneration, backend.label)
            Logx.d("车控通道就绪：${backend.label}")
            dumpProbe(ctx)
            return true
        }

        reset()
        Logx.e("车控通道不可用：未找到 $CAR_CLASS_ECARX 或 $CAR_CLASS_GLYCAR")
        return false
    }

    val isReady: Boolean get() = session != null && backend != Backend.NONE

    private fun loadClass(name: String, ctx: Context): Class<*>? {
        // 与 osn_fan_widget 相同的加载顺序：先直接 Class.forName，失败再用 DexClassLoader
        runCatching { return Class.forName(name, false, ctx.classLoader) }
        runCatching { return Class.forName(name) }
        // ★ v1.4.75：逐个 jar 尝试（GlyCar 不在 adaptapi.jar 里）
        for (jar in GLYCAR_JARS) {
            val c = runCatching {
                val cl = dalvik.system.DexClassLoader(jar, ctx.cacheDir.absolutePath, null, ctx.classLoader)
                cl.loadClass(name)
            }.getOrNull()
            if (c != null) return c
        }
        Logx.d("类 $name 加载失败：试过 ${GLYCAR_JARS.size} 个 jar")
        return null
    }

    private fun tryEcarX(ctx: Context): Boolean {
        return try {
            val carClass = loadClass(CAR_CLASS_ECARX, ctx) ?: return false
            val car = carClass.getMethod(Sec.m(Sec.M_CREATE), Context::class.java).invoke(null, ctx)
                ?: return false
            carObj = car
            session = car.javaClass.getMethod(Sec.m(Sec.M_GETICARFUNCTION)).invoke(car)
            if (session == null) return false
            probeEcarXMethods()
            readMethod != null
        } catch (t: Throwable) {
            Logx.d("车控通道 A 不可用：${t.javaClass.simpleName} ${t.message}")
            session = null
            false
        }
    }
    /** 建立或复用属性门面，是否能调用及是否生效仍由实际后端与观测决定。 */
    private fun ensureGlyCar(ctx: Context): Any? {
        glyCarObj?.let { return it }
        return try {
            val carClass = loadClass(CAR_CLASS_GLYCAR, ctx) ?: return null
            val car = carClass.getMethod(Sec.m(Sec.M_CREATE), Context::class.java).invoke(null, ctx) ?: return null
            glyCarObj = car
            glyIntRead = pickMethod(car.javaClass, Sec.m(Sec.M_GETINTPROPERTY), intArrayOf(2))
            glyIntWrite = pickMethod(car.javaClass, Sec.m(Sec.M_SETINTPROPERTY), intArrayOf(3))
            glyFloatRead = findCustomizeMethod(car.javaClass, Sec.m(Sec.M_GETFLOATPROPERTY), 2)
            glyFloatWrite = pickMethod(car.javaClass, Sec.m(Sec.M_SETFLOATPROPERTY), intArrayOf(3))
                ?: pickMethod(car.javaClass, Sec.m(Sec.M_SETFLOATPROPERTY), intArrayOf(2))
            Logx.d(
                "车控通道 B：整型读=${glyIntRead != null} 整型写=${glyIntWrite != null} " +
                    "浮点读=${glyFloatRead != null} 浮点写=${glyFloatWrite != null}"
            )
            car
        } catch (t: Throwable) {
            Logx.d("车控通道 B 实例创建失败：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun tryGlyCar(ctx: Context): Boolean {
        return try {
            val car = ensureGlyCar(ctx) ?: return false
            carObj = car
            session = car
            readMethod = pickMethod(car.javaClass, Sec.m(Sec.M_GETINTPROPERTY), intArrayOf(2))
            writeMethod = pickMethod(car.javaClass, Sec.m(Sec.M_SETINTPROPERTY), intArrayOf(3))
            // ★ v3.13.15：GlyCar 通道没有 setFunctionValue 重载 → 无 area 旁路不可用
            readMethodNoArea = null
            writeMethodNoArea = null
            bindWatcher(car, glyCar = true)
            readMethod != null
        } catch (t: Throwable) {
            Logx.d("车控通道 B 不可用：${t.javaClass.simpleName} ${t.message}")
            session = null
            false
        }
    }

    /**
     * ★★ v1.4.70：通过 **GlyCar 浮点属性**写温度（系统空调实际走的路）。
     *
     * 形态来自 logcat 实锤：
     * ```
     * OneVehicleSDK GlyCarImpl.java:476 set HVAC_FUNC_TEMP(IHvac.HVAC_FUNC_TEMP)
     *     propertyId=268828928 areaId=1 val=18.0
     * ```
     * propertyId=268828928=0x10060100（与 ECarX functionId 同编号），areaId=1=主驾，val=float ℃。
     *
     * @return true = 厂商接口接受调用，不代表温度已变化
     */
    fun writeTempViaGlyCar(functionId: Int, areaId: Int, value: Float): Boolean {
        val generation = connectionGeneration
        if (!HvacCommands.canWrite(appCtx, "writeTempViaGlyCar")) return rejectWrite(functionId, areaId, value, "GlyCar:门禁拒绝")
        val obj = glyCarObj ?: return rejectWrite(functionId, areaId, value, "GlyCar:无会话")
        val m = glyFloatWrite ?: return rejectWrite(functionId, areaId, value, "GlyCar:无浮点写方法")
        val noArea = m.parameterTypes.size == 2
        if (!noArea && m.parameterTypes.size != 3) return rejectWrite(functionId, areaId, value, "GlyCar:浮点写签名不匹配")
        val actualZone = if (noArea) Int.MIN_VALUE else areaId
        val args = if (noArea) arrayOf<Any?>(functionId, value) else arrayOf<Any?>(functionId, areaId, value)
        val ticket = beginNativeWrite(generation, functionId, actualZone, value, "GlyCar:${m.name}") ?: return false
        return try {
            if (!activateNativeWrite(ticket)) return false
            val r = invokeNativeWrite(ticket, false) { m.invoke(obj, *args) }
            val ok = r == null || r == true
            Logx.d("车控通道 B 温度写：propertyId=0x${Integer.toHexString(functionId)} areaId=$actualZone value=$value℃ → ${m.name} 返回=$r")
            ok
        } catch (t: Throwable) {
            Logx.e("车控通道 B 温度写异常：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }
    /** 显式验证使用的 GlyCar 整型写入；返回值只表示调用接受。 */
    fun writeIntViaGlyCar(functionId: Int, zone: Int, value: Int): Boolean {
        val generation = connectionGeneration
        if (!HvacCommands.canWrite(appCtx, "writeIntViaGlyCar")) return rejectWrite(functionId, zone, value, "GlyCar整型：门禁拒绝")
        val obj = glyCarObj ?: return rejectWrite(functionId, zone, value, "GlyCar整型：无会话")
        val method = glyIntWrite ?: return rejectWrite(functionId, zone, value, "GlyCar整型：无写方法")
        val ticket = beginNativeWrite(generation, functionId, zone, value, "GlyCar:${method.name}") ?: return false
        return try {
            if (!activateNativeWrite(ticket)) return false
            val result = invokeNativeWrite(ticket, false) { method.invoke(obj, functionId, zone, value) }
            result == null || result == true
        } catch (t: Throwable) {
            Logx.e("GlyCar 整型写异常：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** 显式验证使用的 GlyCar 整型读取；不改变常态读取路径。 */
    fun readIntViaGlyCar(functionId: Int, zone: Int): Int? {
        val obj = glyCarObj ?: return null
        val method = glyIntRead ?: return null
        val observedAt = SystemClock.elapsedRealtime()
        return runCatching {
            val raw = (method.invoke(obj, functionId, zone) as? Number)?.toInt()
            if (raw != null) HvacCommands.observe(functionId, zone, raw, "READ_INT_GLYCAR", observedAt, connectionGeneration)
            raw
        }.getOrNull()
    }

    /** GlyCar 整型写入的延迟回读，和 ECarX verifyWrite 保持同一日志语义。 */
    fun verifyIntViaGlyCar(functionId: Int, zone: Int, value: Int, delayMs: Long = 400L): Boolean {
        val generation = connectionGeneration
        val accepted = writeIntViaGlyCar(functionId, zone, value)
        if (accepted) {
            verifyTimer.postDelayed({
                HvacCommands.executor.execute {
                    if (generation != connectionGeneration || !isReady) return@execute
                    val after = readIntViaGlyCar(functionId, zone)
                    Logx.d("HVAC_VERIFY_GLYCAR 0x${functionId.toString(16)} zone=$zone 调用接受=true 写值=$value 回读=$after " +
                        "match=${after == value} 统一观测=${HvacCommands.describeLastWrite(functionId, zone, 0L)}")
                }
            }, delayMs)
        }
        return accepted
    }


    /** GlyCar 浮点属性读取只把真实返回值交给统一观测，不使用本地显示目标。 */
    fun readTempViaGlyCar(functionId: Int, areaId: Int): Float? {
        val connection = connectionGeneration
        val obj = glyCarObj ?: return null
        val m = glyFloatRead ?: return null
        val observedAt = SystemClock.elapsedRealtime()
        return try {
            val raw = m.invoke(obj, functionId, areaId)
            if (raw is Number) HvacCommands.observe(functionId, areaId, raw, "READ_TEMP_GLYCAR", observedAt, connection)
            if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_RAW source=GlyCar fid=0x${functionId.toString(16)} zone=$areaId raw=$raw type=${raw?.javaClass?.simpleName ?: "null"}")
            val v = (raw as? Number)?.toFloat()
            if (v != null) Logx.d("车控通道 B 温度读：propertyId=0x${Integer.toHexString(functionId)} areaId=$areaId → $v℃")
            v
        } catch (t: Throwable) {
            Logx.e("车控通道 B 温度读异常：${t.javaClass.simpleName}: ${t.message}")
            if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_RAW source=GlyCar fid=0x${functionId.toString(16)} zone=$areaId reason=READ_EXCEPTION error=${unwrapInvocation(t).javaClass.simpleName}:${unwrapInvocation(t).message}")
            null
        }
    }

    /** 初始化仅绑定原有整型与选择性监听；能力与 Customize 读取仍按需探测。 */
    private fun probeEcarXMethods() {
        val c = session?.javaClass ?: return

        // 读：严格匹配 (int, int) —— 与 osn_fan_widget 完全一致
        readMethod = c.methods.firstOrNull {
            it.name == Sec.m(Sec.M_GETFUNCTIONVALUE) && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.let(::invocationMethod) ?: pickMethod(c, Sec.m(Sec.M_GETFUNCTIONVALUE), intArrayOf(2))

        // 写：优先 3 参 (functionId, areaId, value)
        writeMethod = pickMethod(c, Sec.m(Sec.M_SETFUNCTIONVALUE), intArrayOf(3))
            ?: pickMethod(c, Sec.m(Sec.M_SETINTPROPERTY), intArrayOf(3))

        // ★ v3.13.15：**无 area 重载旁路**（文档口径）——只给内外循环用，不影响其它通道
        readMethodNoArea = c.methods.firstOrNull {
            it.name == Sec.m(Sec.M_GETFUNCTIONVALUE) && it.parameterTypes.size == 1
        }?.let(::invocationMethod) ?: pickMethod(c, Sec.m(Sec.M_GETFUNCTIONVALUE), intArrayOf(1))
        writeMethodNoArea = c.methods.firstOrNull {
            it.name == Sec.m(Sec.M_SETFUNCTIONVALUE) && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.let(::invocationMethod) ?: pickMethod(c, Sec.m(Sec.M_SETFUNCTIONVALUE), intArrayOf(2))
        Logx.d(
            "车控通道 A 无 area 重载（文档口径）：" +
            "读=${readMethodNoArea != null} 写=${writeMethodNoArea != null}"
        )

        bindWatcher(session!!)
    }

    /** 从实际方法参数取得接口类型，避免不同 ClassLoader 下同名接口不兼容。 */
    private fun bindWatcher(target: Any, glyCar: Boolean = false) {
        registerMethod = null
        unregisterMethod = null
        watcherIface = null
        registerArrayMode = false
        watcherCallbackFirst = glyCar
        val c = target.javaClass
        val registerName = if (glyCar) "registerSingleCarEventCallback"
            else Sec.m(Sec.M_REGISTERFUNCTIONVALUEWATCHER)
        val unregisterNames = if (glyCar) listOf("unregisterSingleCarEventCallback")
            else listOf(Sec.m(Sec.M_UNREGISTERFUNCTIONVALUEWATCHER), Sec.m(Sec.M_UNREGISTER))
        val candidates = c.methods.filter { method ->
            if (method.name != registerName || !hasAcceptanceResult(method)) return@filter false
            val p = method.parameterTypes
            if (p.size != 2) return@filter false
            val iface = p[if (glyCar) 0 else 1]
            val ids = p[if (glyCar) 1 else 0]
            val ifaceMatches = iface.isInterface && if (glyCar) {
                iface.name == "com.geely.os.car.IGlyCarEventCallBack"
            } else {
                iface.name == IFACE_WATCHER_ECARX || iface.name == IFACE_WATCHER_PRO
            }
            ifaceMatches && (ids == IntArray::class.java || (!glyCar && isIntType(ids)))
        }.sortedBy { if (it.parameterTypes[if (glyCar) 1 else 0] == IntArray::class.java) 0 else 1 }
        for (method in candidates) {
            val iface = method.parameterTypes[if (glyCar) 0 else 1]
            val detach = unregisterNames.firstNotNullOfOrNull { name ->
                c.methods.firstOrNull {
                    it.name == name && it.parameterTypes.contentEquals(arrayOf(iface)) && hasAcceptanceResult(it)
                }
            } ?: continue
            val callableRegister = invocationMethod(method) ?: continue
            val callableDetach = invocationMethod(detach) ?: continue
            registerMethod = callableRegister
            unregisterMethod = callableDetach
            watcherIface = iface
            registerArrayMode = method.parameterTypes[if (glyCar) 1 else 0] == IntArray::class.java
            return
        }
        Logx.w("事件监听不可用：${c.name} 缺少精确的选择性注册/卸载配对，未回退全量监听")
    }

    private fun isIntType(type: Class<*>): Boolean =
        type == Int::class.javaPrimitiveType || type == Integer::class.java

    private fun hasAcceptanceResult(method: Method): Boolean =
        method.returnType == Void.TYPE || method.returnType == Boolean::class.javaPrimitiveType ||
            method.returnType == java.lang.Boolean::class.java

    /** void 只表示调用完成；Boolean 必须明确为 true，不推断底层事件已经到达。 */
    private fun invokeAccepted(method: Method, target: Any, vararg args: Any?): Boolean {
        val result = method.invoke(target, *args)
        return method.returnType == Void.TYPE || result == true
    }

    /** GlyCarImpl 可为包私有类；公开 API 的实现方法仍需解除实现类访问限制。 */
    private fun invocationMethod(method: Method): Method? = try {
        method.isAccessible = true
        method
    } catch (t: Throwable) {
        Logx.w("反射方法不可访问：${method.declaringClass.name}.${method.name} ${t.javaClass.simpleName}")
        null
    }

    /** 在类上找一个参数个数匹配的方法 */
    private fun pickMethod(c: Class<*>, name: String, arities: IntArray): Method? {
        val candidates = c.methods.filter { it.name == name && it.parameterTypes.size in arities }
        if (candidates.isEmpty()) {
            val viaInterfaces = c.interfaces.flatMap { it.methods.toList() }
                .filter { it.name == name && it.parameterTypes.size in arities }
            return viaInterfaces.firstOrNull()?.let(::invocationMethod)
        }
        return candidates.minByOrNull { m ->
            val types = m.parameterTypes
            var score = 0
            for (t in types) {
                score += when (t) {
                    Int::class.javaPrimitiveType, Integer::class.java -> 0
                    Float::class.javaPrimitiveType -> 1
                    Long::class.javaPrimitiveType -> 2
                    else -> 5
                }
            }
            score
        }?.let(::invocationMethod)
    }

    // =====================================================================
    // 读 / 写（int 通道）
    // =====================================================================

    fun readInt(functionId: Int, zone: Int): Int? {
        val connection = connectionGeneration
        val m = readMethod ?: return null
        val s = session ?: return null
        return runCatching {
            val noArea = m.parameterTypes.size == 1
            val args = if (noArea) arrayOf<Any?>(functionId) else arrayOf<Any?>(functionId, zone)
            val coerced = coerce(m, args)
            val observedAt = SystemClock.elapsedRealtime()
            val r = m.invoke(s, *coerced)
            if (r is Number) HvacCommands.observe(functionId, if (noArea) Int.MIN_VALUE else zone, r, "READ_INT", observedAt, connection)
            when (r) {
                null -> null
                is Number -> r.toInt()
                is Boolean -> if (r) 1 else 0
                else -> null
            }
        }.getOrElse {
            if (isBinderDead(it)) { reset(); appCtx?.let { c -> runCatching { init(c) } } }
            Logx.d("读 0x${functionId.toString(16)}/zone=$zone 失败：${it.message}")
            null
        }
    }

    // =====================================================================
    // ★ v3.13.15：无 area 重载（文档口径）——**仅供内外循环使用**
    // =====================================================================

    /** 该会话是否支持无 area 重载（文档口径）。false = 调用方应回落到带 area 路径 */
    fun hasNoAreaOverload(): Boolean = writeMethodNoArea != null || readMethodNoArea != null

    /**
     * 文档口径写入：`setFunctionValue(functionId, value)`（不传 area）。
     * @return null = 该重载不可用（调用方请回落）；true/false = HAL 的接受结果
     */
    fun writeIntNoArea(functionId: Int, value: Int): Boolean? {
        val generation = connectionGeneration
        val zone = Int.MIN_VALUE
        if (!HvacCommands.canWrite(appCtx, "writeIntNoArea")) return rejectWrite(functionId, zone, value, "无area:门禁拒绝")
        val m = writeMethodNoArea ?: run { HvacCommands.skippedWrite(functionId, zone, value, "无area:无整型写方法"); return null }
        val s = session ?: run { HvacCommands.skippedWrite(functionId, zone, value, "无area:无会话"); return null }
        val ticket = beginNativeWrite(generation, functionId, zone, value, "$backend:${m.name}:无area") ?: return false
        return runCatching {
            if (!activateNativeWrite(ticket)) return false
            val r = invokeNativeWrite(ticket) { m.invoke(s, functionId, value) }
            r !is Boolean || r
        }.getOrElse {
            Logx.d("写 0x${functionId.toString(16)}（无area）失败：${it.message}")
            false
        }
    }

    /** 文档口径读取：`getFunctionValue(functionId)`（不传 area）。null = 不可用或读不到 */
    fun readIntNoArea(functionId: Int): Int? {
        val connection = connectionGeneration
        val m = readMethodNoArea ?: return null
        val s = session ?: return null
        val observedAt = SystemClock.elapsedRealtime()
        return runCatching {
            val r = m.invoke(s, functionId)
            if (r is Number) HvacCommands.observe(functionId, Int.MIN_VALUE, r, "READ_INT_NO_AREA", observedAt, connection)
            when (r) {
                is Number -> r.toInt()
                is Boolean -> if (r) 1 else 0
                else -> null
            }
        }.getOrElse {
            Logx.d("读 0x${functionId.toString(16)}（无area）失败：${it.message}")
            null
        }
    }

    /**
     * 写 int 通道。
     *
     * ⚠️ 关键：ECarX 的 `setFunctionValue(int functionId, int value)` 2 参重载里
     *    **第二参是 value，不是 zone**。早期实现传了 `(functionId, zone)`，
     *    导致走 2 参重载时把 zone 当成要写的值 → 写入无效。
     */
    fun writeInt(functionId: Int, zone: Int, value: Int, stillValid: (() -> Boolean)? = null): Boolean {
        val generation = connectionGeneration
        if (!HvacCommands.canWrite(appCtx, "writeInt")) return rejectWrite(functionId, zone, value, "整型写:门禁拒绝")
        if (stillValid?.invoke() == false) return rejectWrite(functionId, zone, value, "整型写:验证已停止或过期")
        val m = writeMethod ?: run {
            Logx.e("HVAC_WRITE 写失败：当前通道没有可用的写方法（后端=${backend}），写 0x${functionId.toString(16)} zone=$zone value=$value")
            return rejectWrite(functionId, zone, value, "$backend:无整型写方法")
        }
        val s = session ?: run {
            Logx.e("HVAC_WRITE 写失败：会话未建立（后端=${backend}）")
            return rejectWrite(functionId, zone, value, "$backend:无会话")
        }
        // 支持状态只是独立能力观测；不可用/异常不能单独证明权限拒绝或命令实际效果。
        val support = probeSupport(functionId, zone)
        val actualZone = if (m.parameterTypes.size == 2) Int.MIN_VALUE else zone
        val args = if (m.parameterTypes.size == 2) arrayOf<Any?>(functionId, value)
            else arrayOf<Any?>(functionId, zone, value)
        val coerced = coerce(m, args)
        // 能力查询可能阻塞；最终门禁与连接检查仍紧邻真实调用。
        val ticket = beginNativeWrite(generation, functionId, actualZone, value, "$backend:${m.name}") ?: return false
        return runCatching {
            if (!activateNativeWrite(ticket, stillValid = stillValid)) return false
            val r = invokeNativeWrite(ticket) { m.invoke(s, *coerced) }
            val ok = r !is Boolean || r
            Logx.d(
                "HVAC_WRITE 0x${functionId.toString(16)} zone=$actualZone value=$value → " +
                "车控写入返回=${r}(${r?.javaClass?.simpleName ?: "null"}) 接受=$ok " +
                "方法=${m.name} 支持状态=$support"
            )
            ok
        }.getOrElse { e ->
            if (isBinderDead(e)) { reset(); appCtx?.let { c -> runCatching { init(c) } } }
            // 只按实际异常分类，不从异常名推断必须平台签名、root或特定授权方式。
            val cause = e.cause ?: e
            val kind = when {
                cause is SecurityException ||
                    cause.javaClass.name.contains("SecurityException") ->
                    "权限被拒(PERMISSION_DENIED：厂商抛出SecurityException，具体授权条件需设备证据)"
                cause is IllegalArgumentException ||
                    cause.javaClass.name.contains("IllegalArgument") ->
                    "值非法(ILLEGAL_VALUE：value 格式或取值越界，改编码/取值可能有救)"
                cause is UnsupportedOperationException ||
                    cause.javaClass.name.contains("Unsupported") ->
                    "属性不支持(UNSUPPORTED：该 functionId/zone 不接受写入)"
                else -> "其它异常"
            }
            Logx.e(
                "HVAC_WRITE 0x${functionId.toString(16)} zone=$zone value=$value " +
                "失败类型=$kind 支持状态=$support " +
                "异常=${cause.javaClass.simpleName}:${cause.message}"
            )
            false
        }
    }

    private fun rejectWrite(functionId: Int, zone: Int, value: Number, source: String): Boolean {
        HvacCommands.skippedWrite(functionId, zone, value, source)
        return false
    }

    private fun beginNativeWrite(generation: Long, functionId: Int, zone: Int, value: Number, source: String): HvacWriteTicket? {
        if (generation != connectionGeneration || !HvacCommands.canWrite(appCtx, source)) {
            HvacCommands.skippedWrite(functionId, zone, value, "$source:SKIPPED_OR_STALE")
            return null
        }
        val ticket = HvacCommands.beginWrite(appCtx, functionId, zone, value, source)
        // 配置采样及日志也可能耗时；真正进入 native 前不能放松门禁或跨越连接代际。
        if (generation != connectionGeneration || ticket.connection != generation || !HvacCommands.canWrite(appCtx, source)) {
            HvacCommands.abortWrite(ticket, "$source:SKIPPED_OR_STALE")
            return null
        }
        return ticket
    }

    private fun activateNativeWrite(
        ticket: HvacWriteTicket,
        requireCustomizeEnabled: Boolean = false,
        stillValid: (() -> Boolean)? = null
    ): Boolean {
        // 准备日志/配置采样全部结束后复核；激活本身绝不能写日志或触发配置 IO。
        if (ticket.connection != connectionGeneration || !HvacCommands.canWrite(appCtx, "native") ||
            (requireCustomizeEnabled && !customizeWriteEnabled) || stillValid?.invoke() == false ||
            !HvacCommands.activateWrite(ticket)) {
            HvacCommands.abortWrite(ticket, "native:SKIPPED_OR_STALE")
            return false
        }
        return true
    }

    private inline fun invokeNativeWrite(ticket: HvacWriteTicket, acceptNonBoolean: Boolean = true, invoke: () -> Any?): Any? {
        val startedAt = SystemClock.elapsedRealtime()
        val raw = try {
            invoke()
        } catch (t: Throwable) {
            val nativeMs = SystemClock.elapsedRealtime() - startedAt
            HvacCommands.finishWrite(ticket, false, nativeMs, "异常=${unwrapInvocation(t).javaClass.simpleName}")
            throw t
        }
        val nativeMs = SystemClock.elapsedRealtime() - startedAt
        val accepted = if (acceptNonBoolean) raw !is Boolean || raw else raw == null || raw == true
        HvacCommands.finishWrite(ticket, accepted, nativeMs, "返回=$raw type=${raw?.javaClass?.simpleName ?: "null"}")
        return raw
    }

    /** 返回值只表示调用被接受；后台真实回读交统一 Trace，不能另造首次匹配耗时。 */
    fun verifyWrite(functionId: Int, zone: Int, value: Int, delayMs: Long = 400L): Boolean {
        val ctx = appCtx
        if (!HvacCommands.canWrite(ctx, "verifyWrite")) return rejectWrite(functionId, zone, value, "verifyWrite:门禁拒绝")
        ctx ?: return false
        val generation = connectionGeneration
        val accepted = writeInt(functionId, zone, value)
        if (accepted) {
            verifyTimer.postDelayed({
                // 在主线程定时回调、真实读取之前冻结版本；worker 读后不能重新取当前版本。
                val receiver = HvacCommands.confirmations
                val sample = receiver?.capture(functionId, zone)
                HvacCommands.executor.execute {
                    // 排队期间及阻塞回读期间都可能断线、重连或关闭 API。
                    if (connectionGeneration != generation || !isReady ||
                        !Prefs.vehicleApiEnabled(ctx) || Prefs.simulate(ctx)) return@execute
                    val after = readInt(functionId, zone)
                    if (connectionGeneration != generation || !isReady ||
                        !Prefs.vehicleApiEnabled(ctx) || Prefs.simulate(ctx)) return@execute
                    if (after != null) sample?.let { receiver?.accept(it, after) }
                    // 这里只展示该真实回读的证据；首次匹配耗时及相对温度语义由统一 Trace 判定。
                    val result = HvacCommands.describeLastWrite(functionId, zone, 0L)
                    Logx.d(
                        "HVAC_VERIFY 0x${functionId.toString(16)} zone=$zone 调用接受=true " +
                            "写值=$value 回读=$after；统一首次观测记录=$result"
                    )
                }
            }, delayMs)
        }
        return accepted
    }

    /** 优先匹配带 zone 的精确重载，不能误拿同名浮点/回调参数方法。 */
    private fun findCapabilityMethod(target: Any, names: List<String>, values: Boolean = false): Method? {
        for (name in names) {
            val candidates = target.javaClass.methods.filter { m ->
                m.name == name && m.parameterTypes.size in 1..2 && m.parameterTypes.all(::isIntType) &&
                    (!values || m.returnType == IntArray::class.java)
            }
            for (candidate in candidates.sortedByDescending { it.parameterTypes.size }) {
                invocationMethod(candidate)?.let { return it }
            }
        }
        return null
    }

    private fun findSupportMethod(target: Any): Method? = findCapabilityMethod(target,
        if (target === glyCarObj || (target === session && backend == Backend.GLYCAR))
            listOf("getSupportStatus", Sec.m(Sec.M_ISFUNCTIONSUPPORTED))
        else listOf(Sec.m(Sec.M_ISFUNCTIONSUPPORTED), "getSupportStatus"))

    private fun normalizeSupportName(name: String): String = when (
        name.lowercase(java.util.Locale.ROOT).replace("_", "").replace("-", "")
    ) {
        "active", "available" -> "available"
        "notactive", "inactive" -> "notactive"
        "notavailable", "unavailable" -> "notavailable"
        "error" -> "error"
        else -> "未探测"
    }

    private fun normalizeSupport(target: Any, result: Any?): String = when (result) {
        is Enum<*> -> normalizeSupportName(result.name)
        is String -> normalizeSupportName(result)
        is Boolean -> if (result) "available" else "notavailable"
        is Int -> {
            if (glySupportOwner !== target.javaClass) {
                glySupportOwner = target.javaClass
                glySupportCodes = try {
                    // 必须由被调用对象的加载器取得真实常量，不能猜 enum ordinal 或整数编码。
                    val status = Class.forName("com.geely.os.car.GlySupportStatus", false, target.javaClass.classLoader)
                    val codes = mutableMapOf<Int, String>()
                    for (field in status.fields) {
                        val name = normalizeSupportName(field.name)
                        if (java.lang.reflect.Modifier.isStatic(field.modifiers) && isIntType(field.type) && name != "未探测") {
                            val code = (field.get(null) as Number).toInt()
                            val previous = codes[code]
                            codes[code] = if (previous == null || previous == name) name else "未探测"
                        }
                    }
                    codes
                } catch (t: Throwable) {
                    Logx.d("能力整数状态常量不可用：${unwrapInvocation(t).javaClass.simpleName}")
                    emptyMap()
                }
            }
            glySupportCodes[result] ?: "未探测".also {
                Logx.d("能力状态未知：${target.javaClass.name} raw=$result，未推断为 available")
            }
        }
        else -> "未探测"
    }

    private fun querySupport(target: Any, method: Method?, functionId: Int, zone: Int): String {
        method ?: return "未探测"
        return try {
            val result = if (method.parameterTypes.size == 2) method.invoke(target, functionId, zone)
                else method.invoke(target, functionId)
            val status = normalizeSupport(target, result)
            if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_SUPPORT source=${target.javaClass.name}.${method.name} fid=0x${functionId.toString(16)} zone=$zone raw=$result status=$status")
            status
        } catch (t: Throwable) {
            val cause = unwrapInvocation(t)
            Logx.d("能力查询失败：${method.name} fid=0x${functionId.toString(16)} zone=$zone ${cause.javaClass.simpleName}:${cause.message}")
            "error"
        }
    }

    private fun probeSupport(functionId: Int, zone: Int, requireArea: Boolean = false): String {
        val target = session ?: return "未探测"
        if (!supportProbed) {
            supportMethod = findSupportMethod(target)
            supportProbed = true
            if (supportMethod == null) Logx.d("能力查询不可用：${target.javaClass.name} 无精确支持状态方法")
        }
        val method = supportMethod
        if (requireArea && method?.parameterCount != 2) return "未探测"
        return querySupport(target, method, functionId, zone)
    }

    /** 规范化能力状态；available 只表示接口能力，不等于读写权限或车辆效果已验证。 */
    fun supportOf(functionId: Int, zone: Int): String = probeSupport(functionId, zone)

    /** 单参数结果无法区分主副驾，不能用它隐藏任一分区控件。 */
    internal fun isComfortUnavailable(functionId: Int, zone: Int, requireArea: Boolean): Boolean =
        isReady && probeSupport(functionId, zone, requireArea) == "notavailable"

    fun supportedValues(functionId: Int, zone: Int): IntArray? {
        val target = session ?: return null
        if (!valuesProbed) {
            valuesMethod = findCapabilityMethod(target,
                if (backend == Backend.GLYCAR) listOf("getSupportedValueStatus", "getSupportedFunctionValue")
                else listOf("getSupportedFunctionValue", "getSupportedValueStatus"), values = true)
            valuesProbed = true
            if (valuesMethod == null) Logx.d("值域查询不可用：${target.javaClass.name} 无精确整数数组方法")
        }
        val method = valuesMethod ?: return null
        return try {
            val result = if (method.parameterTypes.size == 2) method.invoke(target, functionId, zone)
                else method.invoke(target, functionId)
            result as? IntArray
        } catch (t: Throwable) {
            val cause = unwrapInvocation(t)
            Logx.d("值域查询失败：${method.name} fid=0x${functionId.toString(16)} zone=$zone ${cause.javaClass.simpleName}:${cause.message}")
            null
        }
    }

    /** 默认刷新仍不启用浮点；显式验证调用 readTempProperty，不借此改变旧调用方策略。 */
    val hasFloat: Boolean get() = false

    /** 浮点读取只供显式短时验证；反射句柄存在不代表真车可读取。 */
    val floatChannelName: String get() = "默认整型；显式验证可试 GlyCar/Customize 浮点读取"

    /** 保留原有写入选择与门禁，不因读取验证而启用新的写入路径。 */
    val floatWriteChannelName: String get() = "保留原有温度写入路径与实验开关"

    /** 仅显式验证入口使用；能力未知、无效温度或读取失败均交由调用方回落整数读取。 */
    fun readTempProperty(functionId: Int, zone: Int): Float? {
        val gly = glyCarObj
        if (gly != null && glyFloatRead != null) {
            if (!glyStatusProbed) {
                glyStatusMethod = findSupportMethod(gly)
                glyStatusProbed = true
            }
            val status = querySupport(gly, glyStatusMethod, functionId, zone)
            if (HvacDiagnostics.active && status != "available") Logx.d("HVAC_TEST FLOAT_SKIP source=GlyCar fid=0x${functionId.toString(16)} zone=$zone reason=SUPPORT_STATUS status=$status")
            if (status == "available") {
                val value = readTempViaGlyCar(functionId, zone)
                if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_CHECK source=GlyCar fid=0x${functionId.toString(16)} zone=$zone raw=$value reason=${floatReadReason(value)}")
                value?.takeIf { it.isFinite() && it in 10f..40f }?.let { return it }
            }
        } else if (HvacDiagnostics.active) {
            Logx.d("HVAC_TEST FLOAT_SKIP source=GlyCar fid=0x${functionId.toString(16)} zone=$zone reason=${if (gly == null) "NO_SESSION" else "NO_FLOAT_METHOD"}")
        }
        // GlyCar 已尝试过时不重复调用同一门面；ECarX Customize 仍须独立支持状态通过。
        val target = session ?: run {
            if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_SKIP source=Customize fid=0x${functionId.toString(16)} zone=$zone reason=NO_SESSION")
            return null
        }
        if (target === gly || backend == Backend.GLYCAR) return null
        val status = supportOf(functionId, zone)
        if (status != "available") {
            if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_SKIP source=Customize fid=0x${functionId.toString(16)} zone=$zone reason=SUPPORT_STATUS status=$status")
            return null
        }
        val value = readTempCustomize(functionId, zone)
        if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_CHECK source=Customize fid=0x${functionId.toString(16)} zone=$zone raw=$value reason=${floatReadReason(value)}")
        return value?.takeIf { it.isFinite() && it in 10f..40f }
    }

    /** 仅用于活动验证日志，不改变温度有效域或默认采样行为。 */
    private fun floatReadReason(value: Float?): String = when {
        value == null -> "NO_VALUE"
        !value.isFinite() -> "NON_FINITE"
        value == VALUE_ERROR.toFloat() || value == VALUE_NONE.toFloat() || value == VALUE_UNKNOWN.toFloat() -> "SENTINEL"
        value !in 10f..40f -> "OUT_OF_RANGE"
        else -> "VALID"
    }


    /**
     * Customize浮点写受显式配置及统一门禁控制；当前本车短时验证不能推广到所有车型。
     * 原始返回与实际观测独立记录，不把true当生效，也不把false一律解释为权限失败。
     */
    @Volatile
    var customizeWriteEnabled: Boolean = false

    /** Customize 浮点方法（读/写）缓存 */
    private var customizeWriteMethod: Method? = null
    private var customizeReadMethod: Method? = null

    /**
     * ★★ v1.4.68：**严格照抄系统空调的调用序列**写温度。
     *
     * 权威依据（反编译 + 真车日志双重确认）：
     *
     * 1) 反编译 `oneOS_Dock` 的 `TempSignalClick.addLeftTemp`：
     * ```java
     * float cur = leftTempSignal.getValue();   // getCustomizeFunctionValue(0x10060100, 1)
     * float max = maxTempSignal.getValue();    // 0x10060200
     * if (cur >= max) return;                  // 到顶不写
     * cur += 0.5f;                             // 摄氏步进 0.5
     * leftTempSignal.setValue(cur);            // setCustomizeFunctionValue(0x10060100, 1, cur)
     * ```
     * 2) 真车 logcat（2026-10-20 20:29:29，系统空调 pid 3125 实际调用）：
     * ```
     * isFunctionSupported HVAC_FUNC_TEMP [0x10060100] zone ZONE_ROW_1_LEFT [0x1] status active
     * setCustomizeFunctionValue HVAC_FUNC_TEMP [0x10060100] zone ZONE_ROW_1_LEFT [0x1] value 18.0
     * getFunctionValue HVAC_FUNC_TEMP_MAX [0x10060200] zone ZONE_ALL [0x80000000] value 28.5
     * getFunctionValue HVAC_FUNC_TEMP_MIN [0x10060300] zone ZONE_ALL [0x80000000] value 15.5
     * setCustomizeFunctionValue: ... results:true
     * getCustomizeFunctionValue HVAC_FUNC_TEMP [0x10060100] zone [0x1] results:19.5
     * ```
     *
     * 关键差异（我们之前全错）：
     *  - 方法是 **`setCustomizeFunctionValue`**，不是 `setFunctionValue`
     *  - value 是**目标温度 float（℃）**，不是索引、不是复合编码
     *  - zone：主驾 `0x1`(ZONE_ROW_1_LEFT)、副驾 `0x4`(ZONE_ROW_1_RIGHT)
     *  - 上下限：MAX=`0x10060200` / MIN=`0x10060300`，zone=全局 0x80000000
     *
     * ⚠ 权限现实：同一 (0x10060100, 0x1)，系统空调查到 **active**，
     *   我们查到 **notavailable** → HAL 侧按调用者身份（平台签名/系统 uid）检查。
     *   所以这里**可能直接被拒或触发厂商库崩溃**（1.4.47 时代的 SIGSEGV 疑似同源）。
     *   因此默认关闭，由用户在主界面显式开启。
     */
    fun writeTempCustomize(functionId: Int, zone: Int, value: Float): Boolean {
        val generation = connectionGeneration
        if (!HvacCommands.canWrite(appCtx, "writeTempCustomize")) return rejectWrite(functionId, zone, value, "Customize:门禁拒绝")
        if (!customizeWriteEnabled) return rejectWrite(functionId, zone, value, "Customize:实验写入关闭")
        val s = session ?: return rejectWrite(functionId, zone, value, "Customize:无会话")
        val c = s.javaClass
        val m = (customizeWriteMethod ?: findCustomizeMethod(c, Sec.m(Sec.M_SETCUSTOMIZEFUNCTIONVALUE), 3)
            ?.also { customizeWriteMethod = it })
            ?: run {
                Logx.d("温度 Customize 写：厂商库没有对应的浮点写入方法")
                return rejectWrite(functionId, zone, value, "Customize:无浮点写方法")
            }
        val arg = if (m.parameterTypes[2] == Float::class.javaPrimitiveType) value else value.toDouble()
        val ticket = beginNativeWrite(generation, functionId, zone, value, "$backend:${m.name}") ?: return false
        return try {
            if (!activateNativeWrite(ticket, true)) return false
            val r = invokeNativeWrite(ticket, false) { m.invoke(s, functionId, zone, arg) }
            val ok = r == null || r == true
            Logx.d(
                "温度 Customize 写：0x${functionId.toString(16)} zone=0x${zone.toString(16)} " +
                "value=$value℃ → ${m.name} 返回=$r"
            )
            ok
        } catch (t: Throwable) {
            Logx.e("温度 Customize 写异常：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * ★★ v1.4.68：读温度（float ℃）—— `getCustomizeFunctionValue(int, int)`。
     * 系统空调就是这么读的（20:29:29 日志：results:19.5）。
     * 相比 watcher 回调，这个是主动读，更适合「写完立刻验证」。
     */
    fun readTempCustomize(functionId: Int, zone: Int): Float? {
        val connection = connectionGeneration
        val s = session ?: return null
        val c = s.javaClass
        val m = (customizeReadMethod ?: findCustomizeMethod(c, Sec.m(Sec.M_GETCUSTOMIZEFUNCTIONVALUE), 2)
            ?.also { customizeReadMethod = it }) ?: run {
                if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_SKIP source=Customize fid=0x${functionId.toString(16)} zone=$zone reason=NO_FLOAT_METHOD")
                return null
            }
        return try {
            val observedAt = SystemClock.elapsedRealtime()
            val raw = m.invoke(s, functionId, zone)
            if (raw is Number) HvacCommands.observe(functionId, zone, raw, "READ_TEMP_CUSTOMIZE", observedAt, connection)
            if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_RAW source=Customize fid=0x${functionId.toString(16)} zone=$zone raw=$raw type=${raw?.javaClass?.simpleName ?: "null"}")
            val v = (raw as? Number)?.toFloat()
            if (v != null) Logx.d("温度 Customize 读：0x${functionId.toString(16)} zone=0x${zone.toString(16)} → $v℃")
            v
        } catch (t: Throwable) {
            Logx.e("温度 Customize 读异常：${t.javaClass.simpleName}: ${t.message}")
            if (HvacDiagnostics.active) Logx.d("HVAC_TEST FLOAT_RAW source=Customize fid=0x${functionId.toString(16)} zone=$zone reason=READ_EXCEPTION error=${unwrapInvocation(t).javaClass.simpleName}:${unwrapInvocation(t).message}")
            null
        }
    }

    /** 找 Customize 系列的 (int, int[, float|double]) 方法 */
    private fun findCustomizeMethod(c: Class<*>, name: String, argCount: Int): Method? =
        c.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == argCount &&
                isIntType(m.parameterTypes[0]) && isIntType(m.parameterTypes[1]) &&
                (if (argCount == 2) {
                    m.returnType == Float::class.javaPrimitiveType || m.returnType == java.lang.Float::class.java ||
                        m.returnType == Double::class.javaPrimitiveType || m.returnType == java.lang.Double::class.java
                } else {
                    m.parameterTypes[2] == Float::class.javaPrimitiveType ||
                        m.parameterTypes[2] == Double::class.javaPrimitiveType
                })
        }?.let(::invocationMethod)

    /** 把实参适配成方法声明的类型 */
    private fun coerce(m: Method, args: Array<Any?>): Array<Any?> {
        val types = m.parameterTypes
        val out = arrayOfNulls<Any?>(args.size)
        for (i in args.indices) {
            val v = args[i]
            val t = types.getOrNull(i)
            out[i] = when {
                v == null -> null
                t == Int::class.javaPrimitiveType || t == Integer::class.java -> (v as Number).toInt()
                t == Long::class.javaPrimitiveType -> (v as Number).toLong()
                t == Float::class.javaPrimitiveType -> (v as Number).toFloat()
                t == Double::class.javaPrimitiveType -> (v as Number).toDouble()
                t == Boolean::class.javaPrimitiveType -> (v as Number).toInt() != 0
                else -> v
            }
        }
        return out
    }

    // =====================================================================
    // 事件监听
    // =====================================================================

    @Synchronized
    fun registerWatcher(functions: IntArray, onChange: (functionId: Int, zone: Int, value: Any?) -> Unit): Boolean {
        val requested = functions.distinct().sorted().toIntArray()
        if (requested.isEmpty()) return unregisterWatcher()
        if (watcherRegistered && watcherTarget === session && watcherFunctions.contentEquals(requested)) {
            watcherCallback = onChange
            return true
        }
        // 先确认旧代理已卸载；失败时保留它的目标与卸载句柄，禁止再挂第二个代理。
        if (!unregisterWatcher()) return false
        val iface = watcherIface ?: return false
        val method = registerMethod ?: return false
        val target = session ?: return false
        val detach = unregisterMethod ?: return false
        val generation = ++watcherGeneration
        val handler = InvocationHandler { proxy, callbackMethod, args ->
            when {
                callbackMethod.declaringClass == Any::class.java -> when (callbackMethod.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> (args?.firstOrNull() === proxy)
                    "toString" -> "VehicleWatcherProxy@${Integer.toHexString(System.identityHashCode(proxy))}"
                    else -> primitiveDefault(callbackMethod.returnType)
                }
                else -> {
                    val name = callbackMethod.name
                    if (watcherRegistered && watcherGeneration == generation && watcherProxy === proxy &&
                        args != null && args.size == 3 &&
                        (name == "onFunctionValueChanged" || name == "onCustomizeFunctionValueChanged" ||
                            (watcherCallbackFirst && name == "onChangeEvent"))) {
                        val fid = (args[0] as? Number)?.toInt()
                        val zone = (args[1] as? Number)?.toInt()
                        if (fid != null && zone != null && requested.binarySearch(fid) >= 0) {
                            try {
                                watcherCallback?.invoke(fid, zone, args[2])
                            } catch (t: Throwable) {
                                Logx.e("事件监听业务回调异常", t)
                            }
                        }
                    }
                    primitiveDefault(callbackMethod.returnType)
                }
            }
        }
        return try {
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
            watcherProxy = proxy
            watcherTarget = target
            watcherDetachMethod = detach
            watcherFunctions = requested
            watcherCallback = onChange
            val accepted = if (watcherCallbackFirst) {
                invokeAccepted(method, target, proxy, requested)
            } else if (registerArrayMode) {
                invokeAccepted(method, target, requested, proxy)
            } else {
                requested.all { invokeAccepted(method, target, it, proxy) }
            }
            if (!accepted) {
                Logx.w("事件监听注册被拒：${method.name}，撤销可能已接收的部分属性")
                unregisterWatcher()
                false
            } else {
                watcherRegistered = true
                Logx.d("事件监听接口已接受：${method.name} func=${requested.joinToString { "0x" + it.toString(16) }}；收到有效回调仍需单独验证")
                true
            }
        } catch (t: Throwable) {
            Logx.e("事件监听注册异常：${unwrapInvocation(t).javaClass.simpleName}，尝试撤销部分注册", t)
            unregisterWatcher()
            false
        }
    }

    /** 未识别的回调仍须返回正确的装箱值，不能让 primitive 返回类型因 null 崩溃。 */
    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    @Synchronized
    fun unregisterWatcher(): Boolean {
        watcherRegistered = false
        watcherCallback = null
        ++watcherGeneration
        val proxy = watcherProxy ?: return true
        val target = watcherTarget ?: return false
        val detach = watcherDetachMethod ?: return false
        val removed = try {
            invokeAccepted(detach, target, proxy).also {
                if (!it) Logx.w("事件监听卸载被拒：${detach.name}，保留代理并拒绝叠加注册")
            }
        } catch (t: Throwable) {
            val cause = unwrapInvocation(t)
            // 明确的 DeadObjectException 才能证明旧远端已不存在；普通 RemoteException 不能据此清空。
            if (cause is android.os.DeadObjectException) {
                true
            } else {
                Logx.e("事件监听卸载异常：保留代理并拒绝叠加注册", t)
                false
            }
        }
        if (removed) {
            watcherProxy = null
            watcherTarget = null
            watcherDetachMethod = null
            watcherFunctions = intArrayOf()
        }
        return removed
    }

    // =====================================================================

    /** 把探测结果打进日志，方便在车机上确认通道是否真的通了 */
    private fun dumpProbe(ctx: Context) {
        Logx.d("后端=$backend  读方法=${readMethod?.let { "${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})" } ?: "-"}")
        Logx.d("后端=$backend  写方法=${writeMethod?.let { "${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})" } ?: "无（只读）"}")
        Logx.d("后端=$backend  温度通道=$floatChannelName")
        Logx.d("后端=$backend  监听方法=${registerMethod?.name ?: "-"}${if (registerArrayMode) "(数组)" else "(逐函数)"}")
    }

    /** 调试页用：列出会话对象的全部可用方法 */
    fun dumpMethods(): List<String> {
        val s = session ?: return emptyList()
        return s.javaClass.methods
            .filter { it.declaringClass != Any::class.java }
            .map { "${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})" }
            .distinct()
            .sorted()
    }

    private fun unwrapInvocation(t: Throwable): Throwable =
        if (t is java.lang.reflect.InvocationTargetException && t.targetException != null)
            unwrapInvocation(t.targetException) else t

    /** binder 异常包在反射调用异常内时也需要恢复会话。 */
    private fun isBinderDead(t: Throwable): Boolean = unwrapInvocation(t).let {
        it is android.os.DeadObjectException || it is android.os.RemoteException
    }

    @Synchronized
    fun reset() {
        ++connectionGeneration
        HvacChannelEvidence.connection(connectionGeneration, Backend.NONE.label, ready = false)
        unregisterWatcher()
        session = null
        carObj = null
        backend = Backend.NONE
        readMethod = null
        writeMethod = null
        readMethodNoArea = null
        writeMethodNoArea = null
        registerMethod = null
        unregisterMethod = null
        watcherIface = null
        registerArrayMode = false
        watcherCallbackFirst = false
        glyCarObj = null
        glyFloatRead = null
        glyFloatWrite = null
        glyIntRead = null
        glyIntWrite = null
        customizeReadMethod = null
        customizeWriteMethod = null
        supportMethod = null
        glyStatusMethod = null
        glyStatusProbed = false
        supportProbed = false
        valuesMethod = null
        valuesProbed = false
        glySupportOwner = null
        glySupportCodes = emptyMap()
    }
}
