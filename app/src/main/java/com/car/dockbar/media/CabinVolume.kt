package com.car.dockbar.media

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import com.car.dockbar.util.Logx

/**
 * 媒体、导航、电话、语音音量。
 *
 * 显示和写入都用 AudioManager 的流索引，与系统同一套数字。
 * DCY11 日志里媒体流 3、导航流 15 的上限都是 39。
 * 电话通道最低保持为 1；语音通道使用厂商流 11。
 * 不再加减 5：界面 0 写入 0，界面 15 写入 15。
 */
object CabinVolume {

    data class Channel(
        val title: String,
        val groupId: Int,
        val stream: Int,
        val minValue: Int = 0
    )

    val MEDIA = Channel("媒体", 0, 3)
    val NAVI = Channel("导航", 1, 15)
    val PHONE = Channel("电话", 2, 0, minValue = 1)
    val VOICE = Channel("语音", 3, 11)
    internal val channels = listOf(MEDIA, NAVI, PHONE, VOICE)

    private val mediaShownListeners = mutableListOf<(Int) -> Unit>()
    private val mediaLock = Any()
    private var cachedMediaLevel: Level? = null
    private var mediaWriteGeneration = 0L
    private var mediaObservationAt = Long.MIN_VALUE

    fun addMediaShownListener(listener: (Int) -> Unit) {
        synchronized(mediaLock) {
            if (listener !in mediaShownListeners) mediaShownListeners += listener
        }
    }

    fun removeMediaShownListener(listener: (Int) -> Unit) {
        synchronized(mediaLock) { mediaShownListeners -= listener }
    }

    data class Level(val value: Int, val max: Int)

    /** 验证使用真实流索引：失败和越界值不记作观测。 */
    internal fun readForValidation(ctx: Context, channel: Channel): Level? = runCatching {
        val manager = audio(ctx)
        val max = manager.getStreamMaxVolume(channel.stream)
        val value = manager.getStreamVolume(channel.stream)
        if (max > 0 && value in 0..max) Level(value, max) else null
    }.getOrNull()

    internal fun publishValidationMedia(level: Level, sampledAt: Long) {
        recordMediaLevel(level, null, sampledAt)
    }

    /** 读取流索引；各通道的最小值和上限均使用系统返回的范围。 */
    fun read(ctx: Context, channel: Channel): Level {
        val sampledAt = if (channel == MEDIA) SystemClock.elapsedRealtimeNanos() else 0L
        val max = streamMax(ctx, channel)
        val readGeneration = if (channel == MEDIA) {
            synchronized(mediaLock) { mediaWriteGeneration }
        } else null
        val observed = runCatching { audio(ctx).getStreamVolume(channel.stream) }.getOrNull()
        val level = Level(clamp(channel, observed ?: 0, max), max)
        if (observed != null && channel == MEDIA) recordMediaLevel(level, readGeneration, sampledAt)
        return level
    }

    fun step(ctx: Context, channel: Channel, delta: Int) {
        val now = read(ctx, channel)
        write(ctx, channel, (now.value + delta).coerceIn(0, now.max))
    }

    /** [shown] 直接写入同号的流索引，不再加 5。 */
    fun write(ctx: Context, channel: Channel, shown: Int): Int {
        val max = streamMax(ctx, channel)
        val written = clamp(channel, shown, max)
        val writeGeneration = if (channel == MEDIA) {
            synchronized(mediaLock) {
                mediaWriteGeneration++
                mediaWriteGeneration
            }
        } else null
        runCatching {
            audio(ctx).setStreamVolume(channel.stream, written, 0)
        }.onFailure { Logx.e("音量：setStreamVolume(${channel.stream}) 失败", it) }
        val observed = runCatching { audio(ctx).getStreamVolume(channel.stream) }.getOrNull()
        val raw = observed?.coerceIn(0, max)
        val listeners = if (channel == MEDIA) {
            finishMediaWrite(writeGeneration!!, raw, max)
        } else emptyList()
        listeners.forEach { it(raw ?: written) }
        Logx.d("音量 ${channel.title} 流${channel.stream} 写入 $written 读回 ${raw ?: "失败"} 上限 $max")
        return written
    }

    /** 返回媒体缓存；冷启动没有缓存时只进行一次真实读取。 */
    fun cachedMediaLevel(ctx: Context): Level {
        synchronized(mediaLock) {
            cachedMediaLevel?.let { return it }
        }
        return read(ctx, MEDIA)
    }

    private fun recordMediaLevel(level: Level, expectedGeneration: Long?, sampledAt: Long) {
        val listeners = synchronized(mediaLock) {
            if (expectedGeneration != null && expectedGeneration != mediaWriteGeneration) {
                return@synchronized emptyList<(Int) -> Unit>()
            }
            // 验证回读在后台完成，不能盖过更新的日常采样或本地调节。
            if (sampledAt < mediaObservationAt) return@synchronized emptyList<(Int) -> Unit>()
            mediaObservationAt = sampledAt
            val changed = cachedMediaLevel?.value != level.value
            cachedMediaLevel = level
            if (changed) mediaShownListeners.toList() else emptyList()
        }
        listeners.forEach { it(level.value) }
    }

    private fun finishMediaWrite(generation: Long, raw: Int?, max: Int): List<(Int) -> Unit> {
        val listeners = synchronized(mediaLock) {
            if (mediaWriteGeneration != generation) return@synchronized emptyList<(Int) -> Unit>()
            mediaWriteGeneration++
            mediaObservationAt = SystemClock.elapsedRealtimeNanos()
            if (raw == null) return@synchronized emptyList<(Int) -> Unit>()
            val level = Level(raw, max)
            val changed = cachedMediaLevel?.value != level.value
            cachedMediaLevel = level
            if (changed) mediaShownListeners.toList() else emptyList()
        }
        return listeners
    }

    private fun clamp(channel: Channel, value: Int, max: Int): Int {
        val min = channel.minValue.coerceIn(0, max)
        return value.coerceIn(min, max)
    }

    private fun streamMax(ctx: Context, channel: Channel): Int =
        runCatching { audio(ctx).getStreamMaxVolume(channel.stream) }.getOrDefault(1).coerceAtLeast(1)


    private fun audio(ctx: Context): AudioManager =
        ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
