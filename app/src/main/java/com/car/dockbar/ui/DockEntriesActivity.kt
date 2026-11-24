package com.car.dockbar.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.dock.DockCatalog
import com.car.dockbar.model.DockEditorOrder
import com.car.dockbar.model.DockEntries
import com.car.dockbar.model.DockEntry
import com.car.dockbar.util.Logx
import java.util.concurrent.Executors

/** 条目编辑器只用拖动增删和排序，不接管实际 Dock 或快捷应用弹窗的交互。 */
class DockEntriesActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(MainActivity.scaledContext(newBase, previousScale = 1f))
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val entries = mutableListOf<DockEntry>()
    private val icons = HashMap<String, Drawable>()
    private var catalog = emptyList<DockEntry>()
    private var catalogLoaded = false
    private var systemPackages = emptySet<String>()
    private var systemCategory = false
    private var catalogLoading = false
    private var showApps = false
    private var query = ""
    private var drag: DockEditorDrag? = null
    private var committed = false
    private var destroyed = false
    private lateinit var root: FrameLayout
    private lateinit var summary: TextView
    private lateinit var poolTitle: TextView
    private lateinit var poolHint: TextView
    private lateinit var appButton: Button
    private lateinit var search: EditText
    private lateinit var pool: LinearLayout
    private lateinit var candidateScroll: ScrollView
    private lateinit var appTabs: LinearLayout
    private lateinit var personalTab: Button
    private lateinit var systemTab: Button
    private lateinit var preview: DockEditorPreviewView
    private lateinit var dragHint: TextView
    private lateinit var arrangePreview: () -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showApps = savedInstanceState?.getBoolean("editor_apps", false) ?: false
        systemCategory = savedInstanceState?.getBoolean("editor_system_apps", false) ?: false
        entries.addAll(DockEntries.load(this))
        root = FrameLayout(this).apply { setBackgroundColor(color(R.color.settings_page)) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val workspace = column()
        root.addView(workspace, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = DOCK_CLEARANCE_PX })
        workspace.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val page = column().apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        scroll.addView(page)
        setContentView(root)

        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹") { finish() }.apply {
            textSize = 30f
            setPadding(0, 0, 0, 0)
            contentDescription = "返回"
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        val titles = column()
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titles.addView(label("Dock 图标管理", 30f, bold = true), lp())
        summary = label("", 14f, true)
        titles.addView(summary, lp(12))
        appButton = button("＋ 添加第三方应用") {
            showApps = !showApps
            renderPool()
            if (showApps) loadApps()
        }
        val actions = row()
        actions.addView(appButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        actions.addView(button("恢复默认") { confirmReset() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        page.addView(settingsColumns(this, header, actions, minWidthDp = 640), lp())

        val available = card(page)
        poolTitle = label("可添加控件", 21f, bold = true)
        available.addView(poolTitle)
        poolHint = label("", 13f, true)
        available.addView(poolHint, lp(12))
        search = EditText(this).apply {
            hint = "搜索应用名称或包名"
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
                    query = s?.toString().orEmpty()
                    renderPool()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        available.addView(search, lp(12))
        appTabs = row()
        personalTab = button("个人应用") { systemCategory = false; renderPool() }
        systemTab = button("系统应用") { systemCategory = true; renderPool() }
        personalTab.setBackgroundResource(R.drawable.bg_settings_choice)
        systemTab.setBackgroundResource(R.drawable.bg_settings_choice)
        appTabs.addView(personalTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        appTabs.addView(systemTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(12) })
        available.addView(appTabs, lp(10))
        candidateScroll = ScrollView(this).apply { isNestedScrollingEnabled = true }
        pool = column()
        candidateScroll.addView(pool)
        available.addView(candidateScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            candidateHeight()).apply { topMargin = dp(12) })

        val edit = card(page)
        preview = DockEditorPreviewView(this)
        val previewHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val previewTitle = label("Dock 预览", 21f, bold = true)
        fun backdropLabel() = "模拟前台：${if (preview.lightBackdrop) "亮底" else "暗底"}"
        val backdrop = button(backdropLabel()) { control ->
            preview.lightBackdrop = !preview.lightBackdrop
            control.isSelected = preview.lightBackdrop
            control.text = backdropLabel()
            render()
        }.apply {
            setBackgroundResource(R.drawable.bg_settings_choice)
            isSelected = preview.lightBackdrop
        }
        previewHeader.addView(previewTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewHeader.addView(backdrop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        edit.addView(previewHeader, lp())
        val reorderHint = label("长按图标拖动排序；拖出预览并松手移除，内置控件会回到上方", 13f, true)
        edit.addView(reorderHint, lp(12))
        edit.addView(preview, lp())
        dragHint = label("长按上方控件拖入预览添加，第三方应用通过页面顶部单独选择", 13f, true)
        edit.addView(dragHint, lp(10))
        arrangePreview = {
            // 矮屏滚动候选时，放置目标始终留在同一屏。
            val compact = resources.configuration.screenHeightDp in 1..560
            val nextParent = if (compact) workspace else page
            if (edit.parent !== nextParent) {
                (edit.parent as ViewGroup).removeView(edit)
                nextParent.addView(edit, lp(if (compact) 0 else 18))
            }
            val padding = dp(if (compact) 8 else 20)
            edit.setPadding(padding, padding, padding, padding)
            reorderHint.visibility = if (compact) View.GONE else View.VISIBLE
            preview.layoutParams = preview.layoutParams.apply {
                height = dp(if (compact) 80 else 154)
                (this as LinearLayout.LayoutParams).topMargin = dp(if (compact) 4 else 18)
            }
        }
        arrangePreview()

        preview.onPreviewLongPress = { index, shadow ->
            entries.getOrNull(index)?.let { startDrag(preview, DockEditorDrag(it, true), shadow) }
        }
        preview.onHover = { item, inside -> updateDragHint(item, inside) }
        preview.onDrop = { item, gap -> completeDrop(item, gap) }
        root.setOnDragListener { _, event ->
            val item = event.localState as? DockEditorDrag
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> item != null
                DragEvent.ACTION_DRAG_LOCATION -> { if (item != null) updateDragHint(item, false); item != null }
                DragEvent.ACTION_DROP -> { if (item != null) completeDrop(item, null); item != null }
                DragEvent.ACTION_DRAG_ENDED -> {
                    drag = null
                    root.parent?.requestDisallowInterceptTouchEvent(false)
                    render()
                    true
                }
                else -> item != null
            }
        }
        render()
        if (showApps) loadApps()
    }

    private fun render() {
        if (destroyed) return
        summary.text = "共 ${entries.size} 项 · 长按拖入添加，预览内排序，拖出松手移除"
        dragHint.text = "松手后自动保存 · 编辑预览仅用于排列"
        dragHint.setTextColor(color(R.color.settings_secondary))
        renderPool()
        preview.render(entries)
    }

    private fun renderPool() {
        if (!::pool.isInitialized || destroyed) return
        appButton.text = if (showApps) "返回内置控件" else "＋ 添加第三方应用"
        appButton.setBackgroundResource(if (showApps) R.drawable.bg_settings_secondary_button else R.drawable.bg_button_primary)
        if (showApps) {
            appButton.setTextColor(ContextCompat.getColorStateList(this, R.color.settings_action_text))
        } else {
            appButton.setTextColor(color(R.color.settings_on_accent))
        }
        search.visibility = if (showApps) View.VISIBLE else View.GONE
        appTabs.visibility = if (showApps) View.VISIBLE else View.GONE
        val candidateHeight = candidateHeight()
        if (candidateScroll.layoutParams.height != candidateHeight) {
            candidateScroll.layoutParams = candidateScroll.layoutParams.apply { height = candidateHeight }
        }
        val addedApps = entries.filter { it.kind == "app" }.mapTo(HashSet()) { it.action }
        val availableApps = catalog.filter { it.action !in addedApps && (query.isBlank() ||
            it.label.contains(query, true) || it.action.contains(query, true)) }
        val systemCount = availableApps.count { it.action in systemPackages }
        personalTab.text = "个人应用（${availableApps.size - systemCount}）"
        systemTab.text = "系统应用（$systemCount）"
        listOf(personalTab to !systemCategory, systemTab to systemCategory).forEach { (tab, selected) ->
            tab.isSelected = selected
            tab.setTextColor(color(if (selected) R.color.settings_accent else R.color.settings_secondary))
        }
        val candidates = if (showApps) {
            availableApps.filter { (it.action in systemPackages) == systemCategory }
        } else {
            val added = entries.filter { it.kind == "builtin" }.mapTo(HashSet()) { it.action }
            DockCatalog.builtins()
                .filter { !it.spec.hidden && it.spec.action !in added }
                .map { widget ->
                    DockEntry("b_${widget.spec.action}", "builtin", widget.spec.action, widget.spec.label, span = widget.spec.span)
                }
        }
        poolTitle.text = if (showApps) "添加应用 · ${if (systemCategory) "系统应用" else "个人应用"}" else "可添加控件 · ${candidates.size} 项"
        poolHint.text = when {
            showApps && !catalogLoaded -> "正在读取可启动应用…"
            showApps -> "长按应用拖入下方预览；移除只取消入口，不会卸载应用"
            else -> "仅显示尚未添加的内置图标，长按拖入下方 Dock 预览"
        }
        pool.removeAllViews()
        if (candidates.isEmpty()) {
            pool.addView(label(if (showApps && !catalogLoaded) "正在加载…" else "没有可添加的项目", 14f, true), lp(24))
            return
        }
        val contentWidth = resources.displayMetrics.widthPixels - dp(80)
        val columns = if (showApps) (contentWidth / dp(104)).coerceIn(2, 6) else
            (contentWidth / dp(140)).coerceIn(2, 8)
        val iconWidthLimit = (contentWidth / columns - dp(22)).coerceAtLeast(dp(24))
        val grid = GridLayout(this).apply { columnCount = columns }
        pool.addView(grid, lp())
        candidates.forEachIndexed { index, entry ->
            val tile = column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setBackgroundResource(R.drawable.bg_settings_choice)
                isClickable = true
                isFocusable = true
                contentDescription = "可添加：${entry.label}，长按拖入 Dock"
                setOnClickListener { toast("请长按图标拖入下方 Dock 预览") }
            }
            DockDragLongPress.attach(tile) { _, _ ->
                startDrag(tile, DockEditorDrag(entry, false), View.DragShadowBuilder(tile))
            }
            val icon = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val iconWidth = if (entry.kind == "builtin") {
                val image = preview.candidateBitmap(entry)
                icon.setImageBitmap(image)
                (44f * image.width / image.height.coerceAtLeast(1)).toInt().coerceIn(44, 120)
            } else {
                bindIcon(icon, entry)
                56
            }
            tile.addView(icon, LinearLayout.LayoutParams(minOf(dp(iconWidth), iconWidthLimit), dp(if (showApps) 56 else 44)))
            tile.addView(label(entry.label, if (showApps) 16f else 14f).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, lp(8))
            tile.addView(label("长按拖入 Dock", 11f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(color(R.color.settings_accent))
            }, lp(5))
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(if (showApps) 140 else 116)
                columnSpec = GridLayout.spec(index % columns, 1f)
                rowSpec = GridLayout.spec(index / columns)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
        // 末行仍占满全部列，避免只剩一两个候选时图块被拉宽。
        val remainder = candidates.size % columns
        if (remainder != 0) repeat(columns - remainder) { offset ->
            grid.addView(View(this), GridLayout.LayoutParams().apply {
                width = 0
                height = dp(if (showApps) 140 else 116)
                columnSpec = GridLayout.spec(remainder + offset, 1f)
                rowSpec = GridLayout.spec(candidates.size / columns)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
    }

    private fun startDrag(view: View, item: DockEditorDrag, shadow: View.DragShadowBuilder) {
        if (drag != null) return
        drag = item
        committed = false
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        view.parent?.requestDisallowInterceptTouchEvent(true)
        updateDragHint(item, false)
        Logx.d("Dock 编辑：长按开始拖动，${item.entry.label}")
        val started = view.startDragAndDrop(ClipData.newPlainText("dock-entry", item.entry.id), shadow, item, 0)
        if (!started) {
            drag = null
            view.parent?.requestDisallowInterceptTouchEvent(false)
            render()
        }
    }

    private fun updateDragHint(item: DockEditorDrag, inside: Boolean) {
        dragHint.text = when {
            inside && item.fromPreview -> "松手插入标记位置：${item.entry.label}"
            inside -> "松手添加：${item.entry.label}"
            item.fromPreview -> "松手移除：${item.entry.label}"
            else -> "拖入下方 Dock 预览添加；在预览外松手取消"
        }
        dragHint.setTextColor(color(if (!inside && item.fromPreview) R.color.settings_error else R.color.settings_accent))
    }

    private fun completeDrop(item: DockEditorDrag, gap: Int?) {
        if (committed) return
        committed = true
        val before = entries.toList()
        val from = entries.indexOfFirst { it.id == item.entry.id }
        val next = when {
            gap == null && item.fromPreview && from >= 0 -> entries.filterIndexed { i, _ -> i != from }
            gap == null -> before
            item.fromPreview && from >= 0 -> DockEditorOrder.move(before, from, gap)
            item.fromPreview -> before
            item.entry.kind == "builtin" && entries.any { it.kind == "builtin" && it.action == item.entry.action } -> before
            else -> {
                val added = if (item.entry.kind == "app") item.entry.copy(
                    id = "app_${System.currentTimeMillis()}_${item.entry.action.hashCode()}") else item.entry
                DockEditorOrder.insert(before, added, gap)
            }
        }
        if (next != before) {
            entries.clear()
            entries.addAll(next)
            DockEntries.save(this, entries)
            sendBroadcast(Intent(DockOverlayService.ACTION_ENTRIES_CHANGED).setPackage(packageName))
            Logx.d("Dock 编辑：${item.entry.label}，${if (gap == null) "移除" else "插入位置 $gap"}，已保存")
        }
    }

    private fun loadApps() {
        if (catalogLoaded || catalogLoading) return
        catalogLoading = true
        worker.execute {
            val records = runCatching {
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .mapNotNull { it.activityInfo }
                    .distinctBy { it.packageName }
                    .map { info -> DockEntry("candidate_${info.packageName}", "app", info.packageName,
                        info.loadLabel(packageManager).toString(), info.name) to
                        ((info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
                    .sortedBy { it.first.label.lowercase() }
            }.getOrDefault(emptyList())
            main.post {
                if (!destroyed) {
                    catalog = records.map { it.first }
                    systemPackages = records.filter { it.second }.mapTo(HashSet()) { it.first.action }
                    catalogLoaded = true
                    catalogLoading = false
                    renderPool()
                }
            }
        }
    }

    private fun bindIcon(view: ImageView, entry: DockEntry) {
        if (entry.kind == "builtin") {
            view.setImageResource(DockCatalog.of(entry.action)?.spec?.catalogIcon ?: R.drawable.ic_dock_car)
            return
        }
        val cached = icons[entry.action]
        if (cached != null) { view.setImageDrawable(cached); return }
        view.setImageResource(R.drawable.ic_apps)
        view.tag = entry.action
        worker.execute {
            val icon = runCatching { packageManager.getApplicationIcon(entry.action) }.getOrNull()
            main.post {
                if (!destroyed && icon != null) {
                    icons[entry.action] = icon
                    if (view.tag == entry.action) view.setImageDrawable(icon)
                }
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("恢复默认布局")
            .setMessage("将恢复默认条目与排列顺序，是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                DockEntries.reset(this)
                entries.clear()
                entries.addAll(DockEntries.load(this))
                sendBroadcast(Intent(DockOverlayService.ACTION_ENTRIES_CHANGED).setPackage(packageName))
                render()
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editor_apps", showApps)
        outState.putBoolean("editor_system_apps", systemCategory)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangePreview()
        render()
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun card(parent: LinearLayout) = column().apply {
        background = ContextCompat.getDrawable(this@DockEntriesActivity, R.drawable.bg_card)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        parent.addView(this, lp(18))
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun label(text: String, size: Float, secondary: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) typeface = AppFont.bold(this@DockEntriesActivity)
        includeFontPadding = false
        setTextColor(color(if (secondary) R.color.settings_secondary else R.color.settings_text))
    }
    private fun button(text: String, action: (Button) -> Unit) = Button(this).apply {
        this.text = text
        textSize = 15f
        minimumWidth = 0
        minWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        setBackgroundResource(R.drawable.bg_settings_secondary_button)
        setTextColor(ContextCompat.getColorStateList(this@DockEntriesActivity, R.color.settings_action_text))
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { action(this) }
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun candidateHeight(): Int {
        // 车机矮屏给下方预览留出空间；候选仍可在自己的区域滚动。
        val viewport = resources.displayMetrics.heightPixels - DOCK_CLEARANCE_PX
        val preferred = dp(if (showApps) 260 else 170)
        return minOf(preferred, (viewport / 3).coerceAtLeast(dp(88)))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val DOCK_CLEARANCE_PX = 128
    }
}
