package com.car.dockbar.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R


internal class AppearanceSettingsLayout(activity: Activity) : SettingsPageLayout(activity) {
    fun rebuild(page: LinearLayout) {
        val separators = page.findViewById<SwitchCompat>(R.id.swSeparators)
        val modeRow = page.findViewById<View>(R.id.settingsBgModeRow)
        val followNight = page.findViewById<View>(R.id.settingsFollowNightRow)
        val day = page.findViewById<View>(R.id.settingsDayTransparencyRow)
        val night = page.findViewById<View>(R.id.settingsNightTransparencyRow)
        val overlay = page.findViewById<View>(R.id.settingsOverlayControls)
        val preview = page.findViewById<View>(R.id.settingsGradientPreview)
        val musicHold = page.findViewById<View>(R.id.settingsMusicScrollHoldRow)
        val scales = listOf(
            Triple("圆角半径", R.id.tvCorner, R.id.sbCorner),
            Triple("图标缩放", R.id.tvIconScale, R.id.sbIconScale),
            Triple("文字缩放", R.id.tvTextScale, R.id.sbTextScale))
        val scaleViews = scales.map { (_, valueId, seekId) ->
            page.findViewById<TextView>(valueId) to page.findViewById<SeekBar>(seekId)
        }
        (listOf(separators, modeRow, followNight, day, night, overlay, preview, musicHold) +
            scaleViews.flatMap { listOf(it.first, it.second) }).forEach { take(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        val actions = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_secondary_button)
            setPadding(dp(12), 0, dp(12), 0)
        }
        separators.text = "显示分隔线"
        separators.textSize = 16f
        separators.minHeight = dp(48)
        separators.switchPadding = dp(12)
        separators.setTextColor(color(R.color.settings_text))
        actions.addView(separators, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        heading(page, "外观与风格", "背景、比例与显示细节，按你的习惯调整", actions)

        val modes = card(page, "背景形态", "四种形态，选择后显示对应参数")
        add(modes, modeRow, 18)

        val left = column()
        val right = column()
        page.addView(settingsColumns(activity, left, right), params())
        val sizing = card(left, "尺寸与比例", "分别调整圆角、图标和文字大小")
        scales.forEachIndexed { index, (label, _, _) ->
            if (index > 0) divider(sizing)
            val (value, seek) = scaleViews[index]
            val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(text(label, 17f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            value.textSize = 16f
            value.gravity = Gravity.CENTER
            value.setPadding(dp(12), dp(8), dp(12), dp(8))
            value.setTextColor(color(R.color.settings_accent))
            value.background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
            line.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
            add(sizing, line, 12)
            seek.contentDescription = label
            seek.minimumHeight = dp(48)
            add(sizing, seek, 4)
        }
        val music = card(left, "音乐信息", "滚到开头或结尾后，停顿多久再反向滚动")
        musicHold.setPadding(0, dp(6), 0, dp(6))
        add(music, musicHold, 8)
        val lighting = card(right, "日夜与透明度", "按当前背景形态显示可用选项")
        add(lighting, followNight, 16)
        divider(lighting)
        add(lighting, overlay, 14)
        add(lighting, day, 14)
        add(lighting, night, 14)
        add(lighting, preview, 20)
    }
}
