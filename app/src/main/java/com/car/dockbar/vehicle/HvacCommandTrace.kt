package com.car.dockbar.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/** 一次真实厂商调用的身份；排队操作与实际调用不是同一层结果。 */
internal data class HvacWriteTicket(
    val writeId: Long,
    val commandId: Long?,
    val connection: Long,
    @Volatile var startedAtMs: Long = -1L,
    @Volatile var active: Boolean = false,
    val floating: Boolean = false
)

internal class HvacOperationTicket(
    val commandId: Long,
    val label: String,
    val submittedAtMs: Long,
    val connection: Long,
    var queuedAtMs: Long? = null,
    var sentAtMs: Long? = null,
    var state: String = "SUBMITTED",
    /** 仅由所属 worker 操作递增，表示真实浮点调用尝试，不表示接受或生效。 */
    var floatWrites: Int = 0,
    /** 仅由所属 worker 操作递增，包含整型和浮点 native 尝试，不表示接受或生效。 */
    var nativeWrites: Int = 0
)

/** 固定容量保存近期操作与真实写入；正常读取只扫描小数组，不查询配置或建立临时集合。 */
internal object HvacCommandTrace {
    private const val CAPACITY = 32
    private const val CONFIRM_MS = 3_500L
    private const val DIFFERENT_MS = 1_500L
    private val lock = Any()
    private val operations = arrayOfNulls<HvacOperationTicket>(CAPACITY)
    private val writes = arrayOfNulls<Write>(CAPACITY)
    private var operationIndex = 0
    private var writeIndex = 0
    private var nextCommandId = 0L
    private var nextWriteId = 0L
    private val timer = Handler(Looper.getMainLooper())
    private var timerAtMs: Long? = null

    private class Write(
        val ticket: HvacWriteTicket,
        val fid: Int,
        val zone: Int,
        val value: Number,
        val source: String,
        val key: String?,
        val queueMs: Long?,
        val tempBase: Float,
        val tempStep: Float,
        val tempIndexBase: Int,
        var state: String,
        var closed: Boolean,
        var accepted: Boolean? = null,
        var nativeMs: Long? = null,
        var firstMatchMs: Long? = null,
        var observation: String = "无写入后的完整有效观测",
        var detail: String = "尚未调用厂商接口"
    )

    private val timeout = Runnable {
        val lines = synchronized(lock) {
            timerAtMs = null
            val result = mutableListOf<String>()
            val now = SystemClock.elapsedRealtime()
            val connection = VehicleBridge.connectionGeneration
            for (write in writes) {
                write ?: continue
                if (!write.ticket.active) continue
                if (write.ticket.connection != connection && write.state != "STALE") {
                    write.state = "STALE"
                    write.closed = true
                    result.add(describe(write))
                } else if (!write.closed && now - write.ticket.startedAtMs >= CONFIRM_MS) {
                    write.closed = true
                    if (write.state != "OBSERVED_DIFFERENT") write.state = "UNKNOWN"
                    result.add(describe(write))
                }
            }
            scheduleLocked(now)
            result
        }
        lines.forEach(::emit)
    }

    fun operation(label: String, connection: Long): HvacOperationTicket = synchronized(lock) {
        HvacOperationTicket(++nextCommandId, label, SystemClock.elapsedRealtime(), connection).also {
            operations[operationIndex] = it
            operationIndex = (operationIndex + 1) % CAPACITY
        }
    }

    fun operationState(operation: HvacOperationTicket, state: String, detail: String = "") {
        val line = synchronized(lock) {
            if (operation.queuedAtMs != null && operation.sentAtMs == null &&
                (state == "SENT" || state == "SKIPPED" || state == "STALE")) {
                operation.sentAtMs = SystemClock.elapsedRealtime()
            }
            operation.state = state
            operationLine(operation) + if (detail.isEmpty()) "" else " detail=$detail"
        }
        emit(line)
        // QUEUED 日志的 IO 不计入队列等待；返回后调用方立即投递共享执行器。
        if (state == "QUEUED") synchronized(lock) { operation.queuedAtMs = SystemClock.elapsedRealtime() }
    }

    fun beginWrite(
        ctx: Context?, functionId: Int, zone: Int, value: Number, source: String,
        operation: HvacOperationTicket?
    ): HvacWriteTicket {
        // 每次真实写入只解析一次配置；观测热路径只使用这里冻结的编码规则。
        var key = if (zone == VehicleChannels.ZONE_GLOBAL) when (functionId) {
            VehicleChannels.FUNC_AC_MAX -> VehicleChannels.AC_MAX_KEY
            VehicleChannels.FUNC_POWER -> VehicleChannels.POWER_VALIDATION_KEY
            else -> null
        } else null
        if (key == null && ctx != null) {
            for (channel in VehicleChannels.ALL) {
                if (VehicleChannels.funcOf(ctx, channel.key) == functionId &&
                    VehicleChannels.zoneOf(ctx, channel.key) == zone) {
                    key = channel.key
                    break
                }
            }
        }
        val tempBase = ctx?.let(Prefs::tempBase) ?: 20f
        val tempStep = ctx?.let(Prefs::tempStep) ?: 0.5f
        val tempIndexBase = ctx?.let(Prefs::tempIndexBase) ?: 100
        lateinit var ticket: HvacWriteTicket
        val line = synchronized(lock) {
            ticket = HvacWriteTicket(++nextWriteId, operation?.commandId, VehicleBridge.connectionGeneration,
                floating = isFloat(value))
            val relative = (key == "temp_driver" || key == "temp_passenger") &&
                !isFloat(value) && (value.toInt() and 0xff) in 1..2
            val write = Write(ticket, functionId, zone, value, source, key,
                operation?.let { queueMs(it) }, tempBase, tempStep, tempIndexBase,
                "PREPARING", key == null || relative)
            if (relative) write.observation = "相对温度步进需要正常化目标证据，不能直接确认"
            else if (key == null) write.observation = "未定位到同属性同区域的已知通道"
            writes[writeIndex] = write
            writeIndex = (writeIndex + 1) % CAPACITY
            describe(write)
        }
        emit("NATIVE_PREPARING $line")
        return ticket
    }

    /** 最终门禁之后紧邻 native 调用激活；锁内不查配置、不记录日志、不执行厂商 IO。 */
    fun activateWrite(ticket: HvacWriteTicket): Boolean = synchronized(lock) {
        val write = find(ticket.writeId) ?: return@synchronized false
        if (ticket.active || ticket.connection != VehicleBridge.connectionGeneration) return@synchronized false
        // 只有新调用真正进入 native 时才替换旧实际调用的待确认窗口。
        for (previous in writes) {
            if (previous != null && previous.ticket.active && !previous.closed &&
                previous.fid == write.fid && previous.zone == write.zone) {
                previous.closed = true
                previous.state = "SUPERSEDED"
            }
        }
        ticket.startedAtMs = SystemClock.elapsedRealtime()
        ticket.active = true
        write.state = "UNKNOWN"
        write.detail = "调用尚未返回"
        scheduleLocked(ticket.startedAtMs)
        true
    }

    fun finishWrite(ticket: HvacWriteTicket, accepted: Boolean, nativeMs: Long, detail: String) {
        val line = synchronized(lock) {
            val write = find(ticket.writeId) ?: return
            if (!ticket.active) return
            write.accepted = accepted
            write.nativeMs = nativeMs.coerceAtLeast(0L)
            write.detail = detail
            if (ticket.connection != VehicleBridge.connectionGeneration) {
                write.state = "STALE"
                write.closed = true
            }
            describe(write)
        }
        emit("NATIVE_RETURN $line")
    }

    fun abortWrite(ticket: HvacWriteTicket, source: String) {
        val line = synchronized(lock) {
            for (index in writes.indices) {
                val write = writes[index] ?: continue
                if (write.ticket.writeId != ticket.writeId) continue
                if (ticket.active) return@synchronized null
                writes[index] = null
                return@synchronized "SKIPPED commandId=${ticket.commandId ?: "RAW"} wireId=NONE cancelledPendingWireId=${ticket.writeId} source=$source（未调用厂商接口）"
            }
            null
        }
        if (line != null) emit(line)
    }

    fun skippedWrite(functionId: Int, zone: Int, value: Number, source: String, operation: HvacOperationTicket?) {
        // 没有实际调用就没有 wireId，也不能建立会被未来样本匹配的目标。
        emit("SKIPPED commandId=${operation?.commandId ?: "RAW"} wireId=NONE fid=0x${functionId.toUInt().toString(16)} zone=$zone raw=$value source=$source nativeMs=UNKNOWN")
    }

    fun observe(functionId: Int, zone: Int, value: Number, source: String, observedAtMs: Long, connection: Long) {
        val line = synchronized(lock) {
            if (connection != VehicleBridge.connectionGeneration) return
            for (write in writes) {
                write ?: continue
                if (!write.ticket.active || write.closed || write.fid != functionId || write.zone != zone ||
                    write.ticket.connection != connection || observedAtMs < write.ticket.startedAtMs ||
                    observedAtMs - write.ticket.startedAtMs >= CONFIRM_MS ||
                    isFloat(write.value) != isFloat(value) || !validSample(write, value)) continue
                val elapsed = observedAtMs - write.ticket.startedAtMs
                val match = if (isFloat(value)) HvacChannels.temperatureMatches(value.toFloat(), write.value.toFloat())
                    else (value.toInt() and 0xff) == (write.value.toInt() and 0xff)
                if (match) {
                    write.firstMatchMs = elapsed
                    write.state = "OBSERVED_MATCH"
                    write.closed = true
                } else if (elapsed >= DIFFERENT_MS && write.state != "OBSERVED_DIFFERENT") {
                    write.state = "OBSERVED_DIFFERENT"
                } else continue
                write.observation = "raw=$value source=$source afterMs=$elapsed"
                return@synchronized describe(write)
            }
            null
        }
        if (line != null) emit(line)
    }

    fun describeLastWrite(functionId: Int, zone: Int, sinceMs: Long): String = synchronized(lock) {
        var last: Write? = null
        for (write in writes) {
            if (write != null && write.ticket.active && write.fid == functionId && write.zone == zone &&
                write.ticket.startedAtMs >= sinceMs && (last == null || write.ticket.writeId > last.ticket.writeId)) last = write
        }
        val write = last ?: return@synchronized "无本次验证期间的真实调用"
        if (write.ticket.connection != VehicleBridge.connectionGeneration) {
            write.state = "STALE"
            write.closed = true
        }
        describe(write)
    }

    fun recentOperations(sinceMs: Long): String = synchronized(lock) {
        buildString {
            for (offset in 0 until CAPACITY) {
                val operation = operations[(operationIndex + offset) % CAPACITY] ?: continue
                if (operation.submittedAtMs >= sinceMs) appendLine(operationLine(operation))
            }
        }.trimEnd()
    }

    private fun validSample(write: Write, value: Number): Boolean {
        val key = write.key ?: return false
        if (isFloat(value)) return (key == "temp_driver" || key == "temp_passenger") &&
            HvacChannels.normalizeTemperature(value.toFloat()) != null &&
            HvacChannels.normalizeTemperature(write.value.toFloat()) != null
        val raw = value.toInt()
        val expected = write.value.toInt()
        if (HvacChannels.isSentinel(raw) || HvacChannels.isSentinel(expected)) return false
        if (key == "recirc") {
            val inside = (write.fid and 0xffffff00.toInt()) or 1
            return (raw == 0 || raw == inside) && (expected == 0 || expected == inside)
        }
        if (key == "auto_master" || key == "temp_dual") {
            return (raw and 0xff) in 0..1 && (expected and 0xff) in 0..1
        }
        if (key == VehicleChannels.AC_MAX_KEY || key == VehicleChannels.POWER_VALIDATION_KEY) {
            return HvacChannels.validRaw(key, raw) && HvacChannels.validRaw(key, expected)
        }
        return HvacChannels.validRaw(key, raw, write.tempBase, write.tempStep, write.tempIndexBase) &&
            HvacChannels.validRaw(key, expected, write.tempBase, write.tempStep, write.tempIndexBase) &&
            HvacChannels.observationProperties(key, raw) != 0 && HvacChannels.observationProperties(key, expected) != 0
    }

    private fun isFloat(value: Number): Boolean = value is Float || value is Double

    private fun find(writeId: Long): Write? {
        for (write in writes) if (write?.ticket?.writeId == writeId) return write
        return null
    }

    private fun scheduleLocked(now: Long) {
        var nearest: Long? = null
        for (write in writes) {
            if (write != null && write.ticket.active && !write.closed) {
                val due = write.ticket.startedAtMs + CONFIRM_MS
                if (nearest == null || due < nearest) nearest = due
            }
        }
        if (nearest == timerAtMs) return
        timer.removeCallbacks(timeout)
        timerAtMs = nearest
        if (nearest != null) timer.postDelayed(timeout, (nearest - now).coerceAtLeast(0L))
    }

    private fun queueMs(operation: HvacOperationTicket): Long? {
        val queuedAt = operation.queuedAtMs ?: return null
        val sentAt = operation.sentAtMs ?: return null
        return sentAt - queuedAt
    }

    private fun operationLine(operation: HvacOperationTicket): String =
        "${operation.state} commandId=${operation.commandId} label=${operation.label} connection=${operation.connection} queueMs=${queueMs(operation) ?: "UNKNOWN"}"

    private fun describe(write: Write): String =
        "commandId=${write.ticket.commandId ?: "RAW"} wireId=${write.ticket.writeId} fid=0x${write.fid.toUInt().toString(16)} zone=${write.zone} raw=${write.value} source=${write.source} connection=${write.ticket.connection} " +
            "queueMs=${write.queueMs ?: "UNKNOWN"} nativeMs=${write.nativeMs ?: "UNKNOWN"} accepted=${write.accepted ?: "PENDING"} ${write.state} firstMatchMs=${write.firstMatchMs ?: "UNKNOWN"} ${write.observation} detail=${write.detail}（均非物理效果证明）"

    private fun emit(line: String) { Logx.d("HVAC_TRACE $line") }
}
