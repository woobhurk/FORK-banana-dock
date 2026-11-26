package com.car.dockbar.util

import android.content.SharedPreferences

internal const val DOCK_HIDE_APPS_KEY = "dock_hide_apps"

/** 负责当前黑名单的归一化读写；未保存时使用当前安装默认值。 */
internal object DockBlacklistPreferences {
    fun read(prefs: SharedPreferences): Set<String> = synchronized(prefs) {
        val raw = prefs.getString(DOCK_HIDE_APPS_KEY, null)
        if (raw == null) DockBlacklistDefaults.packages else parse(raw)
    }

    fun write(prefs: SharedPreferences, packages: Set<String>) = synchronized(prefs) {
        val normalized = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs.edit().putString(DOCK_HIDE_APPS_KEY, normalized.joinToString(",")).apply()
    }

    private fun parse(raw: String?): Set<String> =
        raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
