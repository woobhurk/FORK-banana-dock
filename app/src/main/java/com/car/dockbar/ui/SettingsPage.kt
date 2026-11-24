package com.car.dockbar.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.Prefs
import android.content.res.Configuration
import android.content.ContextWrapper
import android.view.LayoutInflater

/** 单个普通设置页面的生命周期管理基类。 */
abstract class SettingsPage(
    protected val activity: MainActivity,
    val key: String,
    val root: LinearLayout,
) : ContextWrapper(activity) {
    protected val main = Handler(Looper.getMainLooper())
    protected val layoutInflater: LayoutInflater get() = activity.layoutInflater
    protected val currentFocus: View? get() = activity.currentFocus
    protected fun <T : View> findViewById(id: Int): T = root.findViewById(id)
    open fun create() {}
    open fun buildLayout() {}
    open fun onShown() {}
    open fun onResume() {}
    open fun onConfigurationChanged(newConfig: Configuration) {}
    open fun onDestroy() { main.removeCallbacksAndMessages(null) }
    private var lastToastMs = 0L

    protected fun seek(after: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = after()
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    protected fun addSwitchRow(
        parent: LinearLayout,
        label: String,
        checked: Boolean,
        reloadDock: Boolean = true,
        onChange: (Boolean) -> Unit
    ): SwitchCompat {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sw = SwitchCompat(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            switchPadding = dp(12)
            isChecked = checked
        }
        sw.setOnCheckedChangeListener { _, v ->
            onChange(v)
            if (reloadDock) reloadService()
        }
        row.addView(sw, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return sw
    }

    protected fun addSeekRow(
        parent: LinearLayout,
        label: String,
        progress: Int,
        max: Int,
        format: (Int) -> String,
        reloadDock: Boolean = true,
        onChange: (Int) -> Unit,
        onProgress: ((Int) -> Unit)? = null,
        separateValue: Boolean = false
    ): LinearLayout {
        // ★ v3.13.16：把「标签 + 滑杆」包进一个容器并返回 —— 便于按背景形态整体显隐
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val tv = TextView(this).apply {
            text = if (separateValue) format(progress) else "$label：${format(progress)}"
            setTextColor(ContextCompat.getColor(this@SettingsPage, R.color.settings_secondary))
            textSize = 14f
        }
        if (separateValue) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@SettingsPage).apply {
                    text = label
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(this@SettingsPage, R.color.settings_text))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                tv.setTextColor(ContextCompat.getColor(this@SettingsPage, R.color.settings_accent))
                tv.setPadding(dp(8), dp(4), dp(8), dp(4))
                tv.background = ContextCompat.getDrawable(this@SettingsPage, R.drawable.bg_field)
                addView(tv)
            })
        } else {
            box.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val sb = SeekBar(this).apply {
            this.max = max
            this.progress = progress
            contentDescription = label
            minimumHeight = dp(48)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = if (separateValue) format(p) else "$label：${format(p)}"
                onProgress?.invoke(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                onChange(sb.progress)
                if (reloadDock) reloadService()
            }
        })
        box.addView(sb, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        parent.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return box
    }

    protected fun addEditRow(
        parent: LinearLayout,
        label: String,
        value: String,
        onCommit: (String) -> Unit
    ): EditText {
        val tv = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@SettingsPage, R.color.settings_secondary))
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        }
        parent.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val et = EditText(this).apply {
            setText(value)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onCommit(et.text.toString().trim())
        }
        parent.addView(et, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        return et
    }

    protected fun addColorPresetRow(
        parent: LinearLayout,
        label: String,
        current: Int,
        onPick: (Int) -> Unit
    ) {
        val tv = TextView(this).apply {
            text = "$label：#${Integer.toHexString(current).uppercase()}"
            setTextColor(ContextCompat.getColor(this@SettingsPage, R.color.settings_secondary))
            textSize = 14f
        }
        parent.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((index, preset) in Prefs.LYRIC_COLOR_PRESETS.withIndex()) {
            val hex = "#${Integer.toHexString(preset).uppercase()}"
            val b = Button(this).apply {
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                contentDescription = "$label：$hex"
                isSelected = preset == current
                fun swatch(stroke: Int, color: Int) = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(preset)
                    setStroke(dp(stroke), color)
                }
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), swatch(3,
                        ContextCompat.getColor(this@SettingsPage, R.color.settings_accent)))
                    addState(intArrayOf(), swatch(1,
                        ContextCompat.getColor(this@SettingsPage, R.color.settings_outline)))
                }
                setOnClickListener { chosen ->
                    onPick(preset)
                    for (slot in 0 until row.childCount) {
                        val option = row.getChildAt(slot)
                        option.isSelected = option === chosen
                    }
                    tv.text = "$label：$hex"
                    sendLyricUpdate()
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index > 0) marginStart = dp(4)
            })
        }
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
    }

    protected fun reloadService() {
        runCatching {
            sendBroadcast(Intent(DockOverlayService.ACTION_RELOAD).setPackage(packageName))
        }
    }

    protected fun sendLyricUpdate() {
        runCatching {
            sendBroadcast(Intent(LyricOverlayService.ACTION_UPDATE).setPackage(packageName))
        }
    }

    protected fun toastOnce(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastMs < 4000) return
        lastToastMs = now
        runCatching { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
    }
}
