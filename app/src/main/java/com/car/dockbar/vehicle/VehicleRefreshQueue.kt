package com.car.dockbar.vehicle

/**
 * 车辆读取分组及其最短轮询间隔。
 */
internal enum class VehicleRefreshGroup(val intervalMs: Long) {
    FAN(500L),
    CLIMATE(1_000L),
    TEMPERATURE(2_500L),
    COMFORT(2_500L),
    SENSORS(2_500L),
}

/**
 * 车辆读取请求的单线程调度队列。
 *
 * 该队列只允许主线程访问，nowMs 必须来自同一个单调递增时钟。每组最多有一个
 * 待执行位；执行期间的显式请求单独记录为一个后续请求，不会因 poll 重复积压。
 */
internal class VehicleRefreshQueue {
    private companion object {
        private val GROUPS = VehicleRefreshGroup.values()
        private const val NO_GROUP = -1
    }

    private val dueAtMs = LongArray(GROUPS.size)
    private var queuedMask = 0
    private var rerunMask = 0
    private var runningOrdinal = NO_GROUP
    private var nextOrdinal = 0

    init {
        var ordinal = 0
        while (ordinal < dueAtMs.size) {
            dueAtMs[ordinal] = Long.MIN_VALUE
            ordinal++
        }
    }

    /** 请求一次读取；重复请求在同一状态下合并。 */
    fun request(group: VehicleRefreshGroup) {
        val bit = 1 shl group.ordinal
        if (runningOrdinal == group.ordinal) {
            rerunMask = rerunMask or bit
        } else {
            queuedMask = queuedMask or bit
        }
    }

    /** 将已到期限且尚未排队或执行的分组加入待执行位集合。 */
    fun poll(nowMs: Long) {
        var ordinal = 0
        while (ordinal < GROUPS.size) {
            val bit = 1 shl ordinal
            if (runningOrdinal != ordinal && queuedMask and bit == 0 && nowMs >= dueAtMs[ordinal]) {
                queuedMask = queuedMask or bit
            }
            ordinal++
        }
    }

    /** 按轮转顺序取出一个分组；同一时刻全局最多有一个执行分组。 */
    fun take(): VehicleRefreshGroup? {
        if (runningOrdinal != NO_GROUP || queuedMask == 0) return null

        var offset = 0
        while (offset < GROUPS.size) {
            val ordinal = (nextOrdinal + offset) % GROUPS.size
            val bit = 1 shl ordinal
            if (queuedMask and bit != 0) {
                queuedMask = queuedMask and bit.inv()
                runningOrdinal = ordinal
                nextOrdinal = (ordinal + 1) % GROUPS.size
                return GROUPS[ordinal]
            }
            offset++
        }
        return null
    }

    /** 完成当前分组，并从完成时刻开始计算该分组的下一次周期。 */
    fun complete(group: VehicleRefreshGroup, nowMs: Long) {
        val ordinal = group.ordinal
        if (runningOrdinal != ordinal) return

        dueAtMs[ordinal] = nowMs + group.intervalMs
        runningOrdinal = NO_GROUP

        val bit = 1 shl ordinal
        if (rerunMask and bit != 0) {
            rerunMask = rerunMask and bit.inv()
            queuedMask = queuedMask or bit
        }
    }
}
