package com.car.dockbar.vehicle

import android.content.Context
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

/** main线程捕获的状态版本，仅用于拒绝过期写后回读。 */
internal data class HvacConfirmationSample(
    val functionId: Int, val zone: Int, val key: String,
    val revision: Long, val epoch: Long, val connection: Long
)

internal interface HvacConfirmationReceiver {
    fun capture(functionId: Int, zone: Int): HvacConfirmationSample?
    fun accept(sample: HvacConfirmationSample, value: Number)
}

/** 控制写入与分组读取共用的后台队列；写入入口必须再次核对执行时门禁。 */
internal object HvacCommands {
    @Volatile
    private var workerThread: Thread? = null

    @Volatile var confirmations: HvacConfirmationReceiver? = null

    val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dock-hvac-write").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
            workerThread = this
        }
    }

    fun isWorkerThread(): Boolean = Thread.currentThread() === workerThread


    private val operationContext = ThreadLocal<HvacOperationTicket>()

    /** 保序提交一次用户操作；返回值只表示已进入队列，不表示厂商接受或车辆生效。 */
    fun submit(ctx: Context, label: String, onSkipped: () -> Unit = {}, action: () -> Unit): Boolean {
        val c = ctx.applicationContext
        val operation = HvacCommandTrace.operation(label, VehicleBridge.connectionGeneration)
        if (!canSubmit(c, label)) {
            HvacCommandTrace.operationState(operation, "SKIPPED", "提交门禁拒绝，未调用厂商接口")
            onSkipped()
            return false
        }
        HvacCommandTrace.operationState(operation, "QUEUED")
        executor.execute { execute(c, operation, onSkipped, action) }
        return true
    }

    private fun execute(ctx: Context, operation: HvacOperationTicket, onSkipped: () -> Unit, action: () -> Unit) {
        HvacCommandTrace.operationState(operation, "SENT")
        if (operation.connection != VehicleBridge.connectionGeneration) {
            HvacCommandTrace.operationState(operation, "STALE", "排队期间连接变化，未调用厂商接口")
            onSkipped()
            return
        }
        if (!canWrite(ctx, operation.label)) {
            HvacCommandTrace.operationState(operation, "SKIPPED", "执行门禁拒绝，未调用厂商接口")
            onSkipped()
            return
        }
        val previous = operationContext.get()
        operationContext.set(operation)
        try {
            action()
            HvacCommandTrace.operationState(operation, "FINISHED", "操作闭包结束，各次真实调用结果独立记录")
        } catch (t: Throwable) {
            HvacCommandTrace.operationState(operation, "FAILED", "操作闭包异常，不代表权限拒绝或物理效果")
            Logx.e("HVAC_CMD ${operation.label} 调用异常，回归最近观测", t)
            onSkipped()
        } finally {
            if (previous == null) operationContext.remove() else operationContext.set(previous)
        }
    }

    fun beginWrite(ctx: Context?, functionId: Int, zone: Int, value: Number, source: String): HvacWriteTicket =
        HvacCommandTrace.beginWrite(ctx, functionId, zone, value, source, operationContext.get())

    fun activateWrite(ticket: HvacWriteTicket): Boolean {
        val activated = HvacCommandTrace.activateWrite(ticket)
        if (activated) {
            val operation = operationContext.get()
            if (operation != null && operation.commandId == ticket.commandId) {
                operation.nativeWrites++
                if (ticket.floating) operation.floatWrites++
            }
        }
        return activated
    }

    /** 当前保序操作已经激活的浮点 native 尝试数；不是接受或物理效果计数。 */
    fun operationFloatWrites(): Int = operationContext.get()?.floatWrites ?: 0

    /** 当前保序操作已经激活的全部 native 尝试数；不包含准备或门禁拒绝。 */
    fun operationNativeWrites(): Int = operationContext.get()?.nativeWrites ?: 0

    fun finishWrite(ticket: HvacWriteTicket, accepted: Boolean, nativeMs: Long, detail: String) =
        HvacCommandTrace.finishWrite(ticket, accepted, nativeMs, detail)

    fun abortWrite(ticket: HvacWriteTicket, source: String) = HvacCommandTrace.abortWrite(ticket, source)

    fun skippedWrite(functionId: Int, zone: Int, value: Number, source: String) =
        HvacCommandTrace.skippedWrite(functionId, zone, value, source, operationContext.get())

    fun observe(functionId: Int, zone: Int, value: Number, source: String, observedAtMs: Long, connection: Long) =
        HvacCommandTrace.observe(functionId, zone, value, source, observedAtMs, connection)

    fun describeLastWrite(functionId: Int, zone: Int, sinceMs: Long): String =
        HvacCommandTrace.describeLastWrite(functionId, zone, sinceMs)

    fun recentOperations(sinceMs: Long): String = HvacCommandTrace.recentOperations(sinceMs)
    private val latestTargets = ConcurrentHashMap<String, Long>()

    /** 仅合并尚未执行的绝对目标；步进、翻转和组合动作不得走此入口。 */
    fun submitLatest(ctx: Context, key: String, sequence: Long, onSkipped: () -> Unit, action: () -> Unit) {
        val c = ctx.applicationContext
        val operation = HvacCommandTrace.operation("$key targetSeq=$sequence", VehicleBridge.connectionGeneration)
        if (!canSubmit(c, key)) {
            HvacCommandTrace.operationState(operation, "SKIPPED", "提交门禁拒绝，未调用厂商接口")
            onSkipped()
            return
        }
        latestTargets[key] = operation.commandId
        HvacCommandTrace.operationState(operation, "QUEUED")
        executor.execute {
            try {
                if (latestTargets[key] != operation.commandId) {
                    // 被替换的旧操作不撤销确认目标；新旧同值意图可能仍共享同一目标序号。
                    HvacCommandTrace.operationState(operation, "SKIPPED", "尚未执行的绝对目标已被替换")
                } else execute(c, operation, onSkipped, action)
            } finally {
                latestTargets.remove(key, operation.commandId)
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
        val operation = operationContext.get()
        if (operation != null && operation.connection != VehicleBridge.connectionGeneration) {
            Logx.w("HVAC_WRITE $label 未执行：操作所属连接已失效 commandId=${operation.commandId}")
            return false
        }
        return canSubmit(ctx, label)
    }
}
