package com.car.dockbar.ui

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup

/**
 * 供 MainActivity 双栏容器使用的有序普通页面注册表。
 * 各页面自行管理控件与生命周期；宿主只负责页面选择和事件分发。
 */
internal class SettingsPageRegistry(
    private val content: ViewGroup,
    private val factories: Map<String, () -> SettingsPage>,
) {
    private val created = mutableMapOf<String, SettingsPage>()
    private var current: SettingsPage? = null

    fun requirePage(key: String): SettingsPage = created.getOrPut(key) {
        factories.getValue(key).invoke().also { page ->
            page.root.visibility = View.GONE
            page.create()
            page.buildLayout()
            content.addView(page.root)
        }
    }

    fun createdPage(key: String): SettingsPage? = created[key]

    fun show(key: String): SettingsPage {
        val next = requirePage(if (key in factories) key else MainActivity.SECTION_OVERLAY)
        if (next !== current) current?.root?.visibility = View.GONE
        next.root.visibility = View.VISIBLE
        current = next
        next.onShown()
        return next
    }

    fun onResume() {
        current?.onResume()
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        created.values.forEach { it.onConfigurationChanged(newConfig) }
    }

    fun onDestroy() {
        created.values.forEach { it.onDestroy() }
        created.clear()
        current = null
    }
}
