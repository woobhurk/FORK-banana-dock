package com.car.dockbar.vehicle

import android.content.Context
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

/** 所有真实车控读写共用的后台队列；写入入口必须再次核对执行时门禁。 */
internal object HvacCommands {
    @Volatile
    private var workerThread: Thread? = null

    val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dock-hvac-write").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
            workerThread = this
        }
    }

    fun isWorkerThread(): Boolean = Thread.currentThread() === workerThread

    private val latestTargets = ConcurrentHashMap<String, Long>()

    /** 仅合并尚未执行的绝对目标；步进、翻转和组合动作不得走此入口。 */
    fun submitLatest(ctx: Context, key: String, sequence: Long, onSkipped: () -> Unit, action: () -> Unit) {
        val c = ctx.applicationContext
        val connection = VehicleBridge.connectionGeneration
        if (!canSubmit(c, key)) { onSkipped(); return }
        if (sequence != 0L) latestTargets[key] = sequence
        executor.execute {
            if ((sequence != 0L && latestTargets[key] != sequence) ||
                connection != VehicleBridge.connectionGeneration || !canWrite(c, key)) {
                if (sequence != 0L) latestTargets.remove(key, sequence)
                onSkipped()
                return@execute
            }
            try {
                action()
            } catch (t: Throwable) {
                Logx.e("HVAC_CMD $key 调用异常，回归最近观测", t)
                onSkipped()
            } finally {
                if (sequence != 0L) latestTargets.remove(key, sequence)
            }
        }
    }

    /** 切换模式时使旧模式尚未执行的绝对目标失效。 */
    fun supersede(key: String) { latestTargets.remove(key) }

    /** 提交前检查不要求当前线程是后台线程，也不代表命令已被厂商接受。 */
    fun canSubmit(ctx: Context, label: String): Boolean {
        val reason = when {
            !Prefs.vehicleApiEnabled(ctx) -> "车控 API 已关闭"
            Prefs.simulate(ctx) -> "当前为模拟模式"
            !Prefs.vehicleWriteEnabled(ctx) -> "车控写入已关闭或熔断"
            !VehicleBridge.isReady -> "车控会话未连接"
            else -> null
        }
        if (reason == null) return true
        Logx.w("HVAC_WRITE $label 未执行：$reason")
        return false
    }

    /** 不隐式排队；任何直接绕过共享后台队列的厂商写入都必须拒绝。 */
    fun canWrite(ctx: Context?, label: String): Boolean {
        if (ctx == null) {
            Logx.w("HVAC_WRITE $label 未执行：缺少应用上下文")
            return false
        }
        if (!isWorkerThread()) {
            Logx.w("HVAC_WRITE $label 未执行：不在车控后台线程")
            return false
        }
        return canSubmit(ctx, label)
    }
}
