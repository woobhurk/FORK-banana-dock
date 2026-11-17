package com.car.dockbar.vehicle

import com.car.dockbar.vehicle.VehicleController.State

/** 每一位表示一个可独立确认的属性，风量三元组始终作为整体。 */
internal enum class HvacProperty(val key: String) {
    FAN("fan_manual"),
    TEMP_DRIVER("temp_driver"),
    TEMP_PASSENGER("temp_passenger"),
    AC("ac"),
    RECIRC("recirc"),
    AIR_DIRECTION("air_direction"),
    SEAT_VENT("seat_vent"),
    SEAT_HEAT("seat_heat"),
    SEAT_VENT_PASSENGER("seat_vent_passenger"),
    SEAT_HEAT_PASSENGER("seat_heat_passenger"),
    DEFROST_FRONT("defrost_front"),
    DEFROST_REAR("defrost_rear"),
    STEERING_HEAT("steering_heat");

    val mask: Int = 1 shl ordinal

    companion object {
        fun fromKey(key: String): HvacProperty? = when (key) {
            "fan_manual", "fan_auto" -> FAN
            "temp_driver" -> TEMP_DRIVER
            "temp_passenger" -> TEMP_PASSENGER
            "ac" -> AC
            "recirc" -> RECIRC
            "air_direction" -> AIR_DIRECTION
            "seat_vent" -> SEAT_VENT
            "seat_heat" -> SEAT_HEAT
            "seat_vent_passenger" -> SEAT_VENT_PASSENGER
            "seat_heat_passenger" -> SEAT_HEAT_PASSENGER
            "defrost_front" -> DEFROST_FRONT
            "defrost_rear" -> DEFROST_REAR
            "steering_heat" -> STEERING_HEAT
            else -> null
        }
    }
}

/** 仅由主线程访问；真实观测与有期限的显示目标互不覆盖。 */
internal class HvacState(initial: State) {
    var observed: State = initial
        private set

    private class Pending(
        val target: State,
        val sequence: Long,
        val deadlineMs: Long,
        val connection: Long
    )

    private val pending = arrayOfNulls<Pending>(HvacProperty.entries.size)
    private var sequence = 0L
    private var connection = Long.MIN_VALUE

    val pendingProperties: Int
        get() {
            var properties = 0
            for (property in HvacProperty.entries) {
                if (pending[property.ordinal] != null) properties = properties or property.mask
            }
            return properties
        }

    val nextDeadlineMs: Long
        get() {
            var next = Long.MAX_VALUE
            for (item in pending) {
                if (item != null && item.deadlineMs < next) next = item.deadlineMs
            }
            return next
        }

    fun target(before: State, after: State, nowMs: Long, connection: Long): Long {
        if (!acceptConnection(connection)) return 0L
        expire(nowMs, connection)
        val properties = changedProperties(before, after)
        if (properties == 0) return 0L
        val next = ++sequence
        // 同一次意图的普通属性共享不可变目标，风量单独保留较短期限。
        val regular = if (properties and HvacProperty.FAN.mask.inv() != 0) {
            Pending(after, next, nowMs + 3500L, connection)
        } else {
            null
        }
        for (property in HvacProperty.entries) {
            if (properties and property.mask == 0) continue
            pending[property.ordinal] = if (property == HvacProperty.FAN) {
                Pending(after, next, nowMs + 1500L, connection)
            } else {
                regular
            }
        }
        return next
    }

    fun observe(value: State, properties: Int, nowMs: Long, connection: Long) {
        if (!acceptConnection(connection)) return
        expire(nowMs, connection)
        observed = value
        // 未采样字段及缓存初始化不能确认目标；同值的合法样本也必须走这里。
        for (property in HvacProperty.entries) {
            if (properties and property.mask == 0) continue
            val item = pending[property.ordinal] ?: continue
            if (confirms(property, value, item.target)) pending[property.ordinal] = null
        }
    }

    fun display(nowMs: Long, connection: Long): State {
        acceptConnection(connection)
        // 旧代际的显示请求仍按已知最新代际合成，不能造成界面回滚。
        expire(nowMs, this.connection)
        if (nextDeadlineMs == Long.MAX_VALUE) return observed
        val fan = source(HvacProperty.FAN)
        val driver = source(HvacProperty.TEMP_DRIVER)
        val passenger = source(HvacProperty.TEMP_PASSENGER)
        val ac = source(HvacProperty.AC)
        val recirc = source(HvacProperty.RECIRC)
        val direction = source(HvacProperty.AIR_DIRECTION)
        val seatVent = source(HvacProperty.SEAT_VENT)
        val seatHeat = source(HvacProperty.SEAT_HEAT)
        val seatVentPassenger = source(HvacProperty.SEAT_VENT_PASSENGER)
        val seatHeatPassenger = source(HvacProperty.SEAT_HEAT_PASSENGER)
        val front = source(HvacProperty.DEFROST_FRONT)
        val rear = source(HvacProperty.DEFROST_REAR)
        val steering = source(HvacProperty.STEERING_HEAT)
        // 只合成一次，连接元数据始终来自真实观测。
        return observed.copy(
            driverTempC = driver.driverTempC,
            passengerTempC = passenger.passengerTempC,
            fanLevel = fan.fanLevel,
            fanMax = fan.fanMax,
            fanAuto = fan.fanAuto,
            acOn = ac.acOn,
            recircInside = recirc.recircInside,
            airDirection = direction.airDirection,
            seatVent = seatVent.seatVent,
            seatHeat = seatHeat.seatHeat,
            seatVentPassenger = seatVentPassenger.seatVentPassenger,
            seatHeatPassenger = seatHeatPassenger.seatHeatPassenger,
            defrostFront = front.defrostFront,
            defrostRear = rear.defrostRear,
            steeringHeat = steering.steeringHeat
        )
    }

    fun cancel(sequence: Long) {
        for (index in pending.indices) {
            if (pending[index]?.sequence == sequence) pending[index] = null
        }
    }

    /** 仅延长仍有效的本次目标；旧序号、已过期目标和其它属性均不复活。 */
    fun renew(sequence: Long, properties: Int, nowMs: Long, connection: Long) {
        if (sequence == 0L || !acceptConnection(connection)) return
        expire(nowMs, connection)
        for (property in HvacProperty.entries) {
            if (properties and property.mask == 0) continue
            val item = pending[property.ordinal] ?: continue
            if (item.sequence != sequence || item.connection != connection) continue
            val duration = if (property == HvacProperty.FAN) 1500L else 3500L
            pending[property.ordinal] = Pending(item.target, sequence, nowMs + duration, connection)
        }
    }

    fun clearTargets() {
        pending.fill(null)
    }

    private fun source(property: HvacProperty): State = pending[property.ordinal]?.target ?: observed

    private fun acceptConnection(value: Long): Boolean {
        // Bridge 代际单调递增，迟到的旧连接不能反向清除新连接目标或观测。
        if (value < connection) return false
        if (value != connection) {
            connection = value
            pending.fill(null)
        }
        return true
    }

    private fun expire(nowMs: Long, connection: Long) {
        for (index in pending.indices) {
            val item = pending[index] ?: continue
            if (item.connection != connection || nowMs >= item.deadlineMs) pending[index] = null
        }
    }

    companion object {
        /** 仅推导数值变化；同值有效样本仍由调用方显式提供属性位。 */
        fun changedProperties(before: State, after: State): Int {
            var properties = 0
            for (property in HvacProperty.entries) {
                if (!matches(property, before, after)) properties = properties or property.mask
            }
            return properties
        }

        // 确认允许真实浮点误差，目标变化推导仍保持精确比较，不改写观测值。
        private fun confirms(property: HvacProperty, value: State, target: State): Boolean = when (property) {
            HvacProperty.TEMP_DRIVER -> HvacChannels.temperatureMatches(value.driverTempC, target.driverTempC)
            HvacProperty.TEMP_PASSENGER -> HvacChannels.temperatureMatches(value.passengerTempC, target.passengerTempC)
            else -> matches(property, value, target)
        }


        private fun matches(property: HvacProperty, before: State, after: State): Boolean = when (property) {
            HvacProperty.FAN -> before.fanLevel == after.fanLevel &&
                before.fanMax == after.fanMax && before.fanAuto == after.fanAuto
            HvacProperty.TEMP_DRIVER -> before.driverTempC == after.driverTempC
            HvacProperty.TEMP_PASSENGER -> before.passengerTempC == after.passengerTempC
            HvacProperty.AC -> before.acOn == after.acOn
            HvacProperty.RECIRC -> before.recircInside == after.recircInside
            HvacProperty.AIR_DIRECTION -> before.airDirection == after.airDirection
            HvacProperty.SEAT_VENT -> before.seatVent == after.seatVent
            HvacProperty.SEAT_HEAT -> before.seatHeat == after.seatHeat
            HvacProperty.SEAT_VENT_PASSENGER -> before.seatVentPassenger == after.seatVentPassenger
            HvacProperty.SEAT_HEAT_PASSENGER -> before.seatHeatPassenger == after.seatHeatPassenger
            HvacProperty.DEFROST_FRONT -> before.defrostFront == after.defrostFront
            HvacProperty.DEFROST_REAR -> before.defrostRear == after.defrostRear
            HvacProperty.STEERING_HEAT -> before.steeringHeat == after.steeringHeat
        }
    }
}
