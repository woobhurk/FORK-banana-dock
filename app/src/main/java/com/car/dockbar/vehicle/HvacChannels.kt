package com.car.dockbar.vehicle

import com.car.dockbar.config.VehicleChannels.AC_MAX_VALIDATION_KEY
import com.car.dockbar.vehicle.VehicleController.State
import kotlin.math.abs

/** 车控通道的纯编码/解码规则，不访问配置、厂商 API 或持久化状态。 */
internal object HvacChannels {
    private const val VALUE_ERROR = 0xfd
    private const val VALUE_NONE = 0xfe
    private const val VALUE_UNKNOWN = 0xff
    private const val FAN_LEVEL_AUTO = 10
    private const val ZONE_FRONT = 0x8
    private const val ZONE_GLOBAL = Int.MIN_VALUE
    private const val TEMP_MIN = 5f
    private const val TEMP_MAX = 45f

    /** AUTO 风量的低字节按车机实际值域乱序排列，索引就是档位。 */
    val FAN_AUTO_VALUE_BY_LEVEL: IntArray = intArrayOf(
        0,
        0x10020204,
        0x10020201,
        0x10020202,
        0x10020203,
        0x10020205
    )

    fun isSentinel(raw: Int): Boolean = when (raw and 0xff) {
        VALUE_ERROR, VALUE_NONE, VALUE_UNKNOWN -> true
        else -> false
    }

    fun boolValue(raw: Int): Boolean? = when (raw and 0xff) {
        0, 10 -> false
        1, 11 -> true
        else -> null
    }

    /** 浮点温度只接受有限且位于可观测安全区间的值。 */
    fun normalizeTemperature(value: Float): Float? =
        value.takeIf { it.isFinite() && it in 10f..40f }

    /** int 温度保留旧车机的三种编码兜底顺序。 */
    fun temperatureValue(
        raw: Int?,
        base: Float = 20f,
        step: Float = 0.5f,
        indexBase: Int = 100
    ): Float? {
        if (raw == null || raw == 0 || isSentinel(raw)) return null
        val index = raw and 0xff
        if (index <= 0) return null
        if (index in 5..45) return index.toFloat()
        val byPoint2 = index * 0.2f
        if (byPoint2 in TEMP_MIN..TEMP_MAX) return byPoint2
        val byBase = base + (index - indexBase) * step
        return byBase.takeIf { it in TEMP_MIN..TEMP_MAX }
    }

    fun temperatureMatches(value: Float?, target: Float?): Boolean {
        if (value == null || target == null) return value == null && target == null
        return value.isFinite() && target.isFinite() && abs(value - target) <= 0.10001f
    }

    fun fanAutoValue(level: Int): Int {
        val mapped = FAN_AUTO_VALUE_BY_LEVEL.getOrNull(level.coerceIn(1, 5)) ?: 0x10020202
        return (0x10020200 and 0xffffff00.toInt()) or (mapped and 0xff)
    }

    fun fanAutoLevelOf(raw: Int): Int {
        val low = raw and 0xff
        for (level in 1..5) {
            if ((FAN_AUTO_VALUE_BY_LEVEL[level] and 0xff) == low) return level
        }
        return 0
    }

    private fun fanState(base: State, auto: Boolean, level: Int, max: Int): State =
        if (base.fanAuto == auto && base.fanLevel == level && base.fanMax == max) base
        else base.copy(fanAuto = auto, fanLevel = level, fanMax = max)

    fun merge(
        base: State,
        key: String,
        raw: Int,
        tempBase: Float = 20f,
        tempStep: Float = 0.5f,
        tempIndexBase: Int = 100
    ): State {
        if (isSentinel(raw)) return base
        val value = raw and 0xff
        return when (key) {
            "fan_manual" -> when {
                value == FAN_LEVEL_AUTO -> fanState(
                    base, true, if (base.fanAuto && base.fanLevel in 1..5) base.fanLevel else 0, 5
                )
                value in 0..9 -> fanState(base, false, value, 9)
                else -> base
            }
            "fan_auto" -> {
                val level = fanAutoLevelOf(raw)
                when {
                    level > 0 -> fanState(base, true, level, 5)
                    boolValue(raw) == true -> fanState(
                        base, true, if (base.fanAuto && base.fanLevel in 1..5) base.fanLevel else 0, 5
                    )
                    boolValue(raw) == false -> fanState(base, false, base.fanLevel, 9)
                    else -> base
                }
            }
            "ac" -> boolValue(raw)?.let { if (base.acOn == it) base else base.copy(acOn = it) } ?: base
            "recirc" -> boolValue(raw)?.let {
                if (base.recircInside == it) base else base.copy(recircInside = it)
            } ?: base
            "temp_dual" -> if (value in 0..1) {
                val on = value == 1
                if (base.tempDualOn == on) base else base.copy(tempDualOn = on)
            } else base
            "air_direction" -> if (value in 1..7 && base.airDirection != value) {
                base.copy(airDirection = value)
            } else base
            "seat_vent" -> if (value in 0..3 && base.seatVent != value) {
                base.copy(seatVent = value)
            } else base
            "seat_heat" -> if (value in 0..3 && base.seatHeat != value) {
                base.copy(seatHeat = value)
            } else base
            "seat_vent_passenger" -> if (value in 0..3 && base.seatVentPassenger != value) {
                base.copy(seatVentPassenger = value)
            } else base
            "seat_heat_passenger" -> if (value in 0..3 && base.seatHeatPassenger != value) {
                base.copy(seatHeatPassenger = value)
            } else base
            "defrost_front" -> boolValue(raw)?.let {
                if (base.defrostFront == it) base else base.copy(defrostFront = it)
            } ?: base
            "defrost_rear" -> boolValue(raw)?.let {
                if (base.defrostRear == it) base else base.copy(defrostRear = it)
            } ?: base
            "steering_heat" -> if (value in 0..3 && base.steeringHeat != value) {
                base.copy(steeringHeat = value)
            } else base
            "temp_driver" -> temperatureValue(raw, tempBase, tempStep, tempIndexBase)?.let {
                if (base.driverTempC == it) base else base.copy(driverTempC = it)
            } ?: base
            "temp_passenger" -> temperatureValue(raw, tempBase, tempStep, tempIndexBase)?.let {
                if (base.passengerTempC == it) base else base.copy(passengerTempC = it)
            } ?: base
            else -> base
        }
    }

    fun validRaw(
        key: String,
        raw: Int,
        tempBase: Float = 20f,
        tempStep: Float = 0.5f,
        tempIndexBase: Int = 100
    ): Boolean {
        if (isSentinel(raw)) return false
        val value = raw and 0xff
        return when (key) {
            "fan_manual" -> value in 0..FAN_LEVEL_AUTO
            "fan_auto" -> fanAutoLevelOf(raw) > 0 || boolValue(raw) != null
            "ac", "recirc", "defrost_front", "defrost_rear" -> boolValue(raw) != null
            AC_MAX_VALIDATION_KEY -> raw in 0..1
            "temp_dual" -> value in 0..1
            "air_direction" -> value in 1..7
            "seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger", "steering_heat" ->
                value in 0..3
            "temp_driver", "temp_passenger" ->
                temperatureValue(raw, tempBase, tempStep, tempIndexBase) != null
            else -> false
        }
    }

    /** 返回本次样本实际确认的属性位；模式样本不能冒充完整风量档位。 */
    fun observationProperties(key: String, raw: Any?): Int {
        val property = HvacProperty.fromKey(key) ?: return 0
        if (property == HvacProperty.FAN) {
            val value = (raw as? Number)?.toInt() ?: return 0
            if (key == "fan_auto" && fanAutoLevelOf(value) == 0) return 0
            if (key == "fan_manual" && (value and 0xff) == FAN_LEVEL_AUTO) return 0
        }
        return property.mask
    }

    fun encodeInt(fid: Int, key: String, value: Int): Int? {
        if (fid <= 0) return null
        val prefix = fid and 0xffffff00.toInt()
        return when (key) {
            "fan_manual" -> when (value) {
                0 -> 0
                in 1..9 -> prefix or value
                else -> null
            }
            "fan_auto" -> if (value in 1..5) {
                (fid and 0xffffff00.toInt()) or (fanAutoValue(value) and 0xff)
            } else null
            "ac" -> if (value in 0..1) value else null
            "recirc" -> when (value) {
                0 -> 0
                1 -> prefix or 1
                else -> null
            }
            "air_direction" -> if (value in 1..7) (prefix or value) else null
            "temp_driver", "temp_passenger" -> if (value in 0..0xff) (prefix or value) else null
            "seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger" -> when (value) {
                0 -> 0
                in 1..3 -> prefix or value
                else -> null
            }
            "steering_heat" -> if (value in 0..3) (prefix or value) else null
            "defrost_front", "defrost_rear" -> if (value in 0..1) (prefix or value) else null
            // 这些是仍存在于配置表的旧复合通道；未知 key 不扩展为任意低字节。
            "auto_master", "temp_dual" -> if (value in 0..1) (prefix or value) else null
            else -> null
        }
    }

    fun readZones(key: String, configured: Int): IntArray {
        return when (key) {
            "temp_driver", "temp_passenger",
            "seat_vent", "seat_heat", "seat_vent_passenger", "seat_heat_passenger" ->
                intArrayOf(configured)
            "fan_manual", "fan_auto", "air_direction" ->
                if (configured == ZONE_FRONT) intArrayOf(configured)
                else intArrayOf(configured, ZONE_FRONT)
            "ac", "recirc", "temp_dual" ->
                if (configured == ZONE_GLOBAL) intArrayOf(configured)
                else intArrayOf(configured, ZONE_GLOBAL)
            else -> intArrayOf(configured)
        }
    }
}
