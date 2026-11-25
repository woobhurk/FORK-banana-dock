package com.car.dockbar.ui

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R


/** 展示黑名单应用，添加和删除后立即更新隐藏规则。 */
internal class ResidentAppsPanel(
    private val activity: Activity,
    page: LinearLayout,
    private val host: LinearLayout,
    addButton: Button,
    private val detectionButton: Button
) {
    private val tiles = LinkedHashMap<String, SettingsAppTile>()
    private val selectedCount = text("", 21f).apply { typeface = AppFont.bold(activity) }
    private val systemCount = text("", 13f, true)
    private val personalCount = text("", 13f, true)
    private val systemBody = column()
    private val personalBody = column()
    private var currentPackages: List<String>? = null
    private var currentSystems = emptySet<String>()
    private var wasLoading = false
    private var searchQuery = ""
    private var rerender: (() -> Unit)? = null

    init {
        listOf(host, addButton, detectionButton).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        page.addView(text("隐藏 Dock 应用", 30f).apply { typeface = AppFont.bold(activity) })
        page.addView(text("列表中的应用在前台时隐藏 Dock，删除后恢复默认显示", 15f, true), lp(12))

        val status = card()
        page.addView(status, lp(18))
        val info = column()
        info.addView(selectedCount)
        info.addView(text("列表中均为隐藏黑名单应用", 14f, true), lp(12))
        info.addView(text("点击应用右上角 × 删除，修改立即生效", 13f, true), lp(8))
        addButton.text = "＋ 从已安装应用中选择"
        addButton.textSize = 16f
        addButton.minWidth = 0
        addButton.minimumWidth = 0
        addButton.background = ContextCompat.getDrawable(activity, R.drawable.bg_button_primary)
        addButton.setTextColor(color(R.color.settings_on_accent))
        detectionButton.textSize = 16f
        detectionButton.typeface = AppFont.bold(activity)
        detectionButton.minWidth = 0
        detectionButton.minimumWidth = 0
        detectionButton.background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_choice)
        detectionButton.contentDescription = "前台应用检测，点击管理检测权限"
        for (button in listOf(addButton, detectionButton)) {
            button.minHeight = dp(48)
            button.minimumHeight = dp(48)
            button.setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val actions = settingsColumns(activity, addButton, detectionButton, minWidthDp = 330)
        status.addView(settingsColumns(activity, info, actions, minWidthDp = 760, firstWeight = 0.65f), lp())

        page.addView(EditText(activity).apply {
            id = R.id.settingsResidentSearch
            hint = "搜索黑名单应用名称或包名（系统 / 个人）"
            textSize = 16f
            isSingleLine = true
            minHeight = dp(48)
            setBackgroundResource(R.drawable.bg_settings_input)
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(color(R.color.settings_text))
            setHintTextColor(color(R.color.settings_secondary))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty().trim()
                    rerender?.invoke()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }, lp(18))
        host.orientation = LinearLayout.VERTICAL
        host.removeAllViews()
        page.addView(host, lp(18))
        fun createGroup(title: String, description: String, count: TextView, body: LinearLayout, id: Int): View {
            val group = card()
            val heading = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(text(title, 21f).apply { typeface = AppFont.bold(activity) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            count.setTextColor(color(R.color.settings_accent))
            heading.addView(count)
            group.addView(heading)
            group.addView(text(description, 13f, true), lp(12))
            val maxHeight = dp(520)
            val scroll = object : ScrollView(activity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                        maxHeight
                    } else {
                        minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeight)
                    }
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
                }
            }.apply {
                this.id = id
                isNestedScrollingEnabled = true
                addView(body)
            }
            group.addView(scroll, lp(18))
            return group
        }
        val system = createGroup("系统应用", "系统预装与车机内置应用", systemCount, systemBody, R.id.settingsResidentSystemScroll)
        val personal = createGroup("个人应用", "自行安装的导航、音乐与其他应用", personalCount, personalBody, R.id.settingsResidentPersonalScroll)
        host.addView(settingsColumns(activity, system, personal, minWidthDp = 660), lp())
        selectedCount.id = R.id.settingsResidentSelectedCount
    }

    fun setDetection(enabled: Boolean) {
        detectionButton.text = if (enabled) "● 前台应用检测已开启" else "● 前台应用检测未开启"
        detectionButton.isSelected = enabled
        detectionButton.setTextColor(color(if (enabled) R.color.settings_service_running else R.color.settings_warning))
        detectionButton.contentDescription = "${detectionButton.text}，点击管理检测权限"
    }

    fun render(
        apps: List<ResidentAppItem>, blacklist: Set<String>, loading: Boolean,
        bindIcon: (String, ImageView) -> Unit,
        onRemove: (String) -> Unit
    ) {
        rerender = { render(apps, blacklist, loading, bindIcon, onRemove) }
        val visible = ResidentAppList.blacklistedApps(apps, blacklist, searchQuery)
        val packages = visible.map { it.pkg }
        val systems = visible.filter { it.system }.mapTo(HashSet()) { it.pkg }
        val rebuild = packages != currentPackages || systems != currentSystems || wasLoading != loading
        if (rebuild) {
            currentPackages = packages
            currentSystems = systems
            wasLoading = loading
            tiles.clear()
            fun populate(body: LinearLayout, list: List<ResidentAppItem>) {
                body.removeAllViews()
                if (list.isEmpty()) {
                    val message = when {
                        loading -> "正在读取黑名单应用…"
                        searchQuery.isBlank() -> "暂无黑名单应用"
                        else -> "没有匹配的黑名单应用"
                    }
                    body.addView(text(message, 14f, true).apply { gravity = Gravity.CENTER }, lp(30))
                    return
                }
                val grid = ResidentAppGrid(activity)
                body.addView(grid, lp())
                for (app in list) {
                    val tile = SettingsAppTile(activity, app.label).apply {
                        addRemoveButton(removeDescription(app)) { onRemove(app.pkg) }
                    }
                    tiles[app.pkg] = tile
                    bindIcon(app.pkg, tile.icon)
                    grid.addView(tile)
                }
            }
            val (systemApps, personalApps) = visible.partition { it.system }
            populate(systemBody, systemApps)
            populate(personalBody, personalApps)
        } else {
            // 后台补全应用名称时，仅更新标签，保留当前滚动位置。
            apps.forEach { app -> tiles[app.pkg]?.updateLabel(app.label, removeDescription(app)) }
        }
        selectedCount.text = if (loading) "正在读取黑名单应用…" else "黑名单共 ${apps.size} 个应用"
        val systemSize = visible.count { it.system }
        systemCount.text = "$systemSize 个应用"
        personalCount.text = "${visible.size - systemSize} 个应用"
    }

    private fun removeDescription(app: ResidentAppItem) = "从黑名单中删除 ${app.label}（${app.pkg}）"

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply {
        background = ContextCompat.getDrawable(activity, R.drawable.bg_card)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }
    private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        includeFontPadding = false
        setTextColor(color(if (secondary) R.color.settings_secondary else R.color.settings_text))
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
}

/** 每组按净宽排列卡片，末行沿用同样的列宽，不拉伸剩余卡片。 */
private class ResidentAppGrid(context: Context) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val gap = (8 * density).toInt()
    private val minTileWidth = (112 * density).toInt()
    private val tileHeight = (SettingsAppTile.HEIGHT_DP * density).toInt()
    private var columns = 1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        columns = ((width + gap) / (minTileWidth + gap)).coerceIn(1, 3)
        val cellWidth = (width - gap * (columns - 1)) / columns
        val widthSpec = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(tileHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) getChildAt(index).measure(widthSpec, heightSpec)
        val rows = (childCount + columns - 1) / columns
        val height = rows * tileHeight + (rows - 1).coerceAtLeast(0) * gap
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cellWidth = getChildAt(0)?.measuredWidth ?: return
        for (index in 0 until childCount) {
            val x = index % columns * (cellWidth + gap)
            val y = index / columns * (tileHeight + gap)
            getChildAt(index).layout(x, y, x + cellWidth, y + tileHeight)
        }
    }
}

