package com.car.dockbar.vehicle

/** 仅以当前连接中已确认的订阅和合法事件证明决定轮询兜底周期。 */
internal object HvacRefreshPolicy {
    private const val FAN_MANUAL = 1 shl 0
    private const val FAN_AUTO = 1 shl 1
    private const val AC = 1 shl 2
    private const val RECIRC = 1 shl 3
    private const val AIR_DIRECTION = 1 shl 4
    private const val TEMP_DUAL = 1 shl 5
    private const val TEMP_DRIVER = 1 shl 6
    private const val TEMP_PASSENGER = 1 shl 7

    private const val FAN_MASK = FAN_MANUAL or FAN_AUTO
    private const val CLIMATE_MASK = AC or RECIRC or AIR_DIRECTION or TEMP_DUAL
    private const val TEMPERATURE_MASK = TEMP_DRIVER or TEMP_PASSENGER

    fun bit(key: String): Int = when (key) {
        "fan_manual" -> FAN_MANUAL
        "fan_auto" -> FAN_AUTO
        "ac" -> AC
        "recirc" -> RECIRC
        "air_direction" -> AIR_DIRECTION
        "temp_dual" -> TEMP_DUAL
        "temp_driver" -> TEMP_DRIVER
        "temp_passenger" -> TEMP_PASSENGER
        else -> 0
    }

    /** 配置变化时构建位图；未知通道不参与空调降频。 */
    fun mask(keys: Collection<String>): Int {
        var mask = 0
        for (key in keys) mask = mask or bit(key)
        return mask
    }

    fun intervalMs(
        group: VehicleRefreshGroup,
        configured: Int,
        seen: Int,
        registered: Boolean,
    ): Long {
        if (!registered) return group.intervalMs
        return when (group) {
            VehicleRefreshGroup.FAN -> {
                // 手动和自动风量互斥，任一路完整等级事件即可证明本组事件可用。
                if (configured and FAN_MASK == FAN_MASK && seen and FAN_MASK != 0) 2_500L
                else group.intervalMs
            }
            VehicleRefreshGroup.CLIMATE -> {
                if (configured and CLIMATE_MASK == CLIMATE_MASK && seen and CLIMATE_MASK == CLIMATE_MASK) 2_500L
                else group.intervalMs
            }
            VehicleRefreshGroup.TEMPERATURE -> {
                if (configured and TEMPERATURE_MASK == TEMPERATURE_MASK && seen and TEMPERATURE_MASK == TEMPERATURE_MASK) 5_000L
                else group.intervalMs
            }
            VehicleRefreshGroup.COMFORT, VehicleRefreshGroup.SENSORS -> group.intervalMs
        }
    }
}
