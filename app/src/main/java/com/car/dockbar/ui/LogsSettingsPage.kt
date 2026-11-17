package com.car.dockbar.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.App
import com.car.dockbar.R
import com.car.dockbar.config.VehicleChannel
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.util.CrashHandler
import com.car.dockbar.util.LogUploader
import com.car.dockbar.util.Logx
import com.car.dockbar.util.NativeCrashHandler
import com.car.dockbar.util.Prefs
import com.car.dockbar.util.PrivilegeProbe
import com.car.dockbar.util.StartupPermissions
import com.car.dockbar.vehicle.VehicleBridge
import com.car.dockbar.vehicle.VehicleController
import com.car.dockbar.vehicle.HvacCommands
import com.car.dockbar.vehicle.HvacDiagnostics
import com.car.dockbar.vehicle.HvacValidationMode

/**
 * 管理诊断、车控通道及运行日志设置。
 *
 * 卡片由 activity_main.xml 中引入的 [R.id.cardLogs] 提供。本页面管理其中的控件，
 * 包括 Logx 订阅和有容量上限的显示缓冲区。
 * MainActivity 仅负责创建、显示本页面及转发生命周期事件。
 */
internal class LogsSettingsPage(activity: MainActivity) :
    SettingsPage(activity, MainActivity.SECTION_LOGS) {

    override val root: LinearLayout
        get() = findViewById(R.id.cardLogs)

    override fun buildLayout() { LogsSettingsLayout(activity).rebuild() }

    private lateinit var tvBackend: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var llChannels: LinearLayout

    private var tvStateRef: TextView? = null
    private var created = false
    @Volatile private var destroyed = false
    private val callbackLock = Any()

    private var logBuffer = StringBuilder()
    private var logLineCount = 0
    private var scrollPending = false
    private val maxLogLines = 200

    private val logListener: (String) -> Unit = { line ->
        postUi { appendLog(line) }
    }

    private class ChannelRow(
        val channel: VehicleChannel,
        val etFunc: EditText,
        val etZone: EditText,
        val tvResult: TextView,
        val etWrite: EditText
    )

    private val rows = mutableListOf<ChannelRow>()

    override fun create() {
        if (created) return
        created = true
        bindViews()
        buildChannelRows()
        wireActions()
        addLogUploadSection()
        addHvacSubscriptionAction()
        addHvacValidationActions()

        Logx.addListener(logListener)
        Logx.snapshot().forEach(::appendLog)
        appendLog("—— 打开配置页 ——")

        // 保留 MainActivity 原有的崩溃取证行为：显示一次后清除标记，
        // 避免后续再次进入时重复展示同一份崩溃记录。
        val crash = CrashHandler.read(this)
        if (crash != null) {
            appendLog("⚠⚠ 上次闪退堆栈：\n$crash")
            CrashHandler.clear(this)
            Logx.e("检测到上次闪退，已把堆栈显示在下方日志区")
        }
        refreshBackendStatus()
    }

    override fun onResume() {
        if (created) {
            refreshBackendStatus()
            refreshState?.invoke()
        }
    }

    override fun onDestroy() {
        synchronized(callbackLock) { destroyed = true }
        if (created) Logx.removeListener(logListener)
        super.onDestroy()
        scrollPending = false
        refreshState = null
    }

    /** 页面销毁后，后台任务不得再向旧页面的更新队列添加任务。 */
    private fun postUi(action: () -> Unit) {
        synchronized(callbackLock) {
            if (!destroyed) main.post { if (!destroyed) action() }
        }
    }

    private fun bindViews() {
        tvBackend = findViewById(R.id.tvBackend)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        llChannels = findViewById(R.id.llChannels)
    }

    /** 原先由 MainActivity.wireActions() 绑定的静态日志及通道操作。 */
    private fun wireActions() {
        findViewById<Button>(R.id.btnReadAll).setOnClickListener { readAllChannels() }
        findViewById<Button>(R.id.btnResetChannels).setOnClickListener {
            VehicleChannels.ALL.forEach { ch ->
                VehicleChannels.save(this, ch.key, ch.defFunc, ch.defZone)
            }
            rows.forEach { row ->
                row.etFunc.setText(hex(row.channel.defFunc))
                row.etZone.setText(row.channel.defZone.toString())
            }
            Logx.d("通道已恢复默认值")
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Logx.clear()
            logBuffer = StringBuilder()
            logLineCount = 0
            tvLog.text = ""
            (svLog as? LogTailScrollView)?.resumeFollowing()
        }
    }

    private fun refreshBackendStatus() {
        tvBackend.text = "后端：${VehicleBridge.backend.label}" +
            "　温度通道：${VehicleBridge.floatChannelName}" +
            if (Prefs.simulate(this)) "（模拟模式已开启，忽略真实通道）" else ""
    }

    // =====================================================================
    // 车控通道行
    // =====================================================================

    private fun buildChannelRows() {
        llChannels.removeAllViews()
        rows.clear()

        for (ch in VehicleChannels.ALL) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }

            val label = TextView(this).apply {
                text = "${ch.label}　${ch.note}"
                setTextColor(0xFF9AA1AE.toInt())
                textSize = 11f
            }
            box.addView(label)

            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val etFunc = field("0x10020100", 1.3f)
            val etZone = field("8", 0.7f)
            etZone.inputType = InputType.TYPE_CLASS_NUMBER
            row1.addView(etFunc, lp(0f, 1.3f))
            row1.addView(etZone, lp(dp(6).toFloat(), 0.7f))
            box.addView(row1)

            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val tvResult = TextView(this).apply {
                text = "当前值：—"
                setTextColor(0xFFEDEFF3.toInt())
                textSize = 12f
                // 保存按钮（统一保存）
                // ── [S31] 保存全部通道 ───────────────────────
                val btnSave = smallBtn("保存全部通道") { saveAllChannels() }
                llChannels.addView(btnSave, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })

                // ★ v1.4.52：暴力扫描整片 ID 空间，找出「哪些 functionId 真的有值」。
                //   内外循环 0x10030100 在本车读回恒为 0xff（HAL 没暴露），写也全失败，
                //   强烈提示 functionId 本身就不对（oneOS_Dock 那张表是别的车型/版本）。
                //   与其猜，不如扫一遍：读通道是安全操作（读不到只返回 0xff 哨兵，不会崩）。
                // ── [S32] ★ 扫描车控通道（找出内外循环真实 ID） ────────
                val btnScan = smallBtn("★ 扫描车控通道（找出内外循环真实 ID）") { btn ->
                    btn.isEnabled = false
                    btn.alpha = 0.45f
                    tvStateRef?.text = "正在扫描车控通道（约 2560 次读 × 3 个 zone，10~40 秒）…"
                    Thread {
                        val r = runCatching { VehicleController.scanChannels(this@LogsSettingsPage) }
                            .getOrElse { "扫描失败：${it.javaClass.simpleName}: ${it.message}" }
                        Logx.d(r)
                        postUi {
                            btn.isEnabled = true
                            btn.alpha = 1f
                            tvStateRef?.text = r
                            LogUploader.upload(this@LogsSettingsPage, "channel_scan", r, "车控通道扫描")
                        }
                    }.apply { isDaemon = true }.start()
                }
                llChannels.addView(btnScan, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }
        }
    }

    private fun saveAllChannels() {
        rows.forEach { row ->
            val func = parseNum(row.etFunc.text.toString())
            val zone = row.etZone.text.toString().trim().toIntOrNull() ?: row.channel.defZone
            VehicleChannels.save(this, row.channel.key, func, zone)
        }
        Logx.d("通道配置已保存：${rows.size} 条")
        Thread {
            runCatching { VehicleBridge.reset() }
            runCatching { VehicleController.init(this) }
                .onFailure { Logx.e("重连车控失败", it) }
        }.start()
    }

    private fun readChannel(ch: VehicleChannel, etFunc: EditText, etZone: EditText, out: TextView) {
        doRead(ch, parseNum(etFunc.text.toString()),
            etZone.text.toString().trim().toIntOrNull() ?: ch.defZone, out)
    }

    private fun writeChannel(
        ch: VehicleChannel,
        etFunc: EditText,
        etZone: EditText,
        etVal: EditText,
        out: TextView
    ) {
        val func = parseNum(etFunc.text.toString())
        val zone = etZone.text.toString().trim().toIntOrNull() ?: ch.defZone
        val index = etVal.text.toString().trim().toIntOrNull() ?: 1
        val raw = (func and 0xFFFFFF00.toInt()) or (index and 0xFF)
        HvacCommands.executor.execute {
            val ok = VehicleBridge.writeInt(func, zone, raw)
            postUi { out.text = "写入调用：0x${raw.toString(16)} → ${if (ok) "接受，生效需真实观测" else "未接受，原因见日志"}" }
        }
    }

    private fun doRead(@Suppress("UNUSED_PARAMETER") ch: VehicleChannel, func: Int, zone: Int, out: TextView) {
        Thread {
            if (!VehicleBridge.isReady) VehicleBridge.init(this)
            val value = VehicleBridge.readInt(func, zone)
            postUi {
                out.text = if (value == null) {
                    "当前值：读取失败（0x${func.toString(16)} zone=$zone）"
                } else {
                    "当前值：$value (0x${value.toString(16)})"
                }
            }
        }.start()
    }

    private fun readAllChannels() {
        Logx.d("开始读取全部通道…")
        rows.forEach { row ->
            Thread {
                if (!VehicleBridge.isReady) VehicleBridge.init(this)
                val func = parseNum(row.etFunc.text.toString())
                val zone = row.etZone.text.toString().trim().toIntOrNull() ?: row.channel.defZone
                val value = VehicleBridge.readInt(func, zone)
                postUi {
                    row.tvResult.text = if (value == null) "当前值：读取失败"
                    else "当前值：$value (0x${value.toString(16)})"
                    Logx.d("${row.channel.label}  func=0x${func.toString(16)} zone=$zone → ${value ?: "null"}")
                }
            }.start()
        }
    }

    // =====================================================================
    // 日志上传与诊断工具
    // =====================================================================

    private var refreshState: (() -> Unit)? = null

    private fun addHvacSubscriptionAction() {
        root.addView(smallBtn("日常空调同步通道") {
            AlertDialog.Builder(activity)
                .setTitle("日常空调同步通道")
                .setMessage("本车已验证风量、A/C、温度的读取与事件；循环仅验证读取，风向 0/8 的语义仍未确定，不代表所有车型均可用。\n" +
                    "⚠ 温度会额外启用浮点 Customize/native 读取和事件，可能使进程直接闪退，Java 异常捕获无法保护；厂商其他事件回调也有 native 风险，请在停车安全时配置。\n" +
                    "原有温度监听、全量监听开关及其基线仍保留；全部不选仅关闭新增日常通道。\n" +
                    "保存仅调整订阅与读取，不自动写入车控或补权，也不改变车控写入开关。临时验证结束不会清除此配置。")
                .setPositiveButton("选择通道") { _, _ ->
                    val labels = arrayOf(
                        "风量（手动 + AUTO）",
                        "A/C",
                        "主副驾温度（含浮点读取，native 风险）",
                        "循环（本车仅验证读取）",
                        "风向（0/8 语义未定）"
                    )
                    val groups = arrayOf(
                        setOf("fan_manual", "fan_auto"),
                        setOf("ac"),
                        setOf("temp_driver", "temp_passenger"),
                        setOf("recirc"),
                        setOf("air_direction")
                    )
                    val saved = Prefs.hvacEventKeys(this)
                    val selected = BooleanArray(groups.size) { index ->
                        groups[index].any { it in saved }
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("日常通道（可全部不选）")
                        .setMultiChoiceItems(labels, selected) { _, which, checked ->
                            selected[which] = checked
                        }
                        .setPositiveButton("保存并应用") { _, _ ->
                            val keys = groups.indices.filter { selected[it] }
                                .flatMapTo(mutableSetOf()) { groups[it] }
                            Prefs.setHvacEventKeys(this, keys)
                            VehicleController.reloadHvacSubscriptions(this)
                            toastOnce(if (keys.isEmpty()) "新增日常空调同步已关闭，原有基线监听保留"
                                else "日常空调同步已保存并请求应用")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                .setNegativeButton("取消", null)
                .show()
        })
    }

    /** 验证会话属于 Controller，切页或销毁本页面不会停止车上操作取证。 */
    private fun addHvacValidationActions() {
        root.addView(smallBtn("原车空调接口验证（2分钟）") {
            val modes = HvacValidationMode.values()
            var selected = 0
            AlertDialog.Builder(activity)
                .setTitle("选择空调验证范围")
                .setSingleChoiceItems(arrayOf(
                    "风量（默认）：手动 / 自动风量",
                    "常用空调：风量 + A/C / 循环 / 风向",
                    "含温度（高风险）：额外调用浮点 Customize/native"
                ), selected) { _, which -> selected = which }
                .setPositiveButton("下一步") { _, _ ->
                    val mode = modes[selected]
                    AlertDialog.Builder(activity)
                        .setTitle("开启2分钟验证：${mode.name}")
                        .setMessage("仅临时启用所选通道的订阅、能力查询与主动读取，不开启写入或补权，不修改原有功能开关。\n" +
                            "开启后可切页，在原车空调或 Dock 手动操作，再回来查看报告。命令接受不等于生效，无事件不等于权限拒绝。\n" +
                            if (mode == HvacValidationMode.TEMPERATURE)
                                "⚠ 温度模式会额外触发浮点 Customize/native 读取和事件，可能使进程直接闪退，Java 异常捕获无法保护。请只在停车安全时验证。"
                            else "⚠ 厂商事件回调可能导致 native 闪退，请在停车安全时验证。本模式不新增温度浮点主动读取，原有监听（含用户原已开启的温度监听）保持。")
                        .setPositiveButton("开始验证") { _, _ ->
                            VehicleController.startHvacValidation(this, mode)
                            toastOnce("空调验证已请求；请操作后查看报告")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                .setNegativeButton("取消", null)
                .show()
        })
        root.addView(smallBtn("停止空调接口验证") {
            VehicleController.stopHvacValidation()
            toastOnce("空调验证已停止，可查看报告")
        })
        root.addView(smallBtn("查看空调验证结果") { showHvacValidationReport() })
    }

    private fun showHvacValidationReport() {
        val report = HvacDiagnostics.report()
        val content = TextView(this).apply {
            text = report
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(activity)
            .setTitle("空调验证报告（HVAC_TEST）")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制报告") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("HVAC_TEST", report))
                toastOnce("已复制空调验证报告")
            }
            .show()
    }

    private fun addLogUploadSection() {
        val page = root
        page.addView(TextView(this).apply {
            id = R.id.settingsLogUploadTitle
            text = "日志上传（远程排查）"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 14f
            typeface = AppFont.bold(this@LogsSettingsPage)
            setPadding(0, dp(16), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val tvHint = TextView(this).apply {
            id = R.id.settingsLogUploadHint
            text = "有网时日志自动上传（大约每 1.5 秒一批），不用点按钮。\n" +
                "没网先记在本机，恢复网络后补传。闪退仍会单独上传。\n" +
                "只含 App 日志、崩溃堆栈与车控原始值，不含账号。"
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        }
        page.addView(tvHint)

        val urlRow = addEditRow(
            page,
            "上传地址（POST JSON）",
            Prefs.logUploadUrl(this).ifBlank { LogUploader.DEFAULT_URL }
        ) { value ->
            Prefs.setLogUploadUrl(this, value)
            tvHint.text = "已保存地址：${LogUploader.endpoint(this)}"
        }
        urlRow.id = R.id.settingsLogUploadAddress

        addSwitchRow(page, "启用日志上传", Prefs.logUploadEnabled(this), reloadDock = false) {
            Prefs.setLogUploadEnabled(this, it)
            tvHint.text = if (it) "已启用 → ${LogUploader.endpoint(this)}"
            else "已关闭：不会再上传任何内容"
        }
        page.getChildAt(page.childCount - 1).id = R.id.settingsLogUploadSwitch

        addSwitchRow(
            page,
            "⚠ 温度事件监听（实验，可能闪退）",
            Prefs.vehicleTempWatcherEnabled(this),
            reloadDock = false
        ) {
            Prefs.setVehicleTempWatcherEnabled(this, it)
            Logx.w(if (it) "温度事件监听 → 开启（若闪退会自动关回）" else "温度事件监听 → 关闭")
        }
        addSwitchRow(
            page,
            "★ 车控写入（关=只能读状态，调不动空调）",
            Prefs.vehicleWriteEnabled(this),
            reloadDock = false
        ) {
            Prefs.setVehicleWriteEnabled(this, it)
            Logx.w(if (it) "车控写入 → 开启（若闪退会自动熔断关回）" else "车控写入 → 关闭：只能读取状态")
        }
        addSwitchRow(
            page,
            "★ 车控通道（关=完全不调厂商接口，排查闪退）",
            Prefs.vehicleApiEnabled(this),
            reloadDock = true
        ) {
            Prefs.setVehicleApiEnabled(this, it)
            Logx.w(if (it) "车控通道 → 开启" else "车控通道 → 关闭：不调用任何厂商接口，空调状态不再刷新")
        }

        val tvState = TextView(this).apply {
            id = R.id.settingsLogState
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            setPadding(0, dp(12), 0, dp(12))
        }
        tvStateRef = tvState
        page.addView(tvState)

        fun refresh() {
            val crashFile = LogUploader.javaCrashFile(this)
            val nativeFile = LogUploader.nativeCrashFile(this)
            val level = Prefs.safeLevel(this)
            tvState.text = buildString {
                append("设备 ").append(LogUploader.deviceId(this@LogsSettingsPage)).append('\n')
                append("Native 崩溃捕获：").append(NativeCrashHandler.statusText()).append('\n')
                append("安全等级：L").append(level).append("（").append(App.safeLevelDesc(level)).append("）\n")
                append("待补传崩溃：")
                append(when {
                    LogUploader.hasPendingCrash(this@LogsSettingsPage) -> "有 ⚠"
                    crashFile.exists() || nativeFile.exists() -> "无（已上传）"
                    else -> "无"
                }).append('\n')
                append("上次结果：").append(LogUploader.lastResult.ifBlank { "尚无上传记录" })
                append("\n权限检查：").append(StartupPermissions.lastReport.ifBlank { "尚无检查记录" })
            }
        }
        refreshState = ::refresh
        refresh()

        val actionRow = LinearLayout(this).apply {
            id = R.id.settingsLogTransferActions
            orientation = LinearLayout.HORIZONTAL
        }
        actionRow.addView(smallBtn("测试上传") { button ->
            Prefs.setLogUploadUrl(this, urlRow.text.toString().trim())
            tvState.text = "正在测试上传…（最长 10 秒，完成后自动刷新状态）"
            button.isEnabled = false
            button.alpha = 0.45f
            LogUploader.testUpload(this)
            main.postDelayed({
                button.isEnabled = true
                button.alpha = 1f
                refresh()
            }, 3000)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(smallBtn("立即上传日志") { button ->
            Prefs.setLogUploadUrl(this, urlRow.text.toString().trim())
            tvState.text = "正在上传日志…（最长 10 秒，完成后自动刷新状态）"
            button.isEnabled = false
            button.alpha = 0.45f
            LogUploader.uploadLogTail(this, "manual", "主界面手动上传")
            main.postDelayed({
                button.isEnabled = true
                button.alpha = 1f
                refresh()
            }, 3000)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        actionRow.addView(smallBtn("诊断提权环境") { button ->
            tvState.text = "正在探测 adb / root / 车控权限…（约 10 秒）"
            button.isEnabled = false
            button.alpha = 0.45f
            Thread {
                val result = runCatching { PrivilegeProbe.report(this) }
                    .getOrElse { "探测失败：${it.javaClass.simpleName}: ${it.message}" }
                Logx.d(result)
                postUi {
                    tvState.text = result
                    button.isEnabled = true
                    button.alpha = 1f
                    LogUploader.upload(this, "privilege_probe", result, "提权环境诊断")
                }
            }.apply { isDaemon = true }.start()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        page.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        val actionRow2 = LinearLayout(this).apply {
            id = R.id.settingsLogDiagnosticActions
            orientation = LinearLayout.HORIZONTAL
        }
        actionRow2.addView(smallBtn("检查并补齐权限") { button ->
            tvState.text = "正在检查并补齐核心权限…（最长 45 秒，不修改车控开关）"
            button.isEnabled = false
            button.alpha = 0.45f
            Thread {
                val result = runCatching { StartupPermissions.grantMissing(this) }
                    .getOrElse { "权限检查失败：${it.javaClass.simpleName}: ${it.message}" }
                Logx.d(result)
                postUi {
                    tvState.text = result
                    button.isEnabled = true
                    button.alpha = 1f
                    LogUploader.upload(this, "adb_grant", result, "核心权限检查与补齐")
                }
            }.apply { isDaemon = true }.start()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actionRow2.addView(smallBtn("清除崩溃记录") {
            LogUploader.clearCrashEvidence(this)
            CrashHandler.clear(this)
            Logx.d("崩溃取证已清除")
            refresh()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        page.addView(actionRow2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        page.addView(smallBtn("★ 扫描车控通道（找真实通道 ID）") { button ->
            tvState.text = "正在扫描车控通道（约 7680 次读，10~40 秒）…"
            button.isEnabled = false
            button.alpha = 0.45f
            Thread {
                val result = runCatching { VehicleController.scanChannels(this) }
                    .getOrElse { "扫描失败：${it.javaClass.simpleName}: ${it.message}" }
                Logx.d(result)
                postUi {
                    tvState.text = result
                    button.isEnabled = true
                    button.alpha = 1f
                    LogUploader.upload(this, "channel_scan", result, "车控通道扫描")
                }
            }.apply { isDaemon = true }.start()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        page.addView(smallBtn("抓循环取证（系统 Dock/空调 App）") { button ->
            tvState.text = "取证窗口已开启（2 分钟），请操作系统的内外循环…"
            button.isEnabled = false
            button.alpha = 0.45f
            VehicleController.openEvidence(120_000L)
            Thread {
                val result = runCatching { PrivilegeProbe.grabRecircEvidence(90_000) }
                    .getOrElse { "取证失败：${it.javaClass.simpleName}: ${it.message}" }
                VehicleController.closeEvidence()
                Logx.d(result)
                postUi {
                    tvState.text = result
                    button.isEnabled = true
                    button.alpha = 1f
                    LogUploader.upload(this, "recirc_evidence", result, "内外循环专项取证")
                }
            }.apply { isDaemon = true }.start()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        addSwitchRow(
            page,
            "★ 实验：温度走 Customize 浮点通道（1.4.47 曾误禁）",
            Prefs.tempCustomizeWriteEnabled(this),
            reloadDock = true
        ) {
            Prefs.setTempCustomizeWriteEnabled(this, it)
            Logx.w(if (it) "温度 Customize 写入 → 开启（实验）。若崩溃，取证会给出真凶并自动熔断"
            else "温度 Customize 写入 → 关闭")
        }

        page.addView(smallBtn("退出安全模式（恢复全功能）") {
            Prefs.setSafeLevel(this, 0)
            Logx.d("已手动退出安全模式，恢复全功能")
            reloadService()
            refresh()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
    }

    private fun field(hint: String, weight: Float): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(0xFFEDEFF3.toInt())
        setHintTextColor(0xFF9AA1AE.toInt())
        textSize = 12f
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = ContextCompat.getDrawable(this@LogsSettingsPage, R.drawable.bg_field)
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun smallBtn(text: String, onClick: (Button) -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = ContextCompat.getDrawable(this@LogsSettingsPage, R.drawable.bg_button_primary)
        setTextColor(ContextCompat.getColor(this@LogsSettingsPage, R.color.settings_on_accent))
        setOnClickListener { onClick(this) }
    }

    private fun lp(marginStartPx: Float, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = marginStartPx.toInt()
        }

    // =====================================================================
    // 有容量上限的实时日志显示
    // =====================================================================

    /** 增量追加日志，仅在行数或内容长度超过限制时裁剪。 */
    private fun appendLog(line: String) {
        logBuffer.append(line).append('\n')
        logLineCount++
        if (logLineCount > maxLogLines || logBuffer.length > 60_000) {
            val kept = logBuffer.lineSequence().filter { it.isNotEmpty() }.toList().takeLast(maxLogLines)
            logBuffer = StringBuilder(kept.joinToString("\n", postfix = "\n"))
            logLineCount = kept.size
            tvLog.text = logBuffer
        } else {
            if (tvLog.text.isEmpty()) tvLog.text = line else tvLog.append("\n$line")
        }
        if (!scrollPending && root.isShown && svLog.isShown && currentFocus !is EditText &&
            (svLog as? LogTailScrollView)?.shouldFollowTail() != false
        ) {
            scrollPending = true
            postUi {
                scrollPending = false
                if (root.isShown && svLog.isShown && currentFocus !is EditText &&
                    (svLog as? LogTailScrollView)?.shouldFollowTail() != false
                ) {
                    svLog.scrollTo(0, tvLog.height)
                }
            }
        }
    }
}
