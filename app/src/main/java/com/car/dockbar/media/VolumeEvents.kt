package com.car.dockbar.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.car.dockbar.media.CabinVolume.Channel
import com.car.dockbar.media.CabinVolume.Level
import com.car.dockbar.util.Logx

/** 随 Dock 服务监听；广播只触发后台回读，音量真值来自 AudioManager。 */
@MainThread
internal object VolumeEvents {
    private const val ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    private const val NO_EXTRA = Int.MIN_VALUE

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Channel, Level, Long) -> Unit>()
    @Volatile private var active: Session? = null

    fun addListener(listener: (Channel, Level, Long) -> Unit) { listeners += listener }
    fun removeListener(listener: (Channel, Level, Long) -> Unit) { listeners -= listener }

    fun start(ctx: Context, worker: Handler) {
        if (active != null) return
        val session = Session(ctx.applicationContext, worker)
        active = session
        runCatching { session.register() }.onFailure {
            active = null
            Logx.w("音量事件注册失败，继续使用轮询：${it.javaClass.simpleName}")
        }
    }

    fun stop() {
        val session = active ?: return
        active = null
        session.unregister()
    }

    private class StreamRead(val channel: Channel) {
        var queued = false
        var pending = false
    }

    private class Session(private val ctx: Context, private val worker: Handler) {
        private val reads = CabinVolume.channels.associate { it.stream to StreamRead(it) }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION || isInitialStickyBroadcast || active !== this@Session) return
                val stream = runCatching { intent.getIntExtra(STREAM, NO_EXTRA) }.getOrNull() ?: return
                val read = reads[stream] ?: return
                if (!hasConsumer(read.channel)) return
                if (read.queued) read.pending = true else queue(read)
            }
        }

        fun register() {
            // 系统或厂商进程可能在本进程外发送；忽略广播携带的音量值。
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        }

        fun unregister() {
            runCatching { ctx.unregisterReceiver(receiver) }.onFailure {
                Logx.w("音量事件撤销失败，已忽略旧回调：${it.javaClass.simpleName}")
            }
        }

        private fun hasConsumer(channel: Channel): Boolean = channel == CabinVolume.MEDIA || listeners.isNotEmpty()

        private fun queue(read: StreamRead) {
            // 每路最多一个在途回读，期间的新通知合并为一次后续回读。
            read.pending = false
            read.queued = worker.post {
                if (active !== this) return@post
                val sampledAt = SystemClock.elapsedRealtimeNanos()
                val level = CabinVolume.readEventLevel(ctx, read.channel)
                main.post applySample@{
                    if (active !== this) return@applySample
                    if (level != null) {
                        if (read.channel == CabinVolume.MEDIA) CabinVolume.publishMediaEvent(level, sampledAt)
                        listeners.forEach { it(read.channel, level, sampledAt) }
                    }
                    read.queued = false
                    if (read.pending && hasConsumer(read.channel)) queue(read)
                }
            }
        }
    }
}
