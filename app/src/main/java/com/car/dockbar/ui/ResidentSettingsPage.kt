package com.car.dockbar.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.LruCache
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.car.dockbar.R
import com.car.dockbar.util.AccessibilityKeep
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 显示已保存的隐藏应用，按需读取添加弹窗所用的完整目录。 */
class ResidentSettingsPage(activity: MainActivity, root: LinearLayout) :
    SettingsPage(activity, MainActivity.SECTION_APPS, root) {
    private val iconCache = LruCache<String, Drawable.ConstantState>(96)
    private val metadataExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "resident-app-metadata").apply { isDaemon = true }
    }
    private val iconExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "resident-app-icons").apply { isDaemon = true }
    }
    private lateinit var panel: ResidentAppsPanel
    private var requestedBlacklist: Set<String>? = null
    private var blacklistTask: Future<*>? = null
    private var blacklistedApps = emptyList<ResidentAppItem>()
    private var catalog: List<ResidentAppItem>? = null
    private var catalogLoading = false
    private var packageRevision = 0
    private val iconRevisions = mutableMapOf<String, Int>()
    private var dialog: AlertDialog? = null
    private var loadingCatalog = false
    private var destroyed = false

    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            packageRevision++
            catalog = null
            iconRevisions[pkg] = packageRevision
            iconCache.remove(pkg)
            if (requestedBlacklist?.contains(pkg) == true) {
                requestedBlacklist = null
                blacklistedApps = blacklistedApps.filterNot { it.pkg == pkg }
                if (root.isShown) refreshBlacklist()
            }
        }
    }

    override fun create() {
        val host = findViewById<LinearLayout>(R.id.llDockApps)
        val addButton = findViewById<Button>(R.id.btnAddApp)
        val detectionButton = findViewById<Button>(R.id.btnGrantAccessibility)
        panel = ResidentAppsPanel(activity, root, host, addButton, detectionButton)
        addButton.setOnClickListener { pickInstalledAppForDockList() }
        detectionButton.setOnClickListener { openAccessibilitySettings() }
        ContextCompat.registerReceiver(activity, packagesChanged, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onShown() = refreshPage()

    override fun onResume() = refreshPage()

    private fun refreshPage() {
        refreshBlacklist()
        panel.setDetection(AccessibilityKeep.isEnabled(this))
    }

    override fun onDestroy() {
        destroyed = true
        activity.unregisterReceiver(packagesChanged)
        dialog?.dismiss()
        metadataExecutor.shutdownNow()
        iconExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshBlacklist() {
        val blacklist = Prefs.dockHideApps(this)
        if (requestedBlacklist == blacklist) return
        requestedBlacklist = blacklist
        blacklistTask?.cancel(false)
        if (blacklist.isEmpty()) {
            blacklistedApps = emptyList()
            renderBlacklist(blacklist, loading = false)
            return
        }

        renderBlacklist(blacklist, loading = true)
        val pm = packageManager
        blacklistTask = metadataExecutor.submit {
            val nameOrder = AppDisplayNameOrder.comparator()
            val apps = blacklist.mapNotNull { pkg ->
                val info = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                } catch (error: Exception) {
                    Logx.e("读取黑名单应用失败：$pkg", error)
                    return@mapNotNull null
                }
                appItem(pm, info)
            }.sortedWith { left, right ->
                val label = nameOrder.compare(left.label, right.label)
                if (label != 0) label else left.pkg.compareTo(right.pkg)
            }
            main.post {
                if (!destroyed && requestedBlacklist === blacklist) {
                    blacklistedApps = apps
                    renderBlacklist(blacklist, loading = false)
                }
            }
        }
    }

    private fun loadAppIconAsync(pkg: String, image: ImageView) {
        image.tag = pkg
        val cached = iconCache.get(pkg)
        if (cached != null) {
            image.setImageDrawable(cached.newDrawable(resources))
            return
        }
        image.setImageResource(R.drawable.ic_car)
        val revision = iconRevisions[pkg]
        val pm = packageManager
        iconExecutor.execute {
            val drawable = runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: return@execute
            main.post {
                if (!destroyed && revision == iconRevisions[pkg]) {
                    drawable.constantState?.let { iconCache.put(pkg, it) }
                    if (image.tag == pkg) image.setImageDrawable(drawable)
                }
            }
        }
    }

    private fun renderBlacklist(blacklist: Set<String>, loading: Boolean) {
        panel.render(blacklistedApps, blacklist, loading, ::loadAppIconAsync) { pkg ->
            val updated = Prefs.dockHideApps(this) - pkg
            Prefs.setDockHideApps(this, updated)
            notifyAppsChanged()
            refreshBlacklist()
            Logx.d("已从 Dock 黑名单删除：$pkg → 默认显示")
        }
    }

    private fun pickInstalledAppForDockList() {
        if (dialog != null) return
        catalog?.let { showInstalledAppPicker(it); return }
        showDialog(AlertDialog.Builder(this)
            .setTitle("正在读取已安装应用…")
            .setNegativeButton("取消", null)
            .create(), waitingForCatalog = true).applyDockSafe()
        loadCatalog()
    }

    private fun loadCatalog() {
        if (catalogLoading) return
        catalogLoading = true
        val revision = packageRevision
        val pm = packageManager
        metadataExecutor.execute {
            val result = runCatching {
                pm.getInstalledApplications(0).asSequence()
                    .distinctBy { it.packageName }
                    .map { appItem(pm, it) }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }
            main.post {
                if (destroyed) return@post
                catalogLoading = false
                if (revision != packageRevision) {
                    if (loadingCatalog) loadCatalog()
                    return@post
                }
                catalog = result.getOrNull()
                if (loadingCatalog) {
                    dialog?.dismiss()
                    result.onSuccess(::showInstalledAppPicker).onFailure {
                        Logx.e("读取已安装应用失败", it)
                        toastOnce("没有读到已安装应用")
                    }
                }
            }
        }
    }

    private fun appItem(pm: PackageManager, info: ApplicationInfo) = ResidentAppItem(
        info.packageName,
        runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
    )

    private fun showInstalledAppPicker(items: List<ResidentAppItem>) {
        if (items.isEmpty()) {
            toastOnce("没有读到已安装应用")
            return
        }
        val choices = ResidentAppList.choices(items, Prefs.dockHideApps(this))
        val (systemApps, personalApps) = choices.partition { it.app.system }
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * if (metrics.widthPixels < dp(600)) 0.90f else 0.70f).toInt()
        val twoColumns = width >= dp(540)
        val select: (ResidentAppItem) -> Unit = { app ->
            dialog?.dismiss()
            addDockListApp(app)
        }
        val system = ResidentAppPickerAdapter("系统应用", "无系统应用", systemApps, ::loadAppIconAsync, select)
        val personal = ResidentAppPickerAdapter("个人应用", "无个人应用", personalApps, ::loadAppIconAsync, select)
        fun list(rows: RecyclerView.Adapter<*>) = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = rows
        }
        val lists = if (twoColumns) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(list(system), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                addView(list(personal), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(16)
                })
            }
        } else list(ConcatAdapter(system, personal))
        val content = activity.layoutInflater.inflate(R.layout.dialog_resident_app_picker, null) as LinearLayout
        val inset = dp(if (twoColumns) 24 else 16)
        content.setPaddingRelative(inset, content.paddingTop, inset, content.paddingBottom)
        content.findViewById<FrameLayout>(R.id.residentPickerLists).addView(lists,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val picker = AlertDialog.Builder(this).setView(content).create()
        content.findViewById<Button>(R.id.residentPickerCancel).setOnClickListener { picker.dismiss() }
        showDialog(picker).window?.apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@ResidentSettingsPage, R.drawable.bg_card))
            setLayout(width, minOf(dp(434), (metrics.heightPixels * 0.9f).toInt()))
        }
    }

    private fun showDialog(window: AlertDialog, waitingForCatalog: Boolean = false): AlertDialog {
        dialog = window
        loadingCatalog = waitingForCatalog
        window.setOnDismissListener {
            if (dialog === window) {
                dialog = null
                loadingCatalog = false
            }
        }
        window.show()
        return window
    }

    private fun addDockListApp(app: ResidentAppItem) {
        val blacklist = Prefs.dockHideApps(this)
        if (app.pkg in blacklist) return
        Prefs.setDockHideApps(this, blacklist + app.pkg)
        notifyAppsChanged()
        refreshBlacklist()
        Logx.d("已加入 Dock 黑名单：${app.label}（${app.pkg}）→ 隐藏")
    }

    private fun notifyAppsChanged() {
        runCatching {
            sendBroadcast(Intent(DockOverlayService.ACTION_APPS_CHANGED).setPackage(packageName))
        }.onFailure { Logx.e("发送名单变更广播失败", it) }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure { Logx.e("打开无障碍设置失败", it) }
    }


    private fun AlertDialog.applyDockSafe(): AlertDialog {
        window?.decorView?.setPadding(
            0, 0, 0,
            Prefs.dockHeightPx(this@ResidentSettingsPage) +
                Prefs.dockMarginPx(this@ResidentSettingsPage) + dp(12)
        )
        return this
    }

}
