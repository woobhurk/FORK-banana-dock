package com.car.dockbar.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.GradientSpec
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

class AppearanceSettingsPage(activity: MainActivity, root: LinearLayout) :
    SettingsPage(activity, MainActivity.SECTION_APPEARANCE, root) {
    override fun buildLayout() { AppearanceSettingsLayout(activity).rebuild(root) }
    private lateinit var tvCorner: TextView
    private lateinit var tvIconScale: TextView
    private lateinit var tvTextScale: TextView
    private val solidPreviewFill = ColorDrawable()
    private val gradientPreviewFill = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
    }
    private val gradientPreview = View(activity).apply {
        id = R.id.settingsGradientPreview
    }
    private var bgColorControlRows: List<View> = emptyList()
    private var blurRadiusRow: View? = null
    override fun create() {
        tvCorner = findViewById(R.id.tvCorner)
        tvIconScale = findViewById(R.id.tvIconScale)
        tvTextScale = findViewById(R.id.tvTextScale)
        findViewById<SwitchCompat>(R.id.swSeparators).isChecked = Prefs.showSeparators(this)
        findViewById<SeekBar>(R.id.sbCorner).progress = Prefs.cornerRadiusPx(this)
        findViewById<SeekBar>(R.id.sbIconScale).progress =
            ((Prefs.iconScale(this) - 0.5f) * 100).toInt().coerceIn(0, 100)
        findViewById<SeekBar>(R.id.sbTextScale).progress =
            ((Prefs.textScale(this) - 0.5f) * 100).toInt().coerceIn(0, 100)


        refreshAppearanceLabels()
        findViewById<SeekBar>(R.id.sbCorner).setOnSeekBarChangeListener(seek { refreshAppearanceLabels() })
        findViewById<SeekBar>(R.id.sbIconScale).setOnSeekBarChangeListener(seek { refreshAppearanceLabels() })
        findViewById<SeekBar>(R.id.sbTextScale).setOnSeekBarChangeListener(seek { refreshAppearanceLabels() })
        findViewById<SwitchCompat>(R.id.swSeparators).setOnCheckedChangeListener { _, _ ->
            saveAppearance()
            reloadService()
        }
        val holdStep = Prefs.MUSIC_SCROLL_HOLD_STEP_MS
        val holdMax = (Prefs.MUSIC_SCROLL_HOLD_MAX_MS / holdStep).toInt()
        addSeekRow(
            root, "首尾停顿",
            (Prefs.musicScrollHoldMs(this) / holdStep).toInt().coerceIn(0, holdMax), holdMax,
            { "%.1f 秒".format(it * holdStep / 1000f) },
            reloadDock = false,
            onChange = {
                Prefs.setMusicScrollHoldMs(this, it * holdStep)
                DockMusicView.refreshScrollHold()
            },
            separateValue = true
        ).id = R.id.settingsMusicScrollHoldRow
        val appearance = root
        addSwitchRow(
            appearance, "底色跟随系统日夜模式",
            Prefs.bgFollowNight(this)
        ) {
            Prefs.setBgFollowNight(this, it)
            refreshGradientPreview()
        }
        appearance.getChildAt(appearance.childCount - 1).id = R.id.settingsFollowNightRow

        // 背景形态四选一；旧「模糊」入口用于透明灰色叠层试验，不采样屏幕。
        addBgModeRow(appearance)
        val overlayControls = LinearLayout(this).apply {
            id = R.id.settingsOverlayControls
            orientation = LinearLayout.VERTICAL
        }
        appearance.addView(overlayControls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        blurRadiusRow = overlayControls
        addSeekRow(
            overlayControls, "第二层 · 灰色深浅",
            Prefs.overlayGrayLevel(this), 100,
            { when (it) { 0 -> "黑色"; 100 -> "白色"; else -> "$it%（越大越浅）" } },
            onChange = { Prefs.setOverlayGrayLevel(this, it) }
        )
        addSeekRow(
            overlayControls, "第三层 · 透明度",
            Prefs.overlayTransparency(this), 100,
            { when (it) { 0 -> "不透明"; 100 -> "完全透明"; else -> "$it%" } },
            onChange = { Prefs.setOverlayTransparency(this, it) }
        )
        overlayControls.addView(TextView(this).apply {
            text = "第一层始终纯透明。灰色深浅与合成透明度分别调节，不影响图标和文字；此效果不是真正的背景模糊。"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AppearanceSettingsPage, R.color.settings_secondary))
            setPadding(0, dp(8), 0, 0)
        })
        blurRadiusRow?.visibility =
            if (Prefs.bgMode(this) == BgMode.BLUR) View.VISIBLE else View.GONE

        appearance.addView(gradientPreview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(8) })

        // ── 白天底色透明度 ───────────────────────
        val dayTransparencyRow = addSeekRow(
            appearance, "白天底色透明度",
            Prefs.dayBgTransparency(this), 100,
            format = { "$it%" },
            onChange = { Prefs.setDayBgTransparency(this, it) },
            onProgress = { p -> Prefs.setDayBgTransparency(this, p); refreshGradientPreview() },
            separateValue = true
        )
        dayTransparencyRow.id = R.id.settingsDayTransparencyRow
        // ── 夜间底色透明度 ───────────────────────
        val nightTransparencyRow = addSeekRow(
            appearance, "夜间底色透明度",
            Prefs.nightBgTransparency(this), 100,
            format = { "$it%" },
            onChange = { Prefs.setNightBgTransparency(this, it) },
            onProgress = { p -> Prefs.setNightBgTransparency(this, p); refreshGradientPreview() },
            separateValue = true
        )
        nightTransparencyRow.id = R.id.settingsNightTransparencyRow
        // ★ v3.13.16：按当前形态显隐相关控件（滑杆/预览条只属于纯色与渐变）
        bgColorControlRows = listOf(dayTransparencyRow, nightTransparencyRow)
        refreshBgModeControls(Prefs.bgMode(this))
        refreshGradientPreview()
    }
    override fun onResume() { refreshGradientPreview() }
    override fun onShown() { refreshGradientPreview() }
    override fun onConfigurationChanged(newConfig: Configuration) { refreshGradientPreview() }
    private fun refreshAppearanceLabels() {
        tvCorner.text = "${findViewById<SeekBar>(R.id.sbCorner).progress} px"
        val isc = 0.5f + findViewById<SeekBar>(R.id.sbIconScale).progress / 100f
        tvIconScale.text = "${"%.2f".format(isc)}×"
        val tsc = 0.5f + findViewById<SeekBar>(R.id.sbTextScale).progress / 100f
        tvTextScale.text = "${"%.2f".format(tsc)}×"
    }
    private fun saveAppearance() {
        Prefs.setCornerRadiusPx(this, findViewById<SeekBar>(R.id.sbCorner).progress)
        Prefs.setIconScale(this, 0.5f + findViewById<SeekBar>(R.id.sbIconScale).progress / 100f)
        Prefs.setTextScale(this, 0.5f + findViewById<SeekBar>(R.id.sbTextScale).progress / 100f)
        Prefs.setShowSeparators(this, findViewById<SwitchCompat>(R.id.swSeparators).isChecked)
        Logx.d("外观配置已保存：图标×${"%.2f".format(0.5f + findViewById<SeekBar>(R.id.sbIconScale).progress / 100f)} 文字×${"%.2f".format(0.5f + findViewById<SeekBar>(R.id.sbTextScale).progress / 100f)}（反色按背景自动）")
    }
    private fun refreshGradientPreview() {
        val fill = when (Prefs.bgMode(this)) {
            BgMode.SOLID -> solidPreviewFill.apply { color = Prefs.solidBgColor(this@AppearanceSettingsPage) }
            BgMode.GRADIENT -> gradientPreviewFill.apply {
                colors = GradientSpec.verticalRampColors(Prefs.currentGradientColors(this@AppearanceSettingsPage))
            }
            BgMode.TRANSPARENT, BgMode.BLUR -> return
        }
        if (gradientPreview.background !== fill) gradientPreview.background = fill
    }
    private fun refreshBgModeControls(selected: BgMode) {
        // 纯色 / 渐变显示日夜透明度；现有试验参数只在「模糊」入口下显示。
        val showColorControls = selected == BgMode.SOLID || selected == BgMode.GRADIENT
        for (row in bgColorControlRows) {
            row.visibility = if (showColorControls) View.VISIBLE else View.GONE
        }
        gradientPreview.visibility = if (showColorControls) View.VISIBLE else View.GONE
        blurRadiusRow?.visibility = if (selected == BgMode.BLUR) View.VISIBLE else View.GONE
    }
    private fun addBgModeRow(parent: LinearLayout) {
        val group = object : RadioGroup(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // 按内容区宽度和字体缩放切换，避免四等分后挤压说明。
                val wide = MeasureSpec.getSize(widthMeasureSpec) >=
                    dp(500) * resources.configuration.fontScale.coerceAtLeast(1f)
                val target = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                if (orientation != target) {
                    orientation = target
                    for (index in 0 until childCount) {
                        val choice = getChildAt(index) as AppCompatRadioButton
                        val swatch = choice.compoundDrawablesRelative[if (wide) 2 else 1]
                        choice.gravity = Gravity.CENTER_VERTICAL or if (wide) Gravity.CENTER_HORIZONTAL else Gravity.START
                        choice.setCompoundDrawablesRelative(null, if (wide) swatch else null,
                            if (wide) null else swatch, null)
                        (choice.layoutParams as RadioGroup.LayoutParams).apply {
                            width = if (wide) 0 else LinearLayout.LayoutParams.MATCH_PARENT
                            weight = if (wide) 1f else 0f
                            marginStart = if (wide && index > 0) dp(8) else 0
                            topMargin = if (!wide && index > 0) dp(8) else 0
                            resolveLayoutDirection(layoutDirection)
                        }
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            id = R.id.settingsBgModeRow
            orientation = LinearLayout.VERTICAL
            isSaveEnabled = false
        }
        val choices = BgMode.values().associateBy { View.generateViewId() }
        for ((index, entry) in choices.entries.withIndex()) {
            val (buttonId, mode) = entry
            val caption = when (mode) {
                BgMode.SOLID -> "统一底色"
                BgMode.TRANSPARENT -> "不绘制底色"
                BgMode.GRADIENT -> "上下渐变"
                BgMode.BLUR -> "（测试中）"
            }
            val choice = AppCompatRadioButton(this).apply {
                id = buttonId
                isSaveEnabled = false
                text = SpannableString("${mode.label}\n$caption").apply {
                    val start = mode.label.length + 1
                    setSpan(RelativeSizeSpan(0.83f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.settings_secondary)),
                        start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextColor(ContextCompat.getColor(context, R.color.settings_text))
                supportButtonTintList = ContextCompat.getColorStateList(context, R.color.settings_choice_tint)
                setBackgroundResource(R.drawable.bg_settings_choice)
                setPaddingRelative(dp(8), dp(10), dp(8), dp(10))
                minHeight = dp(48)
                compoundDrawablePadding = dp(5)
                setCompoundDrawablesRelative(null, null, modeSwatch(mode), null)
            }
            group.addView(choice, RadioGroup.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = dp(8) })
        }
        val selected = Prefs.bgMode(this)
        group.check(choices.entries.first { it.value == selected }.key)
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = choices[checkedId] ?: return@setOnCheckedChangeListener
            if (mode != Prefs.bgMode(this)) {
                Prefs.setBgMode(this, mode)
                refreshBgModeControls(mode)
                refreshGradientPreview()
                reloadService()
            }
        }
        parent.addView(group, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    // 静态样块示意四种形态，实际颜色与透明度由下方预览显示。
    private fun modeSwatch(mode: BgMode): GradientDrawable = GradientDrawable().apply {
        val field = ContextCompat.getColor(this@AppearanceSettingsPage, R.color.settings_field)
        val outline = ContextCompat.getColor(this@AppearanceSettingsPage, R.color.settings_control_outline)
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1), outline)
        when (mode) {
            BgMode.SOLID -> setColor(0xFFD8E2E1.toInt())
            BgMode.TRANSPARENT -> {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), outline, dp(4).toFloat(), dp(3).toFloat())
            }
            BgMode.GRADIENT -> {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(field, 0xFFD8E2E1.toInt())
            }
            BgMode.BLUR -> setColor(0xFF687374.toInt())
        }
        setBounds(0, 0, dp(68), dp(34))
    }
}
