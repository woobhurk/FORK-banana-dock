package com.car.dockbar.model

import android.content.Context
import com.car.dockbar.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** 快捷应用按六个固定槽位保存包名，空位以 null 表示。 */
object AppDrawerApps {
    const val MAX_APPS = 6

    /** 已有对象中的显示名称和入口不参与读取。 */
    fun loadSlots(ctx: Context): List<String?> {
        val raw = Prefs.appDrawerAppsJson(ctx)
        if (raw.isBlank()) return List(MAX_APPS) { null }
        return runCatching {
            val array = JSONArray(raw)
            val seen = HashSet<String>()
            List(MAX_APPS) { i ->
                val pkg = (array.optJSONObject(i)?.opt("package") as? String)?.trim().orEmpty()
                pkg.takeIf { it.isNotEmpty() && seen.add(it) }
            }
        }.getOrElse { List(MAX_APPS) { null } }
    }

    /** 批量选择只覆盖列表中可见的包名，缺装应用留在原槽。 */
    fun saveSelection(ctx: Context, available: Set<String>, selected: Set<String>) = synchronized(Prefs.portableWriteLock) {
        val slots = loadSlots(ctx).toMutableList()
        for (i in slots.indices) {
            val pkg = slots[i]
            if (pkg != null && pkg in available && pkg !in selected) slots[i] = null
        }
        for (pkg in selected) {
            if (pkg !in available || pkg in slots) continue
            val free = slots.indexOf(null)
            if (free < 0) break
            slots[free] = pkg
        }
        saveSlots(ctx, slots)
    }

    fun saveSlots(ctx: Context, slots: List<String?>) =
        Prefs.setAppDrawerAppsJson(ctx, encodeSlots(slots).toString())

    internal fun encodeSlots(slots: List<String?>): JSONArray {
        val array = JSONArray()
        val seen = HashSet<String>()
        repeat(MAX_APPS) { i ->
            val pkg = slots.getOrNull(i)
            array.put(if (pkg != null && seen.add(pkg)) JSONObject().put("package", pkg) else JSONObject.NULL)
        }
        return array
    }
}
