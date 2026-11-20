package com.car.dockbar.vehicle

import android.content.Context
import android.os.SystemClock
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.config.VehicleChannels.AC_MAX_VALIDATION_KEY
import com.car.dockbar.util.Logx

internal enum class SeatWriteValidationMode(val label: String) {
    ECARX_DIRECT("ECarX 直接目标写入"),
    ECARX_COMBINED("ECarX 先关闭相反模式"),
    GLYCAR_DIRECT("GlyCar 属性直接写入")
}

internal enum class HvacValidationMode(
    val label: String,
    val keys: List<String>,
    val seatWriteMode: SeatWriteValidationMode? = null,
    val comfortReadOnly: Boolean = false
) {
    FAN("风量：手动 / 自动风量", listOf("fan_manual", "fan_auto")),
    CLIMATE("常用空调：风量 + A/C / 循环 / 风向 / 双温", FAN.keys + listOf("ac", "recirc", "air_direction", "temp_dual")),
    TEMPERATURE("含温度：额外调用浮点 Customize/native", CLIMATE.keys + listOf("temp_driver", "temp_passenger")),
    AC_MAX("急速降温：原厂 MAX A/C", listOf(AC_MAX_VALIDATION_KEY)),
    SEAT_ECARX_DIRECT(
        "座椅：${SeatWriteValidationMode.ECARX_DIRECT.label}",
        listOf("seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger"),
        SeatWriteValidationMode.ECARX_DIRECT
    ),
    SEAT_ECARX_COMBINED(
        "座椅：${SeatWriteValidationMode.ECARX_COMBINED.label}",
        listOf("seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger"),
        SeatWriteValidationMode.ECARX_COMBINED
    ),
    SEAT_GLYCAR_DIRECT(
        "座椅：${SeatWriteValidationMode.GLYCAR_DIRECT.label}",
        listOf("seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger"),
        SeatWriteValidationMode.GLYCAR_DIRECT
    ),
    PASSENGER_SEAT_VENT_EVENTS("只读订阅：副驾座椅通风", listOf("seat_vent_passenger"), comfortReadOnly = true),
    FRONT_DEFROST_EVENTS("只读订阅：前挡除霜", listOf("defrost_front"), comfortReadOnly = true),
    REAR_DEFROST_EVENTS("只读订阅：后挡除霜", listOf("defrost_rear"), comfortReadOnly = true),
    STEERING_HEAT_EVENTS("只读订阅：方向盘加热", listOf("steering_heat"), comfortReadOnly = true)
}

/** 仅保存本次和最近完成的验证；锁内不调用厂商接口、日志文件或网络。 */
internal object HvacDiagnostics {
    private const val DURATION_MS = 120_000L
    private val lock = Any()
    private data class Channel(
        val key: String, val fid: Int, val zone: Int,
        var events: Int = 0, var validEvents: Int = 0, var changedEvents: Int = 0,
        var reads: Int = 0, var validReads: Int = 0,
        var last: String = "未观测", var capability: String = "未查询",
        var lastReadLog: String? = null, var lastReadLogAt: Long = 0,
        var lastAcMaxRead: Int? = null, var lastAcMaxEvent: Int? = null
    )
    private data class Session(
        val mode: HvacValidationMode, val start: Long, val channels: List<Channel>,
        val registrations: MutableList<String> = mutableListOf(), var end: Long? = null,
        var reason: String = "进行中", var lastAcMaxWrite: String? = null
    )
    private var session: Session? = null
    private var completedReport: String? = null
    val active: Boolean get() = synchronized(lock) { session != null }

    fun begin(ctx: Context, mode: HvacValidationMode) {
        // 通道配置读取在锁外完成，不保留 Context。
        val channels = mode.keys.map {
            Channel(it, VehicleChannels.observationFuncOf(ctx, it), VehicleChannels.observationZoneOf(ctx, it))
        }
        finish("被新验证替换")
        val line = synchronized(lock) {
            val next = Session(mode, SystemClock.elapsedRealtime(), channels)
            session = next
            "START mode=$mode durationMs=$DURATION_MS channels=${channels.joinToString { identity(it) }}"
        }
        emit(line)
    }

    fun registration(backend: String, keys: List<String>, accepted: Boolean, detail: String) {
        val line = synchronized(lock) {
            val current = session ?: return
            "REGISTER backend=$backend keys=$keys accepted=$accepted detail=$detail（接受注册不代表收到事件）"
                .also { current.registrations.add(it) }
        }
        emit(line)
    }

    fun capability(key: String, status: String, values: IntArray?) {
        val line = synchronized(lock) {
            val ch = session?.channels?.find { it.key == key } ?: return
            ch.capability = "$status values=${values?.contentToString() ?: "UNKNOWN"}"
            "CAP ${identity(ch)} ${ch.capability}"
        }
        emit(line)
    }

    fun read(key: String, raw: Any?, valid: Boolean, source: String, observedAtMs: Long) {
        val lines = synchronized(lock) {
            val current = session ?: return
            if (observedAtMs < current.start) return
            val ch = current.channels.find { it.key == key } ?: return
            ch.reads++
            if (valid) ch.validReads++
            if (key == AC_MAX_VALIDATION_KEY && valid) ch.lastAcMaxRead = raw as? Int
            // source 由读取端提供实际命中的 zone；缺失时不能冒充配置位置或匹配命令。
            val actualZone = source.substringAfter("zone=", "").substringBefore(' ').toIntOrNull()
            val detail = "READ ${identity(ch)} actualZone=${actualZone ?: "UNKNOWN"} raw=$raw valid=$valid source=$source observedAtMs=$observedAtMs"
            ch.last = detail
            val result = mutableListOf<String>()
            val now = SystemClock.elapsedRealtime()
            val signature = "$raw/$valid/$source"
            if (signature != ch.lastReadLog || now - ch.lastReadLogAt >= 2_000L) {
                result.add(detail)
                ch.lastReadLog = signature
                ch.lastReadLogAt = now
            }
            result
        }
        lines.forEach(::emit)
    }

    fun event(key: String, raw: Any?, valid: Boolean, changed: Boolean, zone: Int, delayMs: Long) {
        val line = synchronized(lock) {
            val current = session ?: return
            val observedAt = SystemClock.elapsedRealtime() - delayMs.coerceAtLeast(0L)
            if (observedAt < current.start) return
            val ch = current.channels.find { it.key == key } ?: return
            ch.events++
            val applicable = valid && zone == ch.zone
            val stateChanged = if (key == AC_MAX_VALIDATION_KEY) {
                val previous = ch.lastAcMaxEvent ?: ch.lastAcMaxRead
                if (applicable) ch.lastAcMaxEvent = raw as? Int
                applicable && previous != null && previous != raw
            } else changed
            if (applicable) {
                ch.validEvents++
                if (stateChanged) ch.changedEvents++
            }
            val detail = "EVENT ${identity(ch)} actualZone=$zone raw=$raw valid=$valid applicable=$applicable changed=$stateChanged delayMs=$delayMs"
            ch.last = detail
            detail
        }
        emit(line)
    }

    fun acMaxWrite(detail: String, matchesEpoch: () -> Boolean) {
        val line = "AC_MAX_WRITE $detail"
        synchronized(lock) {
            val current = session
            if (current?.mode == HvacValidationMode.AC_MAX && matchesEpoch()) current.lastAcMaxWrite = line
        }
        emit(line)
    }

    fun finish(reason: String) {
        val line = synchronized(lock) {
            val current = session ?: return
            current.end = SystemClock.elapsedRealtime()
            current.reason = reason
            completedReport = render(current)
            session = null
            "END mode=${current.mode} reason=$reason elapsedMs=${current.end!! - current.start}\n$completedReport"
        }
        emit(line)
    }

    /** 停止后的厂商恢复结果只能更新已完成报告，不污染新会话。 */
    fun restoration(accepted: Boolean, detail: String) {
        val line = synchronized(lock) {
            if (session != null) return
            val previous = completedReport ?: return
            val warning = if (accepted) "旧订阅卸载/基线恢复已确认"
                else "旧订阅卸载/基线恢复未确认，不能当已安全撤销，查看日志并重启应用后不要重复开启故障通道"
            val result = "RESTORE accepted=$accepted detail=${detail.replace('\n', ' ').replace('\r', ' ')}；$warning"
            completedReport = previous.substringBefore("\nHVAC_TEST RESTORE ") + "\nHVAC_TEST $result"
            result
        }
        emit(line)
    }

    fun report(): String = synchronized(lock) {
        session?.let(::render) ?: completedReport ?: "HVAC_TEST 尚未开始验证。"
    }

    private fun render(current: Session): String = buildString {
        val elapsed = (current.end ?: SystemClock.elapsedRealtime()) - current.start
        appendLine("HVAC_TEST mode=${current.mode} 状态=${current.reason} 已运行=${elapsed / 1000}s 剩余=${((DURATION_MS - elapsed).coerceAtLeast(0L)) / 1000}s")
        appendLine("注册结果：")
        if (current.registrations.isEmpty()) appendLine("UNKNOWN（尚无注册结果）")
        current.registrations.forEach { appendLine(it) }
        current.channels.forEach { ch ->
            appendLine(identity(ch))
            appendLine("  能力=${ch.capability}")
            appendLine("  EVENT收到=${ch.events} 有效且zone匹配=${ch.validEvents} 状态变化=${ch.changedEvents}；业务采样READ总数=${ch.reads} 有效=${ch.validReads}")
            if (ch.key == AC_MAX_VALIDATION_KEY) {
                appendLine("  最近有效状态：读回=${acMaxText(ch.lastAcMaxRead)}；事件=${acMaxText(ch.lastAcMaxEvent)}")
                appendLine("  写入记录：${current.lastAcMaxWrite ?: "未收到原厂 MAX A/C 试写请求"}")
            }
            appendLine("  最后观测=${ch.last}")
            appendLine("  最近实际调用=${HvacCommands.describeLastWrite(ch.fid, ch.zone, current.start)}")
        }
        val operations = HvacCommands.recentOperations(current.start)
        if (operations.isNotEmpty()) appendLine("近期操作（仅排队/执行状态，不代表实际写入）：\n$operations")
        appendLine("UNKNOWN/无事件/无读回不代表0或关闭，也不是成功或权限拒绝。READ实际zone以source/后端日志为准。")
        appendLine("上述READ仅统计业务采样；原生写后校验另见最近实际调用与HVAC_VERIFY日志，不重复计入采样次数。")
        append("命令接受、注册接受、观测匹配均不证明真车物理效果，请人工观察；温度 Customize/native 接口可能导致进程闪退。")
    }

    private fun acMaxText(value: Int?): String = when (value) {
        0 -> "关闭(0)"
        1 -> "开启(1)"
        else -> "未知"
    }

    private fun identity(ch: Channel) = "key=${ch.key} fid=0x${ch.fid.toUInt().toString(16)} configuredZone=${ch.zone}"
    private fun emit(line: String) { Logx.w("HVAC_TEST $line") }
}
