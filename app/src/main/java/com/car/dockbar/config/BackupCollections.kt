package com.car.dockbar.config

import com.car.dockbar.config.ConfigBackupFormat.fail
import com.car.dockbar.config.ConfigBackupFormat.integer
import com.car.dockbar.dock.DockAction
import com.car.dockbar.dock.DockActionCodec
import com.car.dockbar.dock.DockCatalog
import com.car.dockbar.dock.GestureMaps
import com.car.dockbar.dock.GesturePatch
import com.car.dockbar.dock.widgets.AppWidget
import com.car.dockbar.model.AppDrawerApps
import com.car.dockbar.model.DockEntry
import org.json.JSONArray
import org.json.JSONObject

/** 文件中的集合先校验和规范化，再交给现行的布局和快捷槽读取器。 */
internal class BackupCollections(private val available: (String, String) -> Boolean) {
    val warnings = linkedSetOf<String>()

    fun layout(value: Any): JSONArray? {
        val entries = value as? JSONArray ?: fail("Dock 布局必须是条目数组或默认值。")
        val ids = HashSet<String>()
        val builtins = HashSet<String>()
        val result = JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: fail("Dock 布局第 ${i + 1} 项必须是对象。")
            val id = text(item, "id", "条目标识", 160)
            if (!ids.add(id)) fail("Dock 布局第 ${i + 1} 项的标识重复。")
            val kind = item.opt("kind") as? String ?: fail("Dock 布局第 ${i + 1} 项的类型无效。")
            val action = text(item, "action", "控件或应用标识", 256)
            val widget = if (kind == "builtin") DockCatalog.of(action) else null
            val activity = when (kind) {
                "app" -> activity(item, "cls").also { checkApp(packageName(action), it) }
                "builtin" -> {
                    if (widget == null) {
                        warnings.add("已跳过不支持的控件 $action。")
                        continue
                    }
                    if (!builtins.add(action)) fail("Dock 布局包含重复的内置控件 $action。")
                    ""
                }
                else -> fail("Dock 布局第 ${i + 1} 项的类型无效。")
            }
            val label = label(item, widget?.spec?.label ?: action)
            val displayName = label.lineSequence().first().take(80).ifBlank { action }
            val span = if (item.has("span")) {
                integer(item.opt("span"))?.takeIf { it in 1..6 }?.toInt()
                    ?: fail("Dock 条目「$displayName」的槽宽须为 1 到 6。")
            } else widget?.spec?.span ?: 1
            val patch = if (item.has("gestures")) {
                gestures(item, displayName, (widget ?: AppWidget).defaultGestures().keys)
            } else null
            result.put(DockEntry(id, kind, action, label, activity, span, patch).toJson())
        }
        if (entries.length() > 0 && result.length() == 0) {
            warnings.add("文件中的 Dock 控件均不受支持，保留本机布局。")
            return null
        }
        return result
    }

    fun quickApps(value: Any): String {
        val entries = value as? JSONArray ?: fail("快捷应用必须是槽位数组。")
        if (entries.length() != AppDrawerApps.MAX_APPS) fail("快捷应用必须包含 ${AppDrawerApps.MAX_APPS} 个槽位（含空位）。")
        val seen = HashSet<String>()
        val slots = List(entries.length()) { index ->
            if (entries.isNull(index)) null else {
                val slot = entries.optJSONObject(index) ?: fail("快捷应用第 ${index + 1} 槽必须是包名对象或空位。")
                val pkg = packageName(slot.opt("package"))
                if (!seen.add(pkg)) fail("快捷应用包含重复的包名 $pkg。")
                checkApp(pkg, "")
                pkg
            }
        }
        return AppDrawerApps.encodeSlots(slots).toString()
    }

    fun hiddenPackages(value: Any): String {
        val array = value as? JSONArray ?: fail("隐藏 Dock 应用名单必须是包名数组。")
        val names = linkedSetOf<String>()
        for (i in 0 until array.length()) names.add(packageName(array.get(i)))
        return names.joinToString(",")
    }

    private fun gestures(item: JSONObject, label: String, targets: Set<String>): GesturePatch? {
        val patch = item.optJSONObject("gestures") ?: fail("Dock 条目「$label」的手势无效。")
        val normalized = JSONObject()
        for (target in patch.keys()) {
            if (target !in targets) {
                warnings.add("已跳过「$label」不支持的触控区域 ${target.take(80)}。")
                continue
            }
            val slots = patch.optJSONObject(target) ?: fail("Dock 条目「$label」的触控区域无效。")
            val gestures = JSONObject()
            for (gesture in listOf("tap", "longPress")) {
                if (!slots.has(gesture)) continue
                val value = slots.get(gesture)
                if (value === JSONObject.NULL) {
                    gestures.put(gesture, JSONObject.NULL)
                    continue
                }
                val actionObject = value as? JSONObject ?: fail("Dock 条目「$label」的手势动作无效。")
                val type = text(actionObject, "type", "手势动作类型", 80)
                val action = DockActionCodec.fromJson(actionObject)
                if (action == null || !supported(action)) {
                    warnings.add("已禁用「$label」不支持的手势动作 $type。")
                    gestures.put(gesture, JSONObject.NULL)
                } else {
                    gestures.put(gesture, DockActionCodec.toJson(action))
                }
            }
            if (gestures.length() > 0) normalized.put(target, gestures)
        }
        return if (normalized.length() > 0) GestureMaps.parsePatch(normalized) else null
    }

    private fun supported(action: DockAction): Boolean = when (action) {
        is DockAction.ShowPopup -> action.popup in popupIds
        is DockAction.TempStep -> action.delta == -1 || action.delta == 1
        is DockAction.FanStep -> action.delta == -1 || action.delta == 1
        is DockAction.LevelBump -> action.channel in levelChannels && (action.direction == "up" || action.direction == "down")
        else -> true
    }

    private fun checkApp(pkg: String, activity: String) {
        if (!available(pkg, activity)) {
            val name = if (activity.isEmpty()) pkg else "$pkg / $activity"
            warnings.add("本机无法启动 $name，已保留位置。")
        }
    }

    private fun label(item: JSONObject, fallback: String): String {
        if (!item.has("label")) return fallback
        val name = item.opt("label") as? String ?: fail("配置中的应用名称或控件名称无效。")
        return name.ifBlank { fallback }
    }

    private fun activity(item: JSONObject, key: String): String = optionalText(item, key, "应用 Activity", 512).also {
        if (it.isNotEmpty() && !className.matches(it)) fail("配置中的应用 Activity 格式无效。")
    }

    private fun packageName(value: Any?): String = (value as? String)
        ?.takeIf { it.length <= 256 && packageName.matches(it) }
        ?: fail("配置中的应用包名无效。")

    private fun text(item: JSONObject, key: String, title: String, maxLength: Int): String =
        (item.opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= maxLength && it.none(Char::isISOControl) }
            ?: fail("配置中的$title 无效。")

    private fun optionalText(item: JSONObject, key: String, title: String, maxLength: Int): String =
        if (!item.has(key) || item.opt(key) == "") "" else text(item, key, title, maxLength)

    private companion object {
        val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        val className = Regex("\\.?[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*")
        val popupIds = setOf("volume", "appDrawer", "temperatureDriver", "temperaturePassenger", "airDirection", "seat", "seatClimate")
        val levelChannels = setOf("seatHeat", "seatVent", "steeringHeat")
    }
}
