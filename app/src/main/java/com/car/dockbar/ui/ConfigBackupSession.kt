package com.car.dockbar.ui

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.car.dockbar.config.BackupGroup
import com.car.dockbar.config.ConfigBackupException
import com.car.dockbar.config.ConfigBackupStore
import com.car.dockbar.util.Logx
import java.io.IOException
import java.util.concurrent.Executors

/** 同进程刷新通知，接收者只更新已经存在的页面或窗口。 */
internal object ConfigBackupUpdates {
    private val listeners = linkedSetOf<(Set<BackupGroup>) -> Unit>()

    fun add(listener: (Set<BackupGroup>) -> Unit) { listeners.add(listener) }
    fun remove(listener: (Set<BackupGroup>) -> Unit) { listeners.remove(listener) }

    fun publish(groups: Set<BackupGroup>) {
        if (groups.isEmpty()) return
        listeners.toList().forEach { listener ->
            runCatching { listener(groups) }.onFailure { Logx.e("配置已保存，刷新界面失败", it) }
        }
    }
}

internal class ConfigBackupSession(app: Application) : AndroidViewModel(app) {
    sealed interface State {
        data class Ready(val message: String? = null, val recoveryAvailable: Boolean = false) : State
        data class Busy(val message: String, val writingPreferences: Boolean) : State
        data class Review(
            val preview: ConfigBackupStore.Preview,
            val selected: MutableSet<BackupGroup> = preview.changes.keys.toMutableSet(),
        ) : State
    }

    private val store = ConfigBackupStore(app)
    private val resolver = app.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dock-config-backup") }
    private val current = MutableLiveData<State>(State.Ready(recoveryAvailable = store.hasRecovery()))
    val state: LiveData<State> = current
    val writingPreferences: Boolean get() = (current.value as? State.Busy)?.writingPreferences == true
    val exportGroups = BackupGroup.entries.toMutableSet()
    var pendingExport: Set<BackupGroup>? = null
    private var cleared = false

    fun read(uri: Uri) = submit("正在读取配置…", "无法读取文件，请重新选择。") {
        val stream = resolver.openInputStream(uri) ?: throw IOException()
        val preview = store.preview(stream)
        State.Review(preview)
    }

    fun write(uri: Uri?) {
        val groups = pendingExport
        pendingExport = null
        if (uri == null) {
            Logx.d("配置导出：文件选择器未返回保存位置")
            return
        }
        if (groups.isNullOrEmpty()) {
            Logx.w("配置导出：已收到保存位置，但没有待导出的配置组")
            current.value = State.Ready("请重新选择要导出的配置组。", store.hasRecovery())
            return
        }
        Logx.d("配置导出：已收到保存位置，正在生成 ${groups.size} 组配置")
        submit("正在导出配置…", "写入文件失败。目标文件可能不完整，请选择可写位置重新导出。") {
            try {
                val data = store.export(groups)
                Logx.d("配置导出：已生成 ${data.size} 字节，正在打开目标文件")
                // 与诊断 ZIP 一样用通用写入模式，兼容只支持 w 的文档提供器。
                val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("系统未提供文档写入流")
                stream.use {
                    Logx.d("配置导出：目标文件已打开，正在写入")
                    it.write(data)
                }
                Logx.d("配置导出：写入完成（${data.size} 字节）")
                State.Ready("配置已导出。", store.hasRecovery())
            } catch (e: Exception) {
                Logx.e("配置导出失败（${e.javaClass.simpleName}）")
                throw e
            }
        }
    }

    fun apply(review: State.Review) {
        val selected = review.selected.toSet()
        writePreferences("正在导入配置…", "无法保存，先检查本机存储空间。", false) {
            store.apply(review.preview, selected)
        }
    }

    fun restore() = writePreferences("正在恢复配置…", "无法读取或保存本机恢复点。", true, store::restore)

    fun cancelReview() { current.value = State.Ready(recoveryAvailable = store.hasRecovery()) }

    fun pickerFailed() { current.value = State.Ready("无法打开系统文件选择器。", store.hasRecovery()) }

    private fun writePreferences(progress: String, failure: String, restoring: Boolean, action: () -> Set<BackupGroup>) =
        submit(progress, failure, writingPreferences = true) {
            val changed = action()
            main.post { ConfigBackupUpdates.publish(changed) }
            val result = when {
                changed.isEmpty() -> "本机配置已是预览中的值。"
                restoring -> "已恢复导入前的本机配置。"
                else -> "已导入 ${changed.size} 组配置。"
            }
            State.Ready(result, store.hasRecovery())
        }

    private fun submit(progress: String, failure: String, writingPreferences: Boolean = false, action: () -> State) {
        if (current.value is State.Busy) return
        current.value = State.Busy(progress, writingPreferences)
        worker.execute {
            val result = try {
                action()
            } catch (e: ConfigBackupException) {
                State.Ready(e.message, store.hasRecovery())
            } catch (_: Exception) {
                State.Ready(failure, store.hasRecovery())
            }
            main.post { if (!cleared) current.value = result }
        }
    }

    override fun onCleared() {
        cleared = true
        // 已确认的写入和失败恢复应完成，文件任务只保留应用 Context。
        worker.shutdown()
    }
}
