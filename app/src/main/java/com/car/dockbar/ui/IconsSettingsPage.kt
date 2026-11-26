package com.car.dockbar.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.model.AppDrawerApps
import com.car.dockbar.model.DockEntries
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import java.util.concurrent.Executors

/** 负责图标卡片、启动目标及快捷应用交互，不处理其他设置页面。 */
class IconsSettingsPage(activity: MainActivity, root: LinearLayout) :
    SettingsPage(activity, MainActivity.SECTION_ICONS, root) {
    private val appExecutor = Executors.newSingleThreadExecutor()
    private var gridGeneration = 0

    private var destroyed = false
    private var tvAppDrawerSummary: TextView? = null
    private var btnAppDrawerSelection: Button? = null
    private var quickAppsGrid: LinearLayout? = null
    private val dialogs = mutableSetOf<AlertDialog>()

    private data class AppDrawerCandidate(val packageName: String, val label: String, val isSystem: Boolean)

    override fun create() {
        root.id = R.id.settingsDockEntriesPage
        root.background = ContextCompat.getDrawable(this, R.drawable.bg_card)
        root.setPadding(dp(14), dp(14), dp(14), dp(14))
        root.addView(TextView(this).apply {
            text = "Dock图标设置"
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_text))
            textSize = 16f
            typeface = AppFont.bold(this@IconsSettingsPage)
        })

        root.addView(Button(this).apply {
            id = R.id.settingsManageDockEntries
            text = "管理 Dock 图标（增删 / 排序）"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_on_accent))
            background = ContextCompat.getDrawable(this@IconsSettingsPage, R.drawable.bg_button_primary)
            setOnClickListener {
                startActivity(Intent(this@IconsSettingsPage, DockEntriesActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        tvAppDrawerSummary = TextView(this).apply {
            id = R.id.settingsQuickAppsSummary
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_secondary))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(tvAppDrawerSummary)
        btnAppDrawerSelection = Button(this).apply {
            id = R.id.settingsManageQuickApps
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_on_accent))
            background = ContextCompat.getDrawable(this@IconsSettingsPage, R.drawable.bg_button_primary)
            setOnClickListener { chooseAppDrawerApps() }
        }
        root.addView(btnAppDrawerSelection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        quickAppsGrid = LinearLayout(this).apply {
            id = R.id.settingsQuickAppsGrid
            orientation = LinearLayout.VERTICAL
        }
        root.addView(quickAppsGrid)

        val minDelay = Prefs.DOCK_LONG_PRESS_MIN_MS
        val delayStep = 50L
        addSeekRow(
            root, "长按延迟",
            ((Prefs.dockLongPressDelayMs(this) - minDelay) / delayStep).toInt(),
            ((Prefs.DOCK_LONG_PRESS_MAX_MS - minDelay) / delayStep).toInt(),
            { "${minDelay + it * delayStep} ms" },
            reloadDock = false,
            onChange = { Prefs.setDockLongPressDelayMs(this, minDelay + it * delayStep) },
            separateValue = true
        ).id = R.id.settingsLongPressDelayRow

        // --- 淡入淡出（时长滑块用于调节显示/隐藏动画快慢）---
        // 0.1 秒粒度（10ms 步进太细不好调）
        // ── [S02] 淡入淡出时长 ───────────────────────
        addSeekRow(
            root, "淡入淡出时长",
            (Prefs.fadeDurationMs(this) / 100L).toInt(), 10,
            { "%.1f 秒".format(it / 10f) },
            onChange = { Prefs.setFadeDurationMs(this, it * 100L) },
            separateValue = true
        )
        root.getChildAt(root.childCount - 1).id = R.id.settingsFadeDurationRow
        // --- ★ v1.4.82：AUTO 风道档位（这台车有 5 档 AUTO 风道） ---
        // ── [S05] AUTO 风道档位 ────────────────────
        addSeekRow(
            root, "当前档位",
            Prefs.fanAutoLevel(this), 5,
            { "%d 档".format(it) },
            onChange = {
                // ★ v1.4.84：滑条必须**直接写车机**（之前只存本地 Pref → 车机没反应）
                com.car.dockbar.vehicle.VehicleController.setFanAutoLevel(this, it)
            },
            separateValue = true
        )
        root.getChildAt(root.childCount - 1).id = R.id.settingsAutoFanRow
        layoutInflater.inflate(R.layout.settings_icon_targets, root, true)
        loadTargets()
        root.findViewById<Button>(R.id.btnApplyEntries).setOnClickListener {
            saveTargets()
            Logx.d("入口配置已保存")
        }
    }

    override fun buildLayout() { IconsSettingsLayout(activity).rebuild(root) }

    override fun onShown() = onResume()

    override fun onResume() {
        if (destroyed) return
        refreshAppDrawerSelectionLabel()
        findViewById<TextView>(R.id.settingsEntryCount).text = "已添加 ${DockEntries.load(this).size} 项"
    }

    /** 响应 EXTRA_PICK_APP_DRAWER 的入口，无需在宿主中暴露选择器回调。 */
    fun requestAppDrawerPicker() {
        main.postDelayed({ if (!destroyed) chooseAppDrawerApps() }, 300)
    }

    private fun loadTargets() {
        findViewById<EditText>(R.id.etSettingsPkg).setText(Prefs.settingsPkg(this))
        findViewById<EditText>(R.id.etSettingsCls).setText(Prefs.settingsCls(this))
        findViewById<EditText>(R.id.etSettingsAction).setText(Prefs.settingsAction(this))
        findViewById<EditText>(R.id.etSeatPkg).setText(Prefs.seatPkg(this))
        findViewById<EditText>(R.id.etSeatCls).setText(Prefs.seatCls(this))
        findViewById<EditText>(R.id.etSeatAction).setText(Prefs.seatAction(this))
        findViewById<EditText>(R.id.etAirPkg).setText(Prefs.airDirPkg(this))
        findViewById<EditText>(R.id.etAirCls).setText(Prefs.airDirCls(this))
        // ── [S34] 最近任务包名（D01 长按打开）────────
        findViewById<EditText>(R.id.etRecentsPkg).setText(Prefs.recentsPkg(this))
    }

    private fun saveTargets() {
        Prefs.putString(this, "settings_pkg", findViewById<EditText>(R.id.etSettingsPkg).text.toString().trim())
        Prefs.putString(this, "settings_cls", findViewById<EditText>(R.id.etSettingsCls).text.toString().trim())
        Prefs.putString(this, "settings_action", findViewById<EditText>(R.id.etSettingsAction).text.toString().trim())
        Prefs.putString(this, "seat_pkg", findViewById<EditText>(R.id.etSeatPkg).text.toString().trim())
        Prefs.putString(this, "seat_cls", findViewById<EditText>(R.id.etSeatCls).text.toString().trim())
        Prefs.putString(this, "seat_action", findViewById<EditText>(R.id.etSeatAction).text.toString().trim())
        Prefs.putString(this, "airdir_pkg", findViewById<EditText>(R.id.etAirPkg).text.toString().trim())
        Prefs.putString(this, "airdir_cls", findViewById<EditText>(R.id.etAirCls).text.toString().trim())
        Prefs.putString(this, "recents_pkg", findViewById<EditText>(R.id.etRecentsPkg).text.toString().trim())
        Logx.d("入口：设置=${Prefs.settingsPkg(this)}/${Prefs.settingsCls(this)}")
        Logx.d("入口：空调=${Prefs.airDirPkg(this)}/${Prefs.airDirCls(this)}")
        Logx.d("入口：座椅=${Prefs.seatPkg(this)}/${Prefs.seatCls(this)}")
    }

    override fun onDestroy() {
        destroyed = true
        appExecutor.shutdownNow()
        dialogs.toList().forEach { runCatching { it.dismiss() } }
        dialogs.clear()
        super.onDestroy()
    }

    private fun refreshAppDrawerSelectionLabel() {
        val packages = AppDrawerApps.loadSlots(this)
        val count = packages.count { it != null }
        tvAppDrawerSummary?.text = "已选择 $count / ${AppDrawerApps.MAX_APPS}"
        btnAppDrawerSelection?.text = "管理应用"
        renderQuickAppsGrid(packages)
    }

    private fun renderQuickAppsGrid(packages: List<String?>) {
        val grid = quickAppsGrid ?: return
        grid.removeAllViews()
        val generation = ++gridGeneration
        val slotViews = mutableListOf<View>()
        var dragFrom = -1
        var dragTo = -1
        fun highlightDrag() {
            slotViews.forEachIndexed { index, view ->
                AppDrawerSlotStyle.updateDrag(view, index, dragFrom, dragTo)
            }
        }
        fun finishDrag(commit: Boolean) {
            val from = dragFrom
            val to = dragTo
            dragFrom = -1
            dragTo = -1
            grid.parent?.requestDisallowInterceptTouchEvent(false)
            if (commit && from in 0 until AppDrawerApps.MAX_APPS &&
                to in 0 until AppDrawerApps.MAX_APPS && from != to) {
                // 只交换两个固定槽位，空槽位也参与交换。
                val slots = packages.toMutableList()
                val old = slots[from]
                slots[from] = slots[to]
                slots[to] = old
                AppDrawerApps.saveSlots(this, slots)
                Logx.d("设置页快捷应用：槽位 $from ↔ $to，已保存")
                refreshAppDrawerSelectionLabel()
            } else highlightDrag()
        }
        repeat(2) { rowIndex ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (rowIndex > 0) topMargin = dp(8) })
            repeat(3) { colIndex ->
                val slot = rowIndex * 3 + colIndex
                val pkg = packages[slot]
                val tile = SettingsAppTile(this, pkg).apply {
                    AppDrawerSlotStyle.applyDragOutline(this,
                        ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_accent))
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (pkg == null) "管理快捷应用 ${slot + 1}" else "替换快捷应用 $pkg"
                    setOnClickListener {
                        if (pkg == null) chooseAppDrawerApps() else chooseAppDrawerApps(slot)
                    }
                    if (pkg != null) DockDragLongPress.attach(this, onDragTouch = drag@{ event ->
                        if (dragFrom != slot) return@drag
                        when (event.actionMasked) {
                            MotionEvent.ACTION_MOVE -> {
                                val location = IntArray(2)
                                val target = slotViews.indexOfFirst { view ->
                                    view.getLocationOnScreen(location)
                                    event.rawX >= location[0] && event.rawX <= location[0] + view.width &&
                                        event.rawY >= location[1] && event.rawY <= location[1] + view.height
                                }
                                if (target >= 0 && target != dragTo) {
                                    dragTo = target
                                    highlightDrag()
                                }
                            }
                            MotionEvent.ACTION_UP -> finishDrag(true)
                            MotionEvent.ACTION_CANCEL -> finishDrag(false)
                        }
                    }) { _, _ ->
                        if (dragFrom < 0) {
                            dragFrom = slot
                            dragTo = slot
                            parent?.requestDisallowInterceptTouchEvent(true)
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            highlightDrag()
                        }
                    }
                }
                slotViews.add(tile)
                if (pkg != null) {
                    tile.addRemoveButton("清除快捷应用 $pkg") {
                        val current = AppDrawerApps.loadSlots(this).toMutableList()
                        if (current[slot] == pkg) {
                            current[slot] = null
                            AppDrawerApps.saveSlots(this, current)
                        }
                        refreshAppDrawerSelectionLabel()
                    }
                    loadAppDisplay(pkg, tile, generation)
                }
                row.addView(tile, LinearLayout.LayoutParams(0, dp(SettingsAppTile.HEIGHT_DP), 1f).apply {
                    if (colIndex > 0) marginStart = dp(8)
                })
            }
        }
    }

    private fun chooseAppDrawerApps(slot: Int? = null) {
        val loading = AlertDialog.Builder(this)
            .setTitle("正在读取可启动应用…")
            .setCancelable(true)
            .create()
        loading.show()
        loading.applyDockSafe()
        appExecutor.execute {
            val pm = packageManager
            val items = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).asSequence()
                    .mapNotNull { it.activityInfo?.applicationInfo }
                    .distinctBy { it.packageName }
                    .map { info ->
                        AppDrawerCandidate(
                            info.packageName, AppDrawerSlotStyle.appLabel(pm, info),
                            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
            main.post {
                if (destroyed) return@post
                runCatching { loading.dismiss() }
                if (slot == null) showAppDrawerPicker(items) else showAppDrawerSlotPicker(items, slot)
            }
        }
    }

    private fun showAppDrawerPicker(items: List<AppDrawerCandidate>) {
        if (items.isEmpty()) {
            toastOnce("没有读到可启动的应用")
            return
        }
        val availablePackages = items.mapTo(HashSet()) { it.packageName }
        val selected = AppDrawerApps.loadSlots(this).filterNotNullTo(linkedSetOf())
        val unavailableCount = selected.count { it !in availablePackages }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(8))
        }
        val countLabel = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_secondary))
            textSize = 14f
        }
        list.addView(countLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
        fun refreshCount() {
            countLabel.text = "已选 ${selected.size}/${AppDrawerApps.MAX_APPS}（面板按 2 行 × 3 列排列）"
            if (unavailableCount > 0) {
                countLabel.append("\n其中 $unavailableCount 个应用不在可启动列表，仍占用原槽，可在快捷应用卡片中移除或替换。")
            }
        }
        refreshCount()

        fun appCategory(title: String, categoryItems: List<AppDrawerCandidate>): LinearLayout {
            val (col, body) = appListColumn(title)
            if (categoryItems.isEmpty()) {
                body.addView(TextView(this).apply {
                    text = "暂无应用"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_secondary))
                    setPadding(dp(4), dp(5), 0, dp(5))
                })
                return col
            }
            categoryItems.forEach { candidate ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(this@IconsSettingsPage, R.drawable.bg_field)
                    setPadding(dp(6), dp(3), dp(8), dp(3))
                }
                row.addView(ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val drawable = runCatching { packageManager.getApplicationIcon(candidate.packageName) }.getOrNull()
                    AppDrawerSlotStyle.appIcon(this, drawable)
                })
                val check = CheckBox(this).apply {
                    text = "${candidate.label}\n${candidate.packageName}"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_text))
                    isChecked = candidate.packageName in selected
                }
                lateinit var listener: android.widget.CompoundButton.OnCheckedChangeListener
                listener = android.widget.CompoundButton.OnCheckedChangeListener { button, checked ->
                    if (checked && selected.size >= AppDrawerApps.MAX_APPS) {
                        button.setOnCheckedChangeListener(null)
                        button.isChecked = false
                        button.setOnCheckedChangeListener(listener)
                        toastOnce("快捷应用最多选择 ${AppDrawerApps.MAX_APPS} 个")
                        return@OnCheckedChangeListener
                    }
                    if (checked) selected.add(candidate.packageName) else selected.remove(candidate.packageName)
                    refreshCount()
                }
                check.setOnCheckedChangeListener(listener)
                row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                body.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) })
            }
            return col
        }
        list.addView(settingsColumns(this,
            appCategory("系统应用", items.filter { it.isSystem }),
            appCategory("个人应用", items.filterNot { it.isSystem }),
            minWidthDp = 500, gapDp = 12))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        AlertDialog.Builder(this)
            .setTitle("选择快捷应用")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                AppDrawerApps.saveSelection(this, availablePackages, selected)
                refreshAppDrawerSelectionLabel()
                Logx.d("快捷应用面板已更新：${selected.size} 个应用")
            }
            // ★ v1.5.8：不再套 applyDockSafe() —— 它会在窗口底部垫 132dp 空白给 Dock 让位，
            //   窗口缩到 0.7 倍后这块空白占了近 1/3。窗口现在屏幕居中、高 434dp，
            //   底边远在 Dock 上方，不需要让位。
            .show()
            // ★ v1.5.6：窗口缩小到 0.7 倍 —— 原来 0.94×620dp 时「保存/取消」被顶到屏幕最下面，点不到
            .window?.setLayout((resources.displayMetrics.widthPixels * 0.7f).toInt(), dp(434))
    }

    private fun showAppDrawerSlotPicker(items: List<AppDrawerCandidate>, slot: Int) {
        if (items.isEmpty()) {
            toastOnce("没有读到可启动的应用")
            return
        }
        val labels = items.map { "${it.label}\n${it.packageName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (AppDrawerApps.loadSlots(this).getOrNull(slot) != null) "替换快捷应用" else "添加快捷应用")
            .setItems(labels) { _, index ->
                val chosen = items[index].packageName
                val apps = AppDrawerApps.loadSlots(this).toMutableList()
                val existing = apps.indexOf(chosen)
                if (existing >= 0 && existing != slot) apps[existing] = apps[slot]
                apps[slot] = chosen
                AppDrawerApps.saveSlots(this, apps)
                refreshAppDrawerSelectionLabel()
                Logx.d("快捷应用已更新：${apps.count { it != null }} 个应用")
            }
            .setNegativeButton("取消", null)
            .showDockSafe()
    }

    private fun appListColumn(title: String): Pair<LinearLayout, LinearLayout> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_text))
            textSize = 14f
            typeface = AppFont.bold(this@IconsSettingsPage)
            setPadding(dp(4), dp(2), dp(4), dp(6))
        })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        col.addView(body)
        return col to body
    }

    private fun loadAppDisplay(pkg: String, tile: SettingsAppTile, generation: Int) {
        tile.icon.setImageResource(R.drawable.ic_app_missing)
        appExecutor.execute {
            val display = AppDrawerSlotStyle.resolveApp(packageManager, pkg)
            main.post {
                if (destroyed || generation != gridGeneration) return@post
                tile.contentDescription = "替换快捷应用 ${display.label}"
                tile.updateLabel(display.label, "清除快捷应用 ${display.label}")
                display.icon?.let(tile.icon::setImageDrawable)
            }
        }
    }

    private fun AlertDialog.Builder.showDockSafe(): AlertDialog {
        val d = show()
        return d.applyDockSafe()
    }

    /** 已 show 的对话框直接套用 Dock 让位 padding */
    private fun AlertDialog.applyDockSafe(): AlertDialog {
        window?.decorView?.setPadding(
            0, 0, 0,
            Prefs.dockHeightPx(this@IconsSettingsPage) + Prefs.dockMarginPx(this@IconsSettingsPage) + dp(12)
        )
        return this
    }
}
