package com.car.dockbar.config

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.util.AtomicFile
import com.car.dockbar.config.ConfigBackupFormat.fail
import com.car.dockbar.model.AppDrawerApps
import com.car.dockbar.model.DockEntries
import com.car.dockbar.util.DOCK_HIDE_APPS_KEY
import com.car.dockbar.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class ConfigBackupStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = Prefs.sp(ctx)
    private val recovery = AtomicFile(File(ctx.noBackupFilesDir, "dock-config-before-import.json"))

    class Change internal constructor(val key: String, val before: Any?, val after: Any?, val title: String)
    class Preview internal constructor(
        val sourceAppVersion: String?,
        val changes: Map<BackupGroup, List<Change>>,
        val warnings: List<String>,
    )

    fun export(selected: Set<BackupGroup>): ByteArray = synchronized(Prefs.portableWriteLock) {
        val stored = prefs.all
        val groups = JSONObject()
        for (group in BackupGroup.entries) {
            if (group !in selected) continue
            val values = JSONObject()
            when (group) {
                BackupGroup.LAYOUT -> {
                    val entries = if ((stored[LAYOUT_KEY] as? String).isNullOrBlank()) JSONObject.NULL else
                        JSONArray().apply { DockEntries.load(ctx).forEach { put(it.toJson()) } }
                    values.put("entries", entries)
                }
                BackupGroup.QUICK_APPS -> values.put("slots", JSONArray().apply {
                    AppDrawerApps.loadSlots(ctx).forEach { put(it ?: JSONObject.NULL) }
                })
                BackupGroup.VISIBILITY -> if (stored.containsKey(DOCK_HIDE_APPS_KEY)) {
                    values.put("hiddenPackages", JSONArray(Prefs.dockHideApps(ctx)))
                }
                else -> Unit
            }
            for (field in ConfigBackupFormat.fields[group].orEmpty()) {
                val saved = stored[field.key]?.takeIf { it::class == field.default::class }
                val value = saved?.let(field.encode)?.takeIf { field.parse(it) != null } ?: field.encode(field.default)
                values.put(field.name, value)
            }
            groups.put(group.id, values)
        }
        JSONObject()
            .put("format", ConfigBackupFormat.ID)
            .put("schemaVersion", ConfigBackupFormat.VERSION)
            .put("sourceAppVersion", Prefs.appVersion(ctx))
            .put("groups", groups)
            .toString(2).toByteArray(Charsets.UTF_8)
            .also { if (it.size > ConfigBackupFormat.MAX_BYTES) ConfigBackupFormat.tooLarge() }
    }

    fun preview(input: InputStream): Preview {
        val (source, groups) = ConfigBackupFormat.readGroups(input)
        val stored = synchronized(Prefs.portableWriteLock) { prefs.all }
        val collections = BackupCollections(::appAvailable)
        val changes = linkedMapOf<BackupGroup, List<Change>>()
        for (group in BackupGroup.entries) {
            if (!groups.has(group.id)) continue
            val values = groups.optJSONObject(group.id) ?: fail("「${group.title}」配置必须是对象。")
            val changed = mutableListOf<Change>()
            fun add(key: String, value: Any?, title: String) {
                val previous = stored[key]
                if (previous != value) changed.add(Change(key, previous, value, title))
            }
            when (group) {
                BackupGroup.LAYOUT -> if (values.has("entries")) {
                    if (values.isNull("entries")) {
                        add(LAYOUT_KEY, null, "跟随目标版本默认布局")
                    } else collections.layout(values.get("entries"))?.let { entries ->
                        val count = entries.length()
                        val title = if (count == 0) "清空 Dock 布局" else "替换 Dock 布局（$count 项）"
                        add(LAYOUT_KEY, entries.toString(), title)
                    }
                }
                BackupGroup.QUICK_APPS -> if (values.has("slots")) {
                    add(QUICK_APPS_KEY, collections.quickApps(values.get("slots")), "替换全部六个槽位（保留空位）")
                }
                BackupGroup.VISIBILITY -> if (values.has("hiddenPackages") && !values.isNull("hiddenPackages")) {
                    val hidden = collections.hiddenPackages(values.get("hiddenPackages"))
                    add(DOCK_HIDE_APPS_KEY, hidden, if (hidden.isEmpty()) "清空隐藏 Dock 应用名单" else "替换隐藏 Dock 应用名单")
                }
                else -> Unit
            }
            for (field in ConfigBackupFormat.fields[group].orEmpty()) {
                if (!values.has(field.name)) continue
                val value = field.parse(values.get(field.name)) ?: fail("「${field.title}」的类型或数值超出支持范围。")
                add(field.key, value, field.title)
            }
            if (changed.isNotEmpty()) changes[group] = changed
        }
        for (group in groups.keys()) {
            if (BackupGroup.entries.none { it.id == group }) collections.warnings.add("已跳过未支持的配置组 ${group.take(80)}。")
        }
        return Preview(source, changes, collections.warnings.toList())
    }

    fun hasRecovery(): Boolean = recovery.baseFile.exists() || File(recovery.baseFile.path + ".bak").exists()

    fun apply(preview: Preview, selected: Set<BackupGroup>): Set<BackupGroup> = synchronized(Prefs.portableWriteLock) {
        if (hasRecovery()) fail("存在待恢复的本机配置，请先恢复后再导入。")
        val selectedChanges = preview.changes.filterKeys { it in selected }
        val changes = selectedChanges.values.flatten()
        if (changes.isEmpty()) return@synchronized emptySet()
        val before = changes.associate { it.key to it.before }
        val stored = prefs.all
        if (changes.any { stored[it.key] != it.before }) fail("本机配置在预览后已变化，请重新选择文件。")
        saveRecovery(before)
        if (commit(changes.associate { it.key to it.after })) {
            recovery.delete()
            return@synchronized selectedChanges.keys
        }
        // commit() 失败时进程内可能已经看到新值，仍需把旧值提交回去。
        if (commit(before)) {
            recovery.delete()
            fail("写入配置失败，已还原导入前的配置。")
        }
        fail("写入和还原均失败。已保留本机恢复点，释放存储空间后可重试恢复。")
    }

    fun restore(): Set<BackupGroup> = synchronized(Prefs.portableWriteLock) {
        val saved = try {
            recovery.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } catch (_: Exception) {
            fail("无法读取本机恢复点。")
        }
        val values = linkedMapOf<String, Any?>()
        val groups = linkedSetOf<BackupGroup>()
        for (key in saved.keys()) {
            val field = fieldsByKey[key]
            val group = field?.group ?: when (key) {
                LAYOUT_KEY -> BackupGroup.LAYOUT
                QUICK_APPS_KEY -> BackupGroup.QUICK_APPS
                DOCK_HIDE_APPS_KEY -> BackupGroup.VISIBILITY
                else -> fail("恢复点包含不受支持的设置。")
            }
            val value = if (saved.isNull(key)) null else when (field?.default) {
                is Float -> saved.getString(key).toFloat()
                is Long -> saved.getLong(key)
                is Int -> saved.getInt(key)
                is Boolean -> saved.getBoolean(key)
                else -> saved.getString(key)
            }
            values[key] = value
            groups.add(group)
        }
        if (!commit(values)) fail("恢复写入失败，已保留本机恢复点。")
        recovery.delete()
        groups
    }

    private fun saveRecovery(values: Map<String, Any?>) {
        val snapshot = JSONObject()
        for ((key, value) in values) snapshot.put(key, when (value) {
            is Float -> value.toString()
            else -> value ?: JSONObject.NULL
        })
        var stream: java.io.FileOutputStream? = null
        try {
            stream = recovery.startWrite()
            stream.write(snapshot.toString().toByteArray(Charsets.UTF_8))
            // AtomicFile.finishWrite 会记录同步失败但不向调用方返回结果。
            stream.fd.sync()
            recovery.finishWrite(stream)
            if (!recovery.baseFile.isFile) throw IOException("恢复点未落盘")
        } catch (_: IOException) {
            recovery.failWrite(stream)
            fail("无法保存导入前的恢复点，请检查存储空间。")
        }
    }

    private fun commit(values: Map<String, Any?>): Boolean = try {
        val editor = prefs.edit()
        for ((key, value) in values) put(editor, key, value)
        editor.commit()
    } catch (_: RuntimeException) {
        false
    }

    private fun appAvailable(pkg: String, activity: String): Boolean {
        val pm = ctx.packageManager
        return try {
            if (activity.isEmpty()) pm.getLaunchIntentForPackage(pkg) != null else {
                val name = if (activity.startsWith('.')) pkg + activity else activity
                val info = pm.getActivityInfo(ComponentName(pkg, name), 0)
                info.enabled && info.applicationInfo.enabled && (info.exported || pkg == ctx.packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private companion object {
        const val LAYOUT_KEY = "dock_entries_json"
        const val QUICK_APPS_KEY = "app_drawer_apps_json"
        val fieldsByKey = ConfigBackupFormat.fields.values.flatten().associateBy(ConfigBackupFormat.Field::key)

        fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                else -> error("不支持的配置类型")
            }
        }
    }
}
