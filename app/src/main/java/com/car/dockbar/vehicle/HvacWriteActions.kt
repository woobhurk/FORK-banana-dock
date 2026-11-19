package com.car.dockbar.vehicle

import android.content.Context
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/** 只执行共享车控后台队列上的协议动作；返回值仅表示调用接受，不表示真实车辆效果。 */
internal object HvacWriteActions {
    data class SeatWriteStep(val key: String, val value: Int)

    internal fun seatWritePlan(
        driver: Boolean,
        heating: Boolean,
        level: Int,
        mode: SeatWriteValidationMode
    ): List<SeatWriteStep> {
        val heatKey = if (driver) "seat_heat" else "seat_heat_passenger"
        val ventKey = if (driver) "seat_vent" else "seat_vent_passenger"
        if (level == 0) return listOf(SeatWriteStep(heatKey, 0), SeatWriteStep(ventKey, 0))
        val active = if (heating) heatKey else ventKey
        val inactive = if (heating) ventKey else heatKey
        return if (mode == SeatWriteValidationMode.ECARX_COMBINED) {
            listOf(SeatWriteStep(inactive, 0), SeatWriteStep(active, level))
        } else {
            listOf(SeatWriteStep(active, level))
        }
    }
    fun writeChannel(ctx: Context, key: String, value: Int): Boolean {
        if (!HvacCommands.canWrite(ctx, key)) return false
        val function = VehicleChannels.funcOf(ctx, key)
        if (function <= 0) {
            Logx.w("写 $key 未执行：通道未配置")
            return false
        }
        val zone = VehicleChannels.zoneOf(ctx, key)
        val encoded = HvacChannels.encodeInt(function, key, value)
        if (encoded == null) {
            Logx.w("写 $key 未执行：目标越界 value=$value")
            return false
        }
        if (VehicleBridge.verifyWrite(function, zone, encoded)) return true
        // 已验证的 HVAC 编码不扩展候选；旧舒适/整数路径只保留同区、同目标的裸值兼容。
        val verified = key == "fan_manual" || key == "fan_auto" || key == "ac" ||
            key == "recirc" || key == "air_direction"
        return !verified && encoded != value && VehicleBridge.verifyWrite(function, zone, value)
    }

    /** 返回未接受的属性位；关闭时仍独立处理同侧的两条通道。 */

    fun writeSeatComfort(
        ctx: Context,
        driver: Boolean,
        heating: Boolean,
        level: Int,
        mode: SeatWriteValidationMode
    ): Int {
        val steps = seatWritePlan(driver, heating, level, mode)
        var rejected = 0
        for ((index, step) in steps.withIndex()) {
            if (!writeSeatChannel(ctx, step.key, step.value, mode)) {
                rejected = rejected or checkNotNull(HvacProperty.fromKey(step.key)).mask
                if (level > 0 && mode == SeatWriteValidationMode.ECARX_COMBINED && index == 0) {
                    Logx.w("座椅组合操作停止：${step.key} 关闭调用未接受，未请求后续档位")
                    break
                }
            }
        }
        return rejected
    }

    private fun writeSeatChannel(ctx: Context, key: String, value: Int, mode: SeatWriteValidationMode): Boolean {
        if (!HvacCommands.canWrite(ctx, key)) return false
        val function = VehicleChannels.funcOf(ctx, key)
        val zone = VehicleChannels.zoneOf(ctx, key)
        val encoded = HvacChannels.encodeInt(function, key, value) ?: return false
        val accepted = when {
            mode == SeatWriteValidationMode.GLYCAR_DIRECT -> VehicleBridge.verifyIntViaGlyCar(function, zone, encoded)
            key == "seat_vent_passenger" && VehicleBridge.supportOf(function, zone) == "notavailable" -> {
                HvacCommands.skippedWrite(function, zone, encoded, "$mode:副驾通风不可用")
                false
            }
            else -> VehicleBridge.verifyWrite(function, zone, encoded)
        }
        Logx.d("HVAC_TEST SEAT_WRITE mode=$mode key=$key fid=0x${function.toString(16)} zone=$zone raw=$encoded value=$value accepted=$accepted")
        return accepted
    }

    fun writeSwitch(
        ctx: Context, key: String, func: Int, zone: Int, on: Boolean, label: String,
        bareFirst: Boolean = false
    ): Boolean {
        if (!HvacCommands.canWrite(ctx, key)) return false
        val value = if (on) 1 else 0
        val composite = HvacChannels.encodeInt(func, key, value)
        if (composite == null) {
            Logx.w("写 $label 未执行：通道或目标未配置")
            return false
        }
        val support = VehicleBridge.supportOf(func, zone)
        val first = if (bareFirst) value else composite
        val fallback = if (bareFirst) composite else value
        val accepted = VehicleBridge.verifyWrite(func, zone, first) ||
            (fallback != first && VehicleBridge.verifyWrite(func, zone, fallback))
        Logx.d(
            "写 $label target=${if (on) "开" else "关"} " +
                "func=0x${func.toString(16)} zone=0x${zone.toString(16)} " +
                "顺序=${if (bareFirst) "裸值优先" else "复合优先"} 支持状态=$support → " +
                if (accepted) "调用已接受，生效等待真实观测" else "调用未接受；不能据此推断车辆状态或权限原因"
        )
        return accepted
    }

    fun writeTemperature(ctx: Context, driver: Boolean, target: Float): Boolean {
        val key = if (driver) "temp_driver" else "temp_passenger"
        if (!HvacCommands.canWrite(ctx, key)) return false
        if (!target.isFinite() || target < VehicleController.TEMP_MIN_C || target > VehicleController.TEMP_MAX_C) {
            Logx.w("写 $key 未执行：目标温度越界 target=$target")
            return false
        }
        val function = VehicleChannels.funcOf(ctx, key)
        if (function <= 0) {
            Logx.w("写 $key 未执行：通道未配置")
            return false
        }
        val zone = VehicleChannels.zoneOf(ctx, key)
        val accepted = if (Prefs.tempCustomizeWriteEnabled(ctx)) {
            // 两种接口仅兼容同一区域、同一浮点目标，不探测其它温度或改写用户目标。
            VehicleBridge.customizeWriteEnabled = true
            VehicleBridge.writeTempViaGlyCar(function, zone, target) ||
                VehicleBridge.writeTempCustomize(function, zone, target)
        } else {
            // 显式整数路径保留既有 0.2℃ 索引编码，不自动启用浮点写入。
            writeChannel(ctx, key, Math.round(target / 0.2f))
        }
        Logx.d(
            "设温 ${if (driver) "主驾" else "副驾"} target=$target℃ " +
                "zone=0x${zone.toString(16)} accepted=$accepted（生效看真实观测）"
        )
        // 定向确认由调用者统一调度，此处不保存温度、不安排温度专用回读。
        return accepted
    }

    fun writeIntegerTemperatureStep(ctx: Context, driver: Boolean, direction: Int, sync: Boolean): Boolean {
        val key = if (driver) "temp_driver" else "temp_passenger"
        if (!HvacCommands.canWrite(ctx, key)) return false
        if (direction != 1 && direction != 2) {
            Logx.w("写 $key 未执行：整数温度方向无效 direction=$direction")
            return false
        }
        val accepted = writeChannel(ctx, key, direction)
        if (!sync) return accepted
        // 同步是明确地向另一侧发相同方向；即使一侧拒绝也不丢弃另一侧的独立动作。
        val otherKey = if (driver) "temp_passenger" else "temp_driver"
        val otherAccepted = writeChannel(ctx, otherKey, direction)
        return accepted && otherAccepted
    }

    fun writeRecirculation(ctx: Context, target: Boolean): Boolean {
        if (!HvacCommands.canWrite(ctx, "recirc")) return false
        val function = VehicleChannels.funcOf(ctx, "recirc")
        if (function <= 0) {
            Logx.w("写 recirc 未执行：通道未配置")
            return false
        }
        val zone = VehicleChannels.zoneOf(ctx, "recirc")
        val value = HvacChannels.encodeInt(function, "recirc", if (target) 1 else 0) ?: return false
        // 全局无 area 与显式全局区是同一目标；仅兼容调用形态，不试其它值或区域。
        val noArea = if (zone == Int.MIN_VALUE && VehicleBridge.hasNoAreaOverload()) {
            VehicleBridge.writeIntNoArea(function, value)
        } else null
        val accepted = noArea == true || VehicleBridge.verifyWrite(function, zone, value)
        Logx.d(
            "写 内外循环 target=${if (target) "内" else "外"} zone=$zone " +
                "raw=0x${value.toString(16)} accepted=$accepted（生效看真实观测）"
        )
        return accepted
    }

    fun writeDual(ctx: Context, on: Boolean): Boolean {
        if (!HvacCommands.canWrite(ctx, "temp_dual")) return false
        val function = VehicleChannels.funcOf(ctx, "temp_dual")
        if (function <= 0) {
            Logx.w("写 temp_dual 未执行：通道未配置")
            return false
        }
        val zone = VehicleChannels.zoneOf(ctx, "temp_dual")
        val current = VehicleBridge.readInt(function, zone)?.let { raw ->
            // 双区只认既有 0/1 值域，不把其它开关的 10/11 编码当作已达目标。
            if ((raw and 0xff) in 0..1) HvacChannels.boolValue(raw) else null
        }
        if (current == on) {
            // 已有真实观测时避免重复翻转；没有发出写调用，不能报告为调用已接受。
            Logx.d("双温同步未写入：配置区观测已是${if (on) "开" else "关"}，避免重复翻转")
            return false
        }
        val accepted = writeChannel(ctx, "temp_dual", if (on) 1 else 0)
        Logx.d(
            "写 车机双温同步 target=${if (on) "开" else "关"} " +
                "func=0x${function.toString(16)} zone=0x${zone.toString(16)} " +
                "accepted=$accepted（生效看真实观测）"
        )
        return accepted
    }
}
