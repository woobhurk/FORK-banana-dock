package com.car.dockbar.ui

import android.content.ActivityNotFoundException
import android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.config.BackupGroup
import com.car.dockbar.config.ConfigBackupFormat

internal class ConfigBackupSettingsPage(activity: MainActivity, root: LinearLayout) :
    SettingsPage(activity, MainActivity.SECTION_BACKUP, root) {
    private val session get() = activity.configBackup
    private var restoreDialog: AlertDialog? = null

    override fun create() {
        root.addView(label("配置备份", 30f, title = true), params())
        root.addView(label("将个性化配置导出为 JSON，或从文件选取要恢复的配置。", 15f), params(12))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, params())
        session.state.observe(activity) { state ->
            activity.updateBackupNavigation(state is ConfigBackupSession.State.Busy && state.writingPreferences)
            body.removeAllViews()
            when (state) {
                is ConfigBackupSession.State.Ready -> showActions(body, state)
                is ConfigBackupSession.State.Busy -> body.addView(label(state.message, 16f).apply {
                    accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
                }, params(18))
                is ConfigBackupSession.State.Review -> showPreview(body, state)
            }
        }
    }

    private fun showActions(parent: LinearLayout, state: ConfigBackupSession.State.Ready) {
        state.message?.let { message ->
            parent.addView(label(message, 16f).apply { accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }, params(18))
        }
        if (state.recoveryAvailable) {
            val recovery = card(parent, "本机恢复点")
            recovery.addView(label("有一份导入前的配置可恢复。", 15f), params(10))
            recovery.addView(button("恢复导入前的配置") {
                restoreDialog = AlertDialog.Builder(activity)
                    .setTitle("恢复导入前的配置？")
                    .setMessage("将恢复上次导入涉及的设置。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("恢复") { _, _ -> session.restore() }
                    .show()
            }, params(12))
        }
        val importing = card(parent, "导入")
        importing.addView(button("选择配置文件") {
            launchPicker { activity.configImport.launch(arrayOf("*/*")) }
        }.apply { isEnabled = !state.recoveryAvailable }, params(12))

        val exporting = card(parent, "导出")
        val action = button("导出所选配置") {
            session.pendingExport = session.exportGroups.toSet()
            launchPicker { activity.configExport.launch("Banana_Dock-config-v${ConfigBackupFormat.VERSION}.json") }
        }.apply { isEnabled = session.exportGroups.isNotEmpty() }
        for (group in BackupGroup.entries) {
            exporting.addView(choice(group.title, group in session.exportGroups) { checked ->
                if (checked) session.exportGroups.add(group) else session.exportGroups.remove(group)
                action.isEnabled = session.exportGroups.isNotEmpty()
            }, params())
        }
        exporting.addView(action, params(12))
    }

    private fun showPreview(parent: LinearLayout, review: ConfigBackupSession.State.Review) {
        val preview = review.preview
        val selected = review.selected
        val card = card(parent, "导入预览")
        preview.sourceAppVersion?.takeUnless(String::isBlank)?.let {
            card.addView(label("来源应用版本 $it", 14f), params(8))
        }
        if (preview.changes.isEmpty()) card.addView(label("文件没有可应用的变更。", 15f), params(12))
        val apply = button("确认导入") { session.apply(review) }.apply { isEnabled = selected.isNotEmpty() }
        for ((group, changes) in preview.changes) {
            card.addView(choice(group.title, group in selected) { checked ->
                if (checked) selected.add(group) else selected.remove(group)
                apply.isEnabled = selected.isNotEmpty()
            }, params(10))
            card.addView(label(changes.joinToString("\n") { change -> change.title }, 14f), params())
        }
        if (preview.warnings.isNotEmpty()) {
            val warning = card(parent, "导入提示")
            warning.addView(label(preview.warnings.joinToString("\n"), 14f), params(10))
        }
        parent.addView(apply, params(18))
        parent.addView(button("取消", primary = false) { session.cancelReview() }, params(8))
    }

    private fun launchPicker(action: () -> Unit) {
        try {
            action()
        } catch (_: ActivityNotFoundException) {
            session.pendingExport = null
            session.pickerFailed()
        }
    }

    override fun onDestroy() {
        restoreDialog?.dismiss()
        super.onDestroy()
    }

    private fun card(parent: LinearLayout, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        addView(label(title, 21f, title = true), params())
        parent.addView(this, params(18))
    }

    private fun label(value: String, size: Float, title: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(if (title) R.color.settings_text else R.color.settings_secondary))
        if (title) typeface = AppFont.bold(activity)
    }

    private fun choice(title: String, selected: Boolean, onChanged: (Boolean) -> Unit) = AppCompatCheckBox(this).apply {
        text = title
        textSize = 16f
        minimumHeight = dp(48)
        setTextColor(color(R.color.settings_text))
        isChecked = selected
        setOnCheckedChangeListener { _, checked -> onChanged(checked) }
    }

    private fun button(title: String, primary: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 16f
        minimumHeight = dp(48)
        setTextColor(color(if (primary) R.color.settings_on_accent else R.color.settings_text))
        setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_field)
        setOnClickListener { action() }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun params(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
}
