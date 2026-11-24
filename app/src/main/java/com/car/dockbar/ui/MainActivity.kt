package com.car.dockbar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.CrashHandler
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/** 设置页容器：仅负责页面注册、导航及生命周期分发。 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val UI_SCALE = 0.9f
        private const val STATE_SECTION = "settings_section"
        const val EXTRA_SECTION = "section"
        const val SECTION_OVERLAY = "overlay"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_ICONS = "icons"
        const val SECTION_LYRIC = "lyric"
        const val SECTION_APPS = "apps"
        const val SECTION_LOGS = "logs"
        const val EXTRA_PICK_APP_DRAWER = "pick_app_drawer"

        fun scaledContext(base: Context, previousScale: Float = 1.2f): Context {
            val config = Configuration(base.resources.configuration)
            config.densityDpi = (base.resources.displayMetrics.densityDpi * previousScale * UI_SCALE).toInt()
            return base.createConfigurationContext(config)
        }
    }

    private data class PageSpec(
        val key: String,
        val label: String,
        val icon: Int,
        val layout: Int,
        val factory: (MainActivity, LinearLayout) -> SettingsPage,
    )

    /** 在此注册或移除页面；控件与行为由各页面自身的控制器管理。 */
    private val pageSpecs = listOf(
        PageSpec(SECTION_OVERLAY, "悬浮 Dock 栏", R.drawable.ic_nav_dock, R.layout.settings_page_overlay, ::FloatingDockSettingsPage),
        PageSpec(SECTION_APPEARANCE, "外观与风格", R.drawable.ic_nav_home, R.layout.settings_page_appearance, ::AppearanceSettingsPage),
        PageSpec(SECTION_ICONS, "Dock 图标管理", R.drawable.ic_nav_grid, 0, ::IconsSettingsPage),
        PageSpec(SECTION_LYRIC, "歌词悬浮窗", R.drawable.ic_nav_doc, 0, ::LyricsSettingsPage),
        PageSpec(SECTION_APPS, "隐藏 Dock 应用", R.drawable.ic_nav_list, R.layout.settings_page_resident, ::ResidentSettingsPage),
        PageSpec(SECTION_LOGS, "诊断与日志", R.drawable.ic_nav_pulse, R.layout.settings_page_logs, ::LogsSettingsPage),
    )
    private class NavRow(val container: View, val bar: View, val icon: ImageView, val label: TextView)
    private val navButtons = LinkedHashMap<String, NavRow>()
    private lateinit var pages: SettingsPageRegistry
    private var currentPage = SECTION_OVERLAY
    internal val runtime = SettingsRuntime(this, ::refreshRuntimeStatus)
    internal var settingsCrashReport: String? = null
    internal val diagnosticExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val page = if (uri != null) pages.requirePage(SECTION_LOGS) else pages.createdPage(SECTION_LOGS)
        (page as? LogsSettingsPage)?.onExportResult(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(scaledContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 保留原有的 128 物理像素 Dock 避让距离，不随显示密度变化。
        listOf(R.id.svNav, R.id.svContent).forEach { id ->
            val viewport = findViewById<View>(id)
            (viewport.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = 128
                viewport.layoutParams = this
            }
        }
        val content = findViewById<LinearLayout>(R.id.llRoot)
        pages = SettingsPageRegistry(content, pageSpecs.associate { spec ->
            spec.key to {
                val root = if (spec.layout != 0) {
                    layoutInflater.inflate(spec.layout, content, false) as LinearLayout
                } else LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(12) }
                }
                spec.factory(this, root)
            }
        })
        buildNav()
        settingsCrashReport = CrashHandler.read(this)?.also { crash ->
            CrashHandler.archiveForExport(this, crash)
            CrashHandler.clear(this, clearExportArchive = false)
            Logx.e("检测到上次闪退，已归档 Java 堆栈")
        }
        val target = intent?.getStringExtra(EXTRA_SECTION)?.takeUnless { it.isBlank() }
        showPage(savedInstanceState?.getString(STATE_SECTION) ?: target ?: SECTION_OVERLAY)
        if (savedInstanceState == null && target == null && intent?.getBooleanExtra(EXTRA_PICK_APP_DRAWER, false) == true) {
            (pages.requirePage(SECTION_ICONS) as IconsSettingsPage).requestAppDrawerPicker()
        }
        runtime.start()
    }

    override fun onResume() {
        super.onResume()
        runtime.onResume()
        if (::pages.isInitialized) pages.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SECTION, currentPage)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::pages.isInitialized) pages.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        runtime.onDestroy()
        if (::pages.isInitialized) pages.onDestroy()
        super.onDestroy()
    }

    @Deprecated("Uses the existing settings navigation back behavior")
    override fun onBackPressed() {
        if (currentPage != SECTION_OVERLAY) showPage(SECTION_OVERLAY) else super.onBackPressed()
    }

    private fun showPage(key: String) {
        val page = pages.show(key)
        currentPage = page.key
        val label = pageSpecs.first { it.key == currentPage }.label
        title = "设置 · $label"
        updateNavSelection()
        findViewById<ScrollView>(R.id.svContent).post {
            findViewById<ScrollView>(R.id.svContent).scrollTo(0, 0)
        }
        Logx.d("设置页：切到「$label」")
    }

    private fun refreshRuntimeStatus() {
        val running = DockOverlayService.alive
        val status = findViewById<TextView>(R.id.settingsNavStatus)
        status.text = if (running) "●  Dock 服务已就绪" else "●  Dock 未运行"
        status.setTextColor(ContextCompat.getColor(this,
            if (running) R.color.settings_service_running else R.color.settings_warning))
        (pages.createdPage(SECTION_OVERLAY) as? FloatingDockSettingsPage)?.refreshStatus()
        (pages.createdPage(SECTION_LYRIC) as? LyricsSettingsPage)?.refreshStatus()
        (pages.createdPage(SECTION_LOGS) as? LogsSettingsPage)?.refreshRuntimeStatus()
    }

    private fun buildNav() {
        val nav = findViewById<LinearLayout>(R.id.llNav)
        nav.removeAllViews()
        navButtons.clear()
        nav.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(22))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                imageTintList = null
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0, 0, 0, 0)
                val radius = resources.getDimension(R.dimen.compact_surface_radius)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 17f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_text))
                typeface = AppFont.bold(this@MainActivity)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })
        })
        nav.addView(TextView(this).apply {
            text = "个性化设置"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_secondary))
            setPadding(dp(12), 0, dp(8), dp(12))
        })
        for (item in pageSpecs) {
            if (item.key == SECTION_LOGS) {
                nav.addView(View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.settings_outline))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(dp(12), dp(5), dp(12), dp(10))
                })
            }
            val bar = View(this).apply {
                setBackgroundColor(0x00000000)
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(28)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(item.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginStart = dp(8)
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val label = TextView(this).apply {
                text = item.label
                textSize = 16f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = dp(6)
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), dp(6), dp(6))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_settings_nav_idle)
                isClickable = true
                isFocusable = true
                minimumHeight = dp(52)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                    bottomMargin = dp(6)
                }
                setOnClickListener { showPage(item.key) }
                addView(bar)
                addView(icon)
                addView(label)
            }
            nav.addView(row)
            navButtons[item.key] = NavRow(row, bar, icon, label)
        }
        nav.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(TextView(this@MainActivity).apply {
                id = R.id.settingsNavStatus
                text = "●  Dock 未运行"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_warning))
            })
            addView(TextView(this@MainActivity).apply {
                id = R.id.settingsNavVersion
                text = "v${Prefs.appVersion(this@MainActivity)}"
                textSize = 17f
                gravity = Gravity.END or Gravity.BOTTOM
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_secondary))
                setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(8), dp(16), dp(8), dp(12))
        })
    }

    private fun updateNavSelection() {
        val accent = ContextCompat.getColor(this, R.color.settings_accent)
        val idle = ContextCompat.getColor(this, R.color.settings_secondary)
        for ((page, row) in navButtons) {
            val on = page == currentPage
            val color = if (on) accent else idle
            row.container.isSelected = on
            row.container.background = ContextCompat.getDrawable(this,
                if (on) R.drawable.bg_settings_nav_selected else R.drawable.bg_settings_nav_idle)
            row.bar.setBackgroundColor(if (on) accent else 0x00000000)
            row.icon.imageTintList = ColorStateList.valueOf(color)
            row.label.setTextColor(color)
            row.label.typeface = if (on) AppFont.bold(this) else AppFont.regular(this)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
