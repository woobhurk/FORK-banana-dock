package com.car.dockbar.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import com.car.dockbar.util.DiagnosticLogExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.car.dockbar.App
import com.car.dockbar.R
import com.car.dockbar.config.VehicleChannels.AC_MAX_KEY
import com.car.dockbar.util.CrashHandler
import com.car.dockbar.util.LogUploader
import com.car.dockbar.util.Logx
import com.car.dockbar.util.NativeCrashHandler
import com.car.dockbar.util.Prefs
import com.car.dockbar.util.PrivilegeProbe
import com.car.dockbar.util.StartupPermissions
import com.car.dockbar.vehicle.VehicleBridge
import com.car.dockbar.vehicle.VehicleController
import com.car.dockbar.vehicle.HvacDiagnostics
import com.car.dockbar.vehicle.HvacValidationMode

/**
 * 管理诊断、车控保护和运行日志设置。
 *
 * 卡片由 activity_main.xml 中引入的 [R.id.cardLogs] 提供。本页面管理其中的控件，
 * 包括 Logx 订阅和有容量上限的显示缓冲区。
 * MainActivity 仅负责创建、显示本页面及转发生命周期事件。
 */
internal class LogsSettingsPage(activity: MainActivity) :
    SettingsPage(activity, MainActivity.SECTION_LOGS) {

    override val root: LinearLayout = findViewById(R.id.cardLogs)

    private lateinit var layout: LogsSettingsLayout
    private lateinit var tvLog: TextView
    private lateinit var svLog: LogTailScrollView
    private lateinit var backendStatus: TextView
    private lateinit var exitSafetyMode: Button
    private lateinit var writeSwitch: SwitchCompat
    private lateinit var uploadStatus: TextView
    private lateinit var uploadAddress: EditText
    private lateinit var permissionStatus: TextView
    private lateinit var permissionDetails: LinearLayout
    private lateinit var permissionReport: TextView
    private lateinit var repairPermissions: Button
    private lateinit var clearCrashButton: Button
    private var displayedPermissionCheck: StartupPermissions.CheckResult? = null
    private var displayedBackend: VehicleBridge.Backend? = null
    private var displayedUploadResult: String? = null
    private var repairingPermissions = false
    private var refreshValidation: (() -> Unit)? = null
    private val validationExpiry = Runnable { refreshValidation?.invoke() }
    private var exportLauncher: ActivityResultLauncher<String>? = null
    private var exportRunning = false
    private var exportButton: Button? = null

    @Volatile private var destroyed = false
    private val callbackLock = Any()

    private var logBuffer = StringBuilder()
    private var logLineCount = 0
    private var scrollPending = false
    private val maxLogLines = 200

    private val logListener: (String) -> Unit = { line ->
        postUi {
            appendLog(line)
            if (root.isShown) {
                if (displayedBackend != VehicleBridge.backend) refreshBackendStatus()
                if (displayedPermissionCheck !== StartupPermissions.lastCheck) refreshPermissionStatus()
                if (displayedUploadResult != LogUploader.lastResult) refreshUploadStatus()
            }
        }
    }

    override fun create() {
        if (exportLauncher != null) return
        exportLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) {
                exportButton?.let { setActionEnabled(it, true) }
            } else startExport(uri)
        }
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
    }

    override fun buildLayout() {
        if (::layout.isInitialized) return
        val clear = findViewById<Button>(R.id.btnClearLog)
        layout = LogsSettingsLayout(activity, root)
        addOverview()
        addVehicleProtection()
        addLogUploadSection()
        addPermissionSection()
        addLogConsole(clear)
        addAdvancedTools()

        Logx.addListener(logListener)
        Logx.snapshot().forEach(::appendLog)
        appendLog("—— 打开配置页 ——")

        // 启动判据仍按原语义清除；导出专用归档不参与熔断和上传判定。
        val crash = CrashHandler.read(this)
        if (crash != null) {
            appendLog("⚠⚠ 上次闪退堆栈：\n$crash")
            CrashHandler.archiveForExport(this, crash)
            CrashHandler.clear(this, clearExportArchive = false)
            Logx.e("检测到上次闪退，已把堆栈显示在下方日志区")
        }
        refreshPage()
    }

    override fun onShown() = refreshPage()

    override fun onResume() = refreshPage()

    override fun onDestroy() {
        synchronized(callbackLock) { destroyed = true }
        Logx.removeListener(logListener)
        super.onDestroy()
        exportLauncher = null
        refreshValidation = null
        scrollPending = false
    }

    /** 页面销毁后，后台任务不得再向旧页面的更新队列添加任务。 */
    private fun postUi(action: () -> Unit) {
        synchronized(callbackLock) {
            if (!destroyed) main.post { if (!destroyed) action() }
        }
    }

    private fun refreshPage() {
        if (!::layout.isInitialized) return
        refreshOverview()
        refreshBackendStatus()
        refreshWriteAvailability()
        refreshUploadStatus()
        refreshPermissionStatus()
        refreshValidation?.invoke()
    }

    private fun addOverview() {
        backendStatus = layout.note(layout.overview)
        addDiagnosticExportAction(layout.overview)
        exitSafetyMode = layout.action(layout.overview, "退出安全模式") {
            Prefs.setSafeLevel(this, 0)
            Logx.d("已手动退出安全模式（L0）")
            reloadService()
            refreshOverview()
        }
    }

    private fun refreshOverview() {
        val level = Prefs.safeLevel(this)
        val metrics = layout.metrics
        metrics[0].text = LogUploader.deviceId(this)
        metrics[1].text = NativeCrashHandler.statusText()
        metrics[2].text = "L$level · ${App.safeLevelDesc(level)}"
        metrics[3].text = if (LogUploader.hasPendingCrash(this)) "有" else "无"
        exitSafetyMode.isVisible = level > 0
        clearCrashButton.isVisible = CrashHandler.hasEvidence(this)
    }

    private fun refreshBackendStatus() {
        val backend = VehicleBridge.backend
        displayedBackend = backend
        backendStatus.text = "车控后端：${backend.label}" +
            if (Prefs.simulate(this)) "（模拟模式）" else "　温度路径：${VehicleBridge.floatChannelName}"
    }

    private fun addVehicleProtection() {
        val parent = layout.vehicle
        addSwitchRow(parent, "车控通道", Prefs.vehicleApiEnabled(this)) {
            Prefs.setVehicleApiEnabled(this, it)
            Logx.w(if (it) "车控通道 → 开启" else "车控通道 → 关闭：不调用厂商接口，车控状态不再刷新")
            refreshWriteAvailability()
            refreshValidation?.invoke()
        }.contentDescription = "车控通道"
        layout.note(parent, "关闭后不调用厂商接口，车控状态不再刷新。", 0)
        writeSwitch = addSwitchRow(parent, "车控写入", Prefs.vehicleWriteEnabled(this), reloadDock = false) {
            Prefs.setVehicleWriteEnabled(this, it)
            Logx.w(if (it) "车控写入 → 开启（若闪退会自动熔断关回）" else "车控写入 → 关闭：只能读取状态")
        }.apply { contentDescription = "车控写入" }
        layout.note(parent, "关闭后仍可读取状态，但不能调节车控。", 0)
        addHvacSubscriptionAction(parent)
    }

    private fun refreshWriteAvailability() {
        val enabled = Prefs.vehicleApiEnabled(this)
        writeSwitch.isEnabled = enabled
        (writeSwitch.parent as View).alpha = if (enabled) 1f else 0.5f
    }

    private fun addLogConsole(clear: Button) {
        val body = layout.fold(layout.logs, "查看运行日志", "收起运行日志") { open ->
            if (open && svLog.shouldFollowTail()) svLog.resumeFollowing()
        }
        clear.setOnClickListener {
            Logx.clear()
            logBuffer.setLength(0)
            logLineCount = 0
            tvLog.text = ""
            svLog.resumeFollowing()
        }
        layout.mountConsole(body, clear, svLog, tvLog)
    }

    private fun addPermissionSection() {
        val parent = layout.permissions
        permissionStatus = layout.note(parent)
        repairPermissions = layout.action(parent, "检查并补齐权限", primary = true) { button ->
            repairingPermissions = true
            refreshPermissionStatus()
            runDiagnostic(button, permissionReport, "正在检查并补齐核心权限…（最长 45 秒）",
                "adb_grant", "核心权限检查与补齐", onFinished = {
                    repairingPermissions = false
                    refreshPermissionStatus()
                }) { StartupPermissions.grantMissing(this) }
        }
        permissionDetails = layout.fold(parent, "查看权限详情", "收起权限详情") { refreshPermissionStatus() }
        permissionReport = layout.note(permissionDetails).apply { setTextIsSelectable(true) }
        lateinit var probeReport: TextView
        layout.action(permissionDetails, "诊断提权环境") { button ->
            runDiagnostic(button, probeReport, "正在探测本机 ADB 和车控权限…", "privilege_probe", "提权环境诊断") {
                PrivilegeProbe.report(this)
            }
        }
        probeReport = layout.note(permissionDetails).apply { isVisible = false; setTextIsSelectable(true) }
    }

    private fun refreshPermissionStatus() {
        val result = StartupPermissions.lastCheck
        val retry = result.state == StartupPermissions.CheckState.CORE_MISSING ||
            result.state == StartupPermissions.CheckState.FAILED
        permissionStatus.text = if (repairingPermissions) "正在检查核心权限…" else when (result.state) {
            StartupPermissions.CheckState.NOT_CHECKED -> "尚无核心权限检查结果"
            StartupPermissions.CheckState.CORE_READY -> "上次检查：核心权限已齐"
            StartupPermissions.CheckState.CORE_MISSING -> "上次检查：有核心权限未满足"
            StartupPermissions.CheckState.FAILED -> "上次权限检查失败"
        }
        repairPermissions.text = if (retry) "重试补齐权限" else "检查并补齐权限"
        repairPermissions.isVisible = retry || repairingPermissions || permissionDetails.isVisible
        if (!repairingPermissions && displayedPermissionCheck !== result) permissionReport.text = result.report
        displayedPermissionCheck = result
    }

    private fun addHvacSubscriptionAction(parent: LinearLayout) {
        layout.action(parent, "日常车控状态同步") {
            AlertDialog.Builder(activity)
                .setTitle("日常车控状态同步")
                .setMessage("选中的通道尝试接收原车状态变化，定时读取继续兜底。默认勾选除主副驾温度外的可选通道；当前已保存选择优先，可用“恢复默认”重新套用。\n" +
                    "⚠ 厂商回调及温度浮点读取可能导致进程闪退，请停车后配置；支持情况因车机而异。\n" +
                    "崩溃熔断会清空勾选；恢复前可先导出诊断 ZIP。\n" +
                    "保存仅调整订阅与读取，不自动写入车控或补权，也不改变车控写入开关。临时验证结束不会清除此配置。")
                .setPositiveButton("选择通道") { _, _ ->
                    val labels = arrayOf(
                        "风量（手动 + AUTO）",
                        "A/C",
                        "急速降温（MAX A/C）",
                        "主副驾温度（含浮点读取，native 风险）",
                        "循环（本车仅验证读取）",
                        "风向（0/8 语义未定）",
                        "座椅通风／加热（主驾通风、前排加热）",
                        "副驾座椅通风",
                        "前挡除霜",
                        "后挡除霜",
                        "方向盘加热"
                    )
                    val groups = arrayOf(
                        setOf("fan_manual", "fan_auto"),
                        setOf("ac"),
                        setOf(AC_MAX_KEY),
                        setOf("temp_driver", "temp_passenger"),
                        setOf("recirc"),
                        setOf("air_direction"),
                        Prefs.SEAT_CLIMATE_EVENT_KEYS,
                        setOf("seat_vent_passenger"),
                        setOf("defrost_front"),
                        setOf("defrost_rear"),
                        setOf("steering_heat")
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
                            toastOnce(if (keys.isEmpty()) "日常可选订阅已关闭，原有基线监听保留"
                                else "日常车控状态同步已保存并请求应用")
                        }
                        .setNeutralButton("恢复默认") { _, _ ->
                            Prefs.resetHvacEventKeys(this)
                            VehicleController.reloadHvacSubscriptions(this)
                            toastOnce("已恢复默认：除主副驾温度外的原厂通道已勾选")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 验证会话属于 Controller，切页或销毁本页面不会停止车上操作取证。 */
    private fun addHvacValidationActions(parent: LinearLayout) {
        val section = layout.fold(parent, "车控验证", "收起车控验证") { refreshValidation?.invoke() }
        val status = layout.note(section)
        val start = layout.action(section, "开始 2 分钟验证") {
            val modes = HvacValidationMode.values()
            var selected = 0
            val labels = modes.map { it.label }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("选择车控验证范围")
                .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
                .setPositiveButton("下一步") { _, _ ->
                    val mode = modes[selected]
                    val warning = when (mode.seatWriteMode) {
                        null -> when {
                            mode == HvacValidationMode.AC_MAX ->
                                "请停车操作。开始时只读和订阅，厂商回调可能导致闪退。先在原车空调界面开关并确认读回。" +
                                    "再长按 Dock 主驾温度，用「MAX」分别开、关并核对原车状态，或在本页点「急速降温测试写入」。" +
                                    "不启动验证也可用 Dock 操作原厂 MAX A/C。"
                            mode == HvacValidationMode.POWER ->
                                "请停车操作。开始时只读和订阅，厂商回调可能导致闪退。先在原车空调界面开关电源，确认 Dock 风向图标随状态变化；" +
                                    "再在验证窗口内短按风向，核对原车电源是否切换。状态未知不写入，停止或到期后验证订阅恢复基线，日常短按仍使用原厂总开关。"
                            mode.comfortReadOnly -> "只临时加入所选通道的订阅，不自动写入或更改日常配置。厂商回调可能导致闪退。请停车后在原车界面改变状态，并查看报告的有效 EVENT 是否增加。"
                            mode == HvacValidationMode.TEMPERATURE ->
                                "温度模式会额外触发浮点 Customize/native 读取和事件，可能使进程直接闪退。"
                            else -> "本模式只订阅和读取，不自动写入车控。"
                        }
                        else -> "座椅模式会在验证窗口内允许显式测试写入；请停车操作，命令接受不等于车辆生效。"
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("开启2分钟验证：${mode.label}")
                        .setMessage("${warning}\n开启后可切页，操作后回来查看 HVAC_TEST 报告。")
                        .setPositiveButton("开始验证") { _, _ ->
                            VehicleController.startHvacValidation(this, mode)
                            refreshValidation?.invoke()
                            toastOnce("验证已请求；请按模式操作后查看报告")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        val maxTest = layout.action(section, "急速降温测试写入") {
            val epoch = VehicleController.acMaxValidationEpoch()
            if (epoch == null) {
                Logx.w("HVAC_TEST AC_MAX_WRITE stage=SKIPPED entry=SETTINGS 没有有效验证窗口，未调用厂商接口")
                toastOnce("请先开始「急速降温：原厂 MAX A/C」验证")
                refreshValidation?.invoke()
                return@action
            }
            fun submit(on: Boolean) {
                val queued = VehicleController.writeAcMaxForValidation(this, epoch, on)
                toastOnce(if (queued) "已排队，请观察原车界面并查看验证报告"
                    else "未提交：验证已结束或车控写入不可用，请查看日志")
            }
            AlertDialog.Builder(activity)
                .setTitle("急速降温测试写入")
                .setMessage("请停车操作并启用车控写入。只向原厂 MAX A/C 写入开或关，不另外写风量和温度。" +
                    "建议从相反状态开始，并核对原车界面和写入报告。停止或到期不会撤销已发出的命令。")
                .setPositiveButton("测试开启") { _, _ -> submit(true) }
                .setNeutralButton("测试关闭") { _, _ -> submit(false) }
                .setNegativeButton("取消", null)
                .show()
        }
        val stop = layout.action(section, "停止验证") {
            VehicleController.stopHvacValidation()
            refreshValidation?.invoke()
            toastOnce("车控验证已停止，可查看报告")
        }
        val report = layout.action(section, "查看验证报告") {
            showValidationReport("车控验证报告", "HVAC_TEST", HvacDiagnostics.report())
        }
        refreshValidation = {
            val mode = VehicleController.activeValidationMode()
            val hasReport = HvacDiagnostics.hasReport
            status.text = when {
                mode != null -> "验证中：${mode.label}（到期自动结束）"
                hasReport -> "有验证报告可查看"
                else -> "尚未开始验证。停车后选择要验证的车控功能。"
            }
            start.isVisible = mode == null
            stop.isVisible = mode != null
            report.isVisible = hasReport
            maxTest.isVisible = mode == HvacValidationMode.AC_MAX
            main.removeCallbacks(validationExpiry)
            if (mode != null && section.isShown) {
                main.postDelayed(validationExpiry, VehicleController.validationRemainingMs() + 1L)
            }
        }
    }

    private fun showValidationReport(name: String, tag: String, report: String) {
        val content = TextView(this).apply {
            text = report
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(activity)
            .setTitle("$name（$tag）")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制报告") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(tag, report))
                toastOnce("已复制$name")
            }
            .show()
    }

    private fun addLogUploadSection() {
        val parent = layout.upload
        addSwitchRow(parent, "启用日志上传", Prefs.logUploadEnabled(this), reloadDock = false) {
            Prefs.setLogUploadEnabled(this, it)
            refreshUploadStatus()
        }.contentDescription = "启用日志上传"
        uploadStatus = layout.note(parent, top = 0)
        val test = layout.button("测试上传") { button ->
            requestLogUpload(button, "正在测试上传…") { LogUploader.testUpload(this) }
        }
        val send = layout.button("立即上传日志") { button ->
            requestLogUpload(button, "正在上传日志…") {
                LogUploader.uploadLogTail(this, "manual", "主界面手动上传")
            }
        }
        layout.pairedButtons(parent, test, send)
    }

    private fun requestLogUpload(button: Button, progress: String, send: () -> Unit) {
        Prefs.setLogUploadUrl(this, uploadAddress.text.toString().trim())
        uploadStatus.text = progress
        setActionEnabled(button, false)
        send()
        main.postDelayed({
            setActionEnabled(button, true)
            refreshUploadStatus()
        }, 3000)
    }

    private fun refreshUploadStatus() {
        val result = LogUploader.lastResult
        displayedUploadResult = result
        val state = if (Prefs.logUploadEnabled(this)) {
            "联网时自动上传运行日志和崩溃记录，失败后补传。"
        } else "已关闭远程上传；仍可导出本地 ZIP。"
        uploadStatus.text = "$state\n最近上传：${result.ifBlank { "尚无上传记录" }}"
    }

    private fun addAdvancedTools() {
        val tools = layout.fold(layout.advanced, "高级排查", "收起高级排查") { open ->
            if (open) refreshOverview()
            refreshValidation?.invoke()
        }
        addHvacValidationActions(tools)

        layout.subsection(tools, "温度兼容性")
        addSwitchRow(tools, "旧温度事件监听", Prefs.vehicleTempWatcherEnabled(this), reloadDock = false) {
            Prefs.setVehicleTempWatcherEnabled(this, it)
            Logx.w(if (it) "温度事件监听 → 开启（若闪退会自动关回）" else "温度事件监听 → 关闭")
        }.contentDescription = "旧温度事件监听"
        layout.note(tools, "另一路主副驾温度回调，厂商接口可能闪退。日常同步勾选温度时会独立订阅并尝试浮点读取。", 0)
        addSwitchRow(tools, "浮点温度写入", Prefs.tempCustomizeWriteEnabled(this)) {
            Prefs.setTempCustomizeWriteEnabled(this, it)
            Logx.w(if (it) "温度 Customize 写入 → 开启" else "温度 Customize 写入 → 关闭")
        }.contentDescription = "浮点温度写入"
        layout.note(tools, "关闭后使用整数设温路径。厂商温度接口的兼容性因车机而异。", 0)

        layout.subsection(tools, "车型适配")
        addVehicleTools(tools)

        layout.subsection(tools, "上传设置")
        uploadAddress = addEditRow(tools, "上传地址（POST JSON）", Prefs.logUploadUrl(this).ifBlank { LogUploader.DEFAULT_URL }) {
            Prefs.setLogUploadUrl(this, it)
        }.apply {
            textSize = 14f
            minimumHeight = dp(48)
        }
        clearCrashButton = layout.action(tools, "清除崩溃记录") {
            AlertDialog.Builder(activity)
                .setTitle("清除本地崩溃记录？")
                .setMessage("会删除本机崩溃证据和用于导出的崩溃归档，不影响运行日志、已上传的数据或安全等级。需要留存时请先导出诊断 ZIP。")
                .setPositiveButton("清除记录") { _, _ ->
                    LogUploader.clearCrashEvidence(this)
                    CrashHandler.clear(this)
                    Logx.d("崩溃取证已清除")
                    refreshOverview()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun addVehicleTools(parent: LinearLayout) {
        layout.note(parent, "请停车后使用。扫描用于查找候选功能编号；循环取证用于查看原厂操作。")
        lateinit var scanReport: TextView
        layout.action(parent, "扫描车控通道") { button ->
            runDiagnostic(button, scanReport, "正在扫描候选车控通道…", "channel_scan", "车控通道扫描") {
                VehicleController.scanChannels(this)
            }
        }
        scanReport = layout.note(parent).apply { isVisible = false; setTextIsSelectable(true) }
        lateinit var recircReport: TextView
        layout.action(parent, "抓循环取证") { button ->
            AlertDialog.Builder(activity)
                .setTitle("开始内外循环取证？")
                .setMessage("请停车操作。开始后会尝试清空系统日志缓冲。请在原厂 Dock 或空调界面切换内外循环，由本应用采集结果。")
                .setPositiveButton("开始取证") { _, _ ->
                    VehicleController.openEvidence(120_000L)
                    runDiagnostic(button, recircReport, "取证窗口已开启，请操作原厂内外循环…",
                        "recirc_evidence", "内外循环专项取证") {
                        try {
                            PrivilegeProbe.grabRecircEvidence(90_000)
                        } finally {
                            VehicleController.closeEvidence()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        recircReport = layout.note(parent).apply { isVisible = false; setTextIsSelectable(true) }
    }

    private fun runDiagnostic(
        button: Button,
        output: TextView,
        progress: String,
        kind: String,
        title: String,
        onFinished: () -> Unit = {},
        action: () -> String
    ) {
        output.text = progress
        output.isVisible = true
        setActionEnabled(button, false)
        Thread {
            val report = runCatching(action).getOrElse { "$title 失败：${it.javaClass.simpleName}: ${it.message}" }
            Logx.d(report)
            postUi {
                setActionEnabled(button, true)
                onFinished()
                output.text = report
                LogUploader.upload(this, kind, report, title)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun setActionEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    private fun addDiagnosticExportAction(parent: LinearLayout) {
        exportButton = layout.action(parent, "导出诊断 ZIP", primary = true) { btn ->
            if (exportRunning) return@action
            setActionEnabled(btn, false)
            AlertDialog.Builder(activity)
                .setTitle("导出诊断与运行日志")
                .setMessage("日志可能包含应用名/包名、媒体信息及设备诊断信息，请仅交给可信分析者。选择保存位置不会自动分享或上传。")
                .setPositiveButton("选择保存位置") { _, _ ->
                    val name = "banana-dock-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"
                    try {
                        val launcher = exportLauncher ?: throw IllegalStateException("文档保存器尚未就绪")
                        launcher.launch(name)
                    } catch (e: Exception) {
                        setActionEnabled(btn, true)
                        Logx.e("无法打开日志保存位置", e)
                        toastOnce("无法打开保存位置：${e.javaClass.simpleName}")
                    }
                }
                .setNegativeButton("取消") { _, _ ->
                    setActionEnabled(btn, true)
                }
                .setOnCancelListener {
                    setActionEnabled(btn, true)
                }
                .show()
        }
    }

    private fun startExport(uri: android.net.Uri) {
        if (exportRunning) return
        exportRunning = true
        val appContext = applicationContext
        val pageRef = java.lang.ref.WeakReference(this)
        Thread {
            val result = DiagnosticLogExporter.export(appContext, uri)
            val page = pageRef.get() ?: return@Thread
            synchronized(page.callbackLock) {
                if (page.destroyed) return@Thread
                page.main.post {
                    page.exportRunning = false
                    page.exportButton?.let { page.setActionEnabled(it, true) }
                    page.toastOnce(result.message)
                }
            }
        }.apply { isDaemon = true; name = "diagnostic-export" }.start()
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
            svLog.shouldFollowTail()
        ) {
            scrollPending = true
            postUi {
                scrollPending = false
                if (root.isShown && svLog.isShown && currentFocus !is EditText &&
                    svLog.shouldFollowTail()
                ) {
                    svLog.scrollTo(0, tvLog.height)
                }
            }
        }
    }
}
