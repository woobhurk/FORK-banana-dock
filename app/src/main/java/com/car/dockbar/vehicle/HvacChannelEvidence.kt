package com.car.dockbar.vehicle

import com.car.dockbar.util.Logx
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 只消费现有读数和回调；连接、订阅代际隔离，绝不因取证访问车辆或改变配置。 */
internal object HvacChannelEvidence {
    data class ChannelConfig(val key: String, val fid: Int, val configuredZone: Int, val polling: Boolean = true)

    private class Channel(val config: ChannelConfig) {
        var requested = false
        var registration = "NOT_REQUESTED"
        var events = 0L
        var validEvents = 0L
        var invalidEvents = 0L
        var wrongZoneEvents = 0L
        var eventChanges = 0L
        var staleEvents = 0L
        var reads = 0L
        var validReads = 0L
        var invalidReads = 0L
        var staleReads = 0L
        var readChanges = 0L
        var pollOnlyChanges = 0L
        var zoneFallbackReads = 0L
        var readMsTotal = 0L
        var readMsMax = 0L
        var lastReadZone: Int? = null
        var lastEventZone: Int? = null
        var lastRead = "UNKNOWN"
        var lastEvent = "UNKNOWN"
        // 整数和浮点可能是不同编码，不把两者切换冒充车辆变化。
        val observed = arrayOfNulls<Number>(2)
        val observedAt = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)
        var lastReadLogAt = Long.MIN_VALUE
        var lastEventLogAt = Long.MIN_VALUE
        var lastErrorLogAt = Long.MIN_VALUE
        var suppressed = 0L
    }

    private const val SUMMARY_MS = 30_000L
    private const val RECENT_LIMIT = 24
    private val lock = Any()
    private var generation = -1L
    private var configuration = -1L
    private var backend = "UNKNOWN"
    private var connected = false
    private val channels = linkedMapOf<String, Channel>()
    private val recent = ArrayDeque<String>(RECENT_LIMIT)
    // 仅日志独立排队，不创建第二条车辆队列；慢磁盘不能阻塞事件/UI或HAL采样。
    private val logWorker = ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(64), { runnable -> Thread(runnable, "hvac-evidence-log").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())

    fun connection(connection: Long, label: String, ready: Boolean = true) {
        synchronized(lock) {
            if (connection <= generation) return
            generation = connection
            configuration = -1L
            backend = label
            connected = ready
            channels.clear()
            recent.clear()
        }
    }

    fun registration(connection: Long, subscription: Long, configs: List<ChannelConfig>, keys: Set<String>, accepted: Boolean?) {
        val line = synchronized(lock) {
            if (connection != generation || subscription < configuration) return
            configuration = subscription
            val retained = configs.mapTo(hashSetOf()) { it.key }
            channels.keys.retainAll(retained)
            for (config in configs) {
                val ch = channels[config.key]?.takeIf { it.config == config }
                    ?: Channel(config).also { channels[config.key] = it }
                ch.requested = config.key in keys && config.fid > 0
                ch.registration = if (!ch.requested) "NOT_REQUESTED" else when (accepted) {
                    null -> "PENDING"
                    true -> "ACCEPTED"
                    false -> "REJECTED"
                }
            }
            "REGISTER connection=$generation subscription=$configuration backend=$backend requested=$keys accepted=${accepted ?: "PENDING"}"
        }
        emit(line)
    }

    fun read(connection: Long, subscription: Long, key: String, fid: Int, configuredZone: Int, actualZone: Int,
             raw: Number?, valid: Boolean, source: String, observedAtMs: Long, durationMs: Long) {
        val line = synchronized(lock) {
            val ch = channel(connection, subscription, key, fid, configuredZone) ?: return
            ch.reads++
            ch.lastReadZone = actualZone
            ch.lastRead = raw?.toString() ?: "null"
            val duration = durationMs.coerceAtLeast(0)
            ch.readMsTotal += duration
            ch.readMsMax = maxOf(ch.readMsMax, duration)
            if (actualZone != configuredZone) ch.zoneFallbackReads++
            val accepted = valid && raw != null
            val index = if (raw is Float || raw is Double) 1 else 0
            val previous = ch.observed[index]
            val stale = accepted && observedAtMs < ch.observedAt[index]
            val changed = accepted && !stale && previous != null && previous != raw
            if (accepted) {
                ch.validReads++
                if (stale) ch.staleReads++
                else {
                    if (changed) { ch.readChanges++; ch.pollOnlyChanges++ }
                    ch.observed[index] = raw
                    ch.observedAt[index] = observedAtMs
                }
            } else ch.invalidReads++
            val due = if (accepted && !stale) due(observedAtMs, ch.lastReadLogAt) else due(observedAtMs, ch.lastErrorLogAt)
            if (!changed && !stale && !due) { ch.suppressed++; return@synchronized null }
            if (accepted && !stale) ch.lastReadLogAt = observedAtMs else ch.lastErrorLogAt = observedAtMs
            remember("READ ${identity(ch)} actualZone=$actualZone source=$source raw=$raw valid=$accepted changed=$changed stale=$stale " +
                "pollOnlyChange=${changed && !stale} readMs=$duration reads=${ch.reads} validReads=${ch.validReads} " +
                "staleReads=${ch.staleReads} suppressed=${ch.suppressed}")
        }
        if (line != null) emit(line)
    }

    fun event(connection: Long, subscription: Long, key: String, fid: Int, actualZone: Int,
              raw: Number?, valid: Boolean, observedAtMs: Long) {
        val line = synchronized(lock) {
            val ch = channel(connection, subscription, key, fid) ?: return
            if (!ch.requested) return
            ch.events++
            ch.lastEventZone = actualZone
            ch.lastEvent = raw?.toString() ?: "null"
            val zoneMatches = ch.config.configuredZone == actualZone
            if (!zoneMatches) ch.wrongZoneEvents++
            val accepted = valid && zoneMatches && raw != null
            val index = if (raw is Float || raw is Double) 1 else 0
            val previous = ch.observed[index]
            val stale = accepted && observedAtMs < ch.observedAt[index]
            val changed = accepted && !stale && previous != null && previous != raw
            if (accepted) {
                ch.validEvents++
                if (stale) ch.staleEvents++
                else {
                    if (changed) ch.eventChanges++
                    ch.observed[index] = raw
                    ch.observedAt[index] = observedAtMs
                }
            } else ch.invalidEvents++
            val due = if (accepted && !stale) due(observedAtMs, ch.lastEventLogAt) else due(observedAtMs, ch.lastErrorLogAt)
            if (!changed && !stale && !due) { ch.suppressed++; return@synchronized null }
            if (accepted && !stale) ch.lastEventLogAt = observedAtMs else ch.lastErrorLogAt = observedAtMs
            remember("EVENT ${identity(ch)} actualZone=$actualZone raw=$raw valid=$accepted zoneMatches=$zoneMatches " +
                "changed=$changed stale=$stale events=${ch.events} validEvents=${ch.validEvents} staleEvents=${ch.staleEvents} suppressed=${ch.suppressed}")
        }
        if (line != null) emit(line)
    }

    /** 纯内存快照；缺少事件的结论始终是UNKNOWN，不把注册接受或轮询同值当作事件能力。 */
    fun report(): String = synchronized(lock) {
        buildString {
            appendLine("HVAC_CHANNEL_EVIDENCE connection=$generation subscription=$configuration backend=$backend connected=$connected")
            appendLine("覆盖边界：仅当前进程当前连接的现有分组整数/浮点读取及当前订阅回调；不额外探测，不含传感器/座椅记忆专用扫描。")
            appendLine("订阅切换保留同配置累计计数，丢弃旧订阅排队结果；连接重建清空。READ为真实调用样本，未必被UI采纳。")
            appendLine("eventEvidence=UNKNOWN表示未验证（包括未订阅、无事件或仅坏值）；ACCEPTED仅为注册接受，不证明物理效果。")
            appendLine("pollOnlyChanges表示有效轮询发现相对最近同编码观测的新值，此前没有匹配有效事件；不推断丢事件或SDK能力。")
            appendLine("重复日志每30秒聚合；统计持续累计；最近样本最多$RECENT_LIMIT 条，磁盘慢时允许丢日志但不丢统计。")
            if (channels.isEmpty()) appendLine("无通道观测：UNKNOWN")
            for (ch in channels.values) {
                appendLine("CHANNEL ${identity(ch)} requested=${ch.requested} registration=${ch.registration} eventEvidence=${if (ch.validEvents > 0) "OBSERVED" else "UNKNOWN"} " +
                    "events=${ch.events} validEvents=${ch.validEvents} invalidEvents=${ch.invalidEvents} wrongZoneEvents=${ch.wrongZoneEvents} eventChanges=${ch.eventChanges} staleEvents=${ch.staleEvents} " +
                    "reads=${ch.reads} validReads=${ch.validReads} invalidReads=${ch.invalidReads} staleReads=${ch.staleReads} readChanges=${ch.readChanges} pollOnlyChanges=${ch.pollOnlyChanges} " +
                    "lastReadZone=${ch.lastReadZone ?: "UNKNOWN"} lastEventZone=${ch.lastEventZone ?: "UNKNOWN"} zoneFallbackReads=${ch.zoneFallbackReads} " +
                    "readMsTotal=${ch.readMsTotal} readMsMax=${ch.readMsMax} lastRead=${ch.lastRead} lastEvent=${ch.lastEvent} suppressed=${ch.suppressed} " +
                    "coverage=${if (ch.config.polling) "GROUP_POLL" else "DECLARED_ONLY"} acquisition=${if (ch.reads > 0 || ch.events > 0) "OBSERVED" else "UNOBSERVED_UNKNOWN"}")
            }
            for (line in recent) appendLine("RECENT $line")
        }
    }

    private fun channel(connection: Long, subscription: Long, key: String, fid: Int, zone: Int? = null): Channel? {
        if (connection != generation || subscription != configuration) return null
        return channels[key]?.takeIf { it.config.fid == fid && (zone == null || it.config.configuredZone == zone) }
    }

    private fun identity(ch: Channel): String = "connection=$generation subscription=$configuration key=${ch.config.key} fid=0x${ch.config.fid.toUInt().toString(16)} configuredZone=${ch.config.configuredZone}"
    private fun due(now: Long, previous: Long): Boolean = previous == Long.MIN_VALUE || now - previous >= SUMMARY_MS
    private fun remember(line: String): String {
        if (recent.size == RECENT_LIMIT) recent.removeFirst()
        recent.addLast(line)
        return line
    }
    private fun emit(line: String) { logWorker.execute { Logx.d("HVAC_EVIDENCE $line") } }
}
