package com.car.dockbar.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.car.dockbar.media.CabinVolume.Channel
import com.car.dockbar.media.CabinVolume.Level
import com.car.dockbar.util.Logx

/** 只在用户开启验证时监听。报告、会话和界面通知归主线程，AudioManager 回读归同一 worker。 */
@MainThread
internal object VolumeEventValidation {
    private const val ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    private const val VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    private const val DURATION_MS = 120_000L
    private const val POLL_MS = 500L
    private const val NO_EXTRA = Int.MIN_VALUE

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Channel, Level, Long) -> Unit>()
    private var active: Session? = null
    private var lastReport: String? = null

    fun addListener(listener: (Channel, Level, Long) -> Unit) { listeners += listener }
    fun removeListener(listener: (Channel, Level, Long) -> Unit) { listeners -= listener }

    fun start(ctx: Context): Boolean {
        active?.finish("重新开始")
        val session = Session(ctx.applicationContext)
        active = session
        return session.start()
    }

    fun stop(): Boolean {
        val session = active ?: return false
        session.finish("用户停止")
        return true
    }

    fun report(): String {
        active?.let { if (it.isCurrent()) return it.report("验证中，剩余约 ${it.secondsLeft()} 秒") }
        return lastReport ?: "还没有音量事件验证记录。"
    }

    private data class Event(val receivedAt: Long, val value: Int?)

    private class Probe(val channel: Channel) {
        var eventQueued = false
        var pending: Event? = null
        var broadcasts = 0
        var eventReads = 0
        var eventFailures = 0
        var eventChanges = 0
        var mismatches = 0
        var pollReads = 0
        var pollFailures = 0
        var pollChanges = 0
        var initial: Level? = null
        var latest: Level? = null
        var lastEvent: String? = null

        fun summary(): String = buildString {
            appendLine("${channel.title}（流 ${channel.stream}）起始=${initial.label()} 最近=${latest.label()}")
            appendLine("  EVENT 广播=$broadcasts 有效回读=$eventReads 确认变化=$eventChanges 失败=$eventFailures 值不符=$mismatches")
            append("  POLL 有效读取=$pollReads 先发现变化=$pollChanges 失败=$pollFailures")
            lastEvent?.let { append("\n  最近 EVENT：$it") }
        }
    }

    private class Session(private val ctx: Context) {
        private val startAt = SystemClock.elapsedRealtime()
        private val deadline = startAt + DURATION_MS
        private val thread = HandlerThread("volume-validation").apply { start() }
        val worker = Handler(thread.looper)
        private val probes = CabinVolume.channels.associate { it.stream to Probe(it) }
        private var registerError: String? = null
        private var unregisterError: String? = null
        private var unknownCount = 0
        private var lastUnknownStream: Int? = null
        @Volatile private var closed = false

        private val timeout = Runnable { finish("到期自动结束") }
        private val poll = object : Runnable {
            override fun run() {
                for (probe in probes.values) {
                    if (!canRead()) return
                    sample(probe, null)
                }
                if (canRead()) worker.postDelayed(this, POLL_MS)
            }
        }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION || isInitialStickyBroadcast || !isCurrent()) return
                val receivedAt = SystemClock.elapsedRealtime()
                val stream = intent.intExtra(STREAM)
                val probe = probes[stream]
                if (probe == null) {
                    unknownCount++
                    lastUnknownStream = stream
                    return
                }
                probe.broadcasts++
                probe.pending = Event(receivedAt, intent.intExtra(VALUE))
                queueEvent(probe)
            }
        }

        fun start(): Boolean {
            Logx.d("VOLUME_TEST stage=START durationMs=$DURATION_MS pollMs=$POLL_MS")
            // 允许系统或厂商进程发送；广播仅提示回读，不信任其中的档位。
            registerError = runCatching {
                ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
            }.exceptionOrNull()?.javaClass?.simpleName
            Logx.d("VOLUME_TEST stage=REGISTER accepted=${registerError == null} error=${registerError ?: "—"}")
            worker.post(poll)
            main.postDelayed(timeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            return registerError == null
        }

        fun secondsLeft(): Long = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)

        fun isCurrent(): Boolean {
            if (active !== this) return false
            if (canRead()) return true
            finish("到期自动结束")
            return false
        }

        private fun canRead(): Boolean = !closed && SystemClock.elapsedRealtime() < deadline

        private fun queueEvent(probe: Probe) {
            if (probe.eventQueued) return
            val event = probe.pending ?: return
            // 同一路只保留一个排队/执行中的回读和一个最新通知。
            probe.pending = null
            probe.eventQueued = true
            worker.post { if (canRead()) sample(probe, event) }
        }

        private fun sample(probe: Probe, event: Event?) {
            val sampledAt = SystemClock.elapsedRealtimeNanos()
            val level = CabinVolume.readForValidation(ctx, probe.channel)
            val delayMs = event?.let { SystemClock.elapsedRealtime() - it.receivedAt }
            main.post {
                if (!isCurrent()) return@post
                record(probe, level, event, delayMs, sampledAt)
                if (event != null) {
                    probe.eventQueued = false
                    queueEvent(probe)
                }
            }
        }

        private fun record(probe: Probe, level: Level?, event: Event?, delayMs: Long?, sampledAt: Long) {
            val old = probe.latest
            val changed = level != null && old != null && old.value != level.value
            if (level != null) {
                if (probe.initial == null) probe.initial = level
                probe.latest = level
            }
            if (event == null) {
                if (level == null) probe.pollFailures++ else probe.pollReads++
                if (changed) probe.pollChanges++
                if (level != null && (old == null || changed)) {
                    Logx.d("VOLUME_TEST stage=POLL stream=${probe.channel.stream} read=${level.label()} changed=$changed")
                }
                return
            }

            val mismatch = level != null && event.value != null && event.value != level.value
            val confirmedChange = changed && !mismatch
            if (level == null) probe.eventFailures++ else probe.eventReads++
            if (mismatch) probe.mismatches++
            if (confirmedChange) probe.eventChanges++
            probe.lastEvent = "广播值=${event.value ?: "—"} 回读=${level.label()} 接收→回读=${delayMs}ms"
            if (level != null) {
                val channel = probe.channel
                if (channel == CabinVolume.MEDIA) CabinVolume.publishValidationMedia(level, sampledAt)
                val shown = level.value.coerceAtLeast(channel.minValue)
                val display = if (shown == level.value) level else Level(shown, level.max)
                listeners.forEach { it(channel, display, sampledAt) }
            }
            Logx.d("VOLUME_TEST stage=EVENT stream=${probe.channel.stream} valid=${level != null} " +
                "confirmedChange=$confirmedChange mismatch=$mismatch ${probe.lastEvent}")
        }

        fun finish(reason: String) {
            if (active !== this) return
            active = null
            closed = true
            main.removeCallbacks(timeout)
            worker.removeCallbacksAndMessages(null)
            thread.quitSafely()
            if (registerError == null) {
                unregisterError = runCatching { ctx.unregisterReceiver(receiver) }.exceptionOrNull()?.javaClass?.simpleName
            }
            lastReport = report("已结束：$reason")
            val counts = probes.values.joinToString(" ") { "流${it.channel.stream}=${it.broadcasts}/${it.eventReads}/${it.eventChanges}/${it.pollChanges}" }
            Logx.d("VOLUME_TEST stage=END reason=$reason unregisterError=${unregisterError ?: "—"} " +
                "unknown=$unknownCount (广播/有效回读/确认变化/轮询先发现变化) $counts")
        }

        fun report(status: String): String = buildString {
            appendLine("音量事件验证（VOLUME_TEST） · $status")
            val registration = registerError?.let { "失败（$it），仅做轮询对照" } ?: "调用成功，不代表车机发送了事件"
            appendLine("广播注册：$registration")
            unregisterError?.let { appendLine("广播撤销失败：$it，已忽略该会话回调") }
            appendLine("非目标/缺失流广播=$unknownCount${if (unknownCount > 0) "，最近流=${lastUnknownStream ?: "缺失"}" else ""}")
            appendLine("事件定向回读，轮询每轮结束后间隔 ${POLL_MS}ms。事件回读可合并；轮询先发现变化不等于确定漏报。")
            for (probe in probes.values) {
                appendLine()
                appendLine(probe.summary())
            }
            append("接收→回读含排队，不是操作→界面的总延迟；没有改变该路音量时，无事件不代表不支持。")
        }
    }

    private fun Intent.intExtra(key: String): Int? =
        runCatching { getIntExtra(key, NO_EXTRA) }.getOrNull()?.takeUnless { it == NO_EXTRA }

    private fun Level?.label(): String = if (this == null) "—" else "$value/$max"
}
