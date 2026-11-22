package com.car.dockbar.dock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.car.dockbar.util.Logx

/** oneOS d2 原厂 Dock 的单次显隐请求。回执只表示 Binder 返回，不代表屏幕状态。 */
internal class OriginalDockRequest(
    context: Context,
    private val visible: Boolean,
    private val canHide: () -> Boolean,
    private val onResult: (error: String?) -> Unit
) : ServiceConnection {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val bindTimeout = Runnable { finish("连接原厂 Dock 服务超时") }
    private var binding = false
    private var connected = false
    @Volatile private var closed = false

    fun start() {
        val intent = Intent().setClassName("com.geely.service.oneosapi", "com.geely.service.oneosapi.OneOSApiService")
        binding = true
        try {
            if (app.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                main.postDelayed(bindTimeout, 4000)
            } else {
                finish("无法绑定 oneOS 服务，当前车机可能不支持")
            }
        } catch (e: SecurityException) {
            Logx.e("OEM_DOCK：绑定被拒绝", e)
            finish("车机拒绝绑定 oneOS 服务")
        } catch (e: RuntimeException) {
            Logx.e("OEM_DOCK：绑定异常", e)
            finish("绑定 oneOS 服务失败（${e.javaClass.simpleName}）")
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        if (closed || connected) return
        connected = true
        main.removeCallbacks(bindTimeout)
        Thread({
            val error = try {
                request(service)
            } catch (e: SecurityException) {
                Logx.e("OEM_DOCK：调用被拒绝", e)
                "车机拒绝当前应用控制原厂 Dock"
            } catch (e: Exception) {
                Logx.e("OEM_DOCK：调用异常", e)
                "原厂 Dock 请求失败（${e.javaClass.simpleName}）"
            }
            main.post { finish(error) }
        }, "oneos-dock").start()
    }

    override fun onNullBinding(name: ComponentName) = finish("oneOS 服务未开放绑定")

    override fun onServiceDisconnected(name: ComponentName) {
        if (!connected) finish("oneOS 服务连接已断开")
    }

    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(bindTimeout)
        if (binding) runCatching { app.unbindService(this) }
    }

    private fun finish(error: String?) {
        if (closed) return
        close()
        onResult(error)
    }

    private fun request(manager: IBinder): String? {
        if (closed) return "原厂 Dock 请求已取消"
        val dock = manager.callInt("com.geely.lib.oneosapi.IServiceManager", 2, 21) { readStrongBinder() }
            ?: return "oneOS 未提供原厂 Dock 服务（21）"
        // 连接期间本应用窗口可能已收起；检查期间也可能收到取消。
        if (closed || (!visible && (!canHide() || closed))) return "未满足隐藏条件，未请求隐藏原厂 Dock"
        // 用普通 showDock，让厂商保留 360 全景等场景下的显隐判断。
        dock.callInt("com.geely.lib.oneosapi.systemui.IDockBarService", 1, if (visible) 1 else 3) { Unit }
            ?: return "原厂 Dock 请求未返回有效回执"
        return null
    }

    private inline fun <T> IBinder.callInt(descriptor: String, code: Int, value: Int, read: Parcel.() -> T): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.writeInt(value)
            if (!transact(code, data, reply, 0) || reply.dataAvail() < Int.SIZE_BYTES) return null
            reply.readException()
            return reply.read()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
