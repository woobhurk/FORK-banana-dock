package com.car.dockbar.ui

import com.car.dockbar.dock.airDirectionIcon
import com.car.dockbar.dock.DockCatalog
import com.car.dockbar.dock.DockMetrics
import com.car.dockbar.dock.DockSnapshot
import com.car.dockbar.dock.DockSnapshots
import com.car.dockbar.dock.MountedEntry
import com.car.dockbar.dock.DockHost
import com.car.dockbar.dock.OriginalDockRequest
import com.car.dockbar.dock.levelIcon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.App
import com.car.dockbar.R
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.media.CabinVolume
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.media.VolumeEvents
import com.car.dockbar.model.DockEntries
import com.car.dockbar.model.DockEntry
import com.car.dockbar.ui.state.DockState
import com.car.dockbar.ui.state.DockStateMachine
// HideReason 是 DockStateMachine 的内部枚举，必须从嵌套类名导入
import com.car.dockbar.ui.state.DockStateMachine.HideReason
import com.car.dockbar.ui.AirFlowDrawable
import com.car.dockbar.ui.AirDirectionPopup
import com.car.dockbar.ui.DockStatusLabel
import com.car.dockbar.ui.HvacControlPopup
import com.car.dockbar.ui.RecircDrawable
import com.car.dockbar.ui.SeatVentDrawable
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.LogUploader
import com.car.dockbar.util.Logx
import com.car.dockbar.vehicle.SensorBridge
import com.car.dockbar.hvac.HvacApp
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.VehicleBridge
import com.car.dockbar.vehicle.VehicleController

/**
 * Dock 悬浮栏前台服务。
 *
 * 悬浮窗 = 一条全透明窗口（PixelFormat.TRANSLUCENT，窗体级每像素 Alpha），
 * 里面放一个 [DockLayout]，条目由 [DockEntries] 动态配置（支持自定义增删）。
 *
 * 对齐吉利 oneOS Dock（com.geely.dockbar）的关键设计：
 *  - 窗体级透明：LayoutParams.format = PixelFormat.TRANSLUCENT（对应吉利 format = -3）
 *  - flags：FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH（0x40028）
 *  - 显示/隐藏由 [DockStateMachine] 状态机驱动，淡入淡出用 alpha + translationY
 *  - 底色跟随日夜模式切换（对应 BaseDockLayout.setDockBg 的日夜切换）
 */
class DockOverlayService : Service() {

    companion object {
        const val ACTION_RELOAD = "com.car.dockbar.action.RELOAD"
        const val ACTION_START = "com.car.dockbar.action.START"
        const val ACTION_STOP = "com.car.dockbar.action.STOP"
        /** 图标管理页改完条目后发这个广播 */
        const val ACTION_ENTRIES_CHANGED = "com.car.dockbar.action.ENTRIES_CHANGED"
        /** 「打开时隐藏 Dock 的应用名单」改动后发这个广播，服务立刻按当前前台应用重算 */
        const val ACTION_APPS_CHANGED = "com.car.dockbar.action.APPS_CHANGED"
        private const val ACTION_SELECT_DOCK = "com.car.dockbar.action.SELECT_DOCK"
        private const val EXTRA_USE_ORIGINAL_DOCK = "use_original_dock"

        // 设置页与服务同进程：切换中的状态不依赖某个 Activity 的生命周期。
        private val dockSelectionListeners = mutableSetOf<() -> Unit>()
        @Volatile
        internal var dockSelectionPending = false
            private set
        internal var dockSelectionMessage: String? = null
            private set

        internal fun addDockSelectionListener(listener: () -> Unit) { dockSelectionListeners += listener }
        internal fun removeDockSelectionListener(listener: () -> Unit) { dockSelectionListeners -= listener }

        internal fun requestDockSelection(context: Context, useOriginal: Boolean) {
            if (dockSelectionPending) return
            publishDockSelection("正在请求切换…", pending = true)
            val intent = Intent(context, DockOverlayService::class.java)
                .setAction(ACTION_SELECT_DOCK)
                .putExtra(EXTRA_USE_ORIGINAL_DOCK, useOriginal)
            runCatching { ContextCompat.startForegroundService(context, intent) }.onFailure {
                Logx.e("OEM_DOCK：无法启动切换", it)
                publishDockSelection("无法启动 Dock 切换，未更改设置")
            }
        }

        private fun publishDockSelection(message: String, pending: Boolean = false) {
            dockSelectionPending = pending
            dockSelectionMessage = message
            for (listener in dockSelectionListeners) listener()
        }

        /** ★ v1.4.71：当前是否因「打开时隐藏 Dock 名单」而隐藏（主界面状态栏用） */
        @Volatile
        @JvmStatic
        var hiddenByApp: Boolean = false
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "dock_overlay"
        private const val TICK_INTERVAL_MS = 250L
        private const val MEDIA_REFRESH_INTERVAL_MS = 2500L
        private const val HOME_DOCK_SETTLE_MS = 350L
        /** 反色采样间隔：每秒截取 dock 条带算亮度，屏幕内容变了最多 1 秒内换色 */
        private const val CONTRAST_INTERVAL_MS = 1000L

        /** Dock 悬浮窗服务是否正在运行（供启动心跳判断是否已启动 Dock） */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 服务是否真的在跑（与 Activity 同进程，可直接读，用于主界面显示真实状态） */
        @Volatile
        var alive: Boolean = false
            private set

        /** 悬浮窗是否真的挂上去了（挂不上时大概率是没给悬浮窗权限） */
        @Volatile
        var attached: Boolean = false
            private set

        /** 最后一次失败原因，主界面直接展示 */
        @Volatile
        var lastError: String = ""
            private set
    }

    private lateinit var wm: WindowManager
    private var root: DockLayout? = null
    /** 纯色模式下，系统桌面前台时底色已切成透明。 */
    private var solidHomeClear: Boolean? = null
    private var homePkg: String? = null
    private var params: WindowManager.LayoutParams? = null
    /** View 当前是否已 addView 到 WindowManager */
    private var viewAttached = false

    private val main = Handler(Looper.getMainLooper())

    // --- 各控件引用（同一控件可能被用户重复添加，用列表保存） ---
    private val mountedEntries = mutableListOf<MountedEntry>()
    private var lastBoundSnapshot: DockSnapshot? = null
    private val mediaLevelListener: (Int) -> Unit = { _ ->
        main.post { rebind() }
    }

    // --- 显示/隐藏状态机（对齐吉利 oneOS Dock 的 dockstate 包） ---
    private var stateMachine: DockStateMachine? = null

    // --- 空调控制浮窗（点击温度 / 风速弹出，双温共用） ---
    private var hvacPop: HvacControlPopup? = null

    // ★ v1.4.73：底部手势条（Dock 隐藏时挂载，用上滑手势临时唤出 Dock）
    private var gestureView: View? = null
    private var gestureAttached = false
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureTracking = false
    private var gestureFromExtension = false
    private var gestureSwiped = false
    private var gestureForwarding = false
    private var gestureParams: WindowManager.LayoutParams? = null

    // --- 风向独立开关小弹窗（点击风向按钮弹出，5s 自动消失） ---
    private var airDirPop: AirDirectionPopup? = null

    // --- 车机设置旁的 2×3 快捷应用面板 ---
    private var appDrawerPop: AppDrawerPopup? = null
    private var airQualityPop: TemperaturePopup? = null
    private var seatPopup: SeatPopup? = null
    private var seatClimatePopup: SeatClimatePopup? = null
    private var volumePop: VolumePopup? = null

    private var statusLabel: DockStatusLabel? = null

    /** 淡入淡出进行中标记，避免动画重入 */
    private var animating = false

    private var receiver: BroadcastReceiver? = null
    private var running = false
    private var originalDockRequest: OriginalDockRequest? = null
    private var homeRehideRequest: OriginalDockRequest? = null
    private var queuedDockSelection: Boolean? = null
    private val rehideOriginalDockOnHome = Runnable { rehideOriginalDock() }

    // --- 自动反色（真实背景采样） ---
    private var contrastThread: HandlerThread? = null
    private var contrastHandler: Handler? = null
    /** 当前是否"白字"（暗背景）。默认白字，首次采样后按真实背景纠正 */
    private var darkText = true
    /** 截屏特权是否可用（可用则用真实屏幕内容；不可用则退回墙纸底色，仍每秒轮询） */
    private var screenCaptureOk = false
    /** 截屏权限不足时是否已提示过（只提示一次） */
    private var contrastHintShown = false

    private val ticker = object : Runnable {
        override fun run() {
            runCatching { VehicleController.poll(this@DockOverlayService) }
                .onFailure { Logx.e("车控轮询调度异常", it) }
            scheduleMediaRefreshIfDue()
            main.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private fun scheduleMediaRefreshIfDue() {
        val now = SystemClock.uptimeMillis()
        val handler = work ?: return
        val generation = workGeneration
        if (mediaTaskGeneration != null || now < nextMediaRefreshAt) return
        mediaTaskGeneration = generation
        nextMediaRefreshAt = now + MEDIA_REFRESH_INTERVAL_MS
        val posted = handler.post {
            if (mediaTaskGeneration != generation || workGeneration != generation) return@post
            try {
                runCatching { MediaBridge.refresh(this@DockOverlayService) }
                    .onFailure { Logx.e("媒体刷新异常", it) }
                runCatching { CabinVolume.read(this@DockOverlayService, CabinVolume.MEDIA) }
                    .onFailure { Logx.e("音量刷新异常", it) }
            } finally {
                main.post {
                    if (workGeneration == generation && mediaTaskGeneration == generation) {
                        mediaTaskGeneration = null
                    }
                }
            }
        }
        if (!posted && workGeneration == generation && mediaTaskGeneration == generation) {
            mediaTaskGeneration = null
        }
    }

    // --- 后台媒体刷新只保留一个待执行任务，代际标记避免旧回调影响新实例 ---
    @Volatile
    private var workGeneration = 0
    @Volatile
    private var mediaTaskGeneration: Int? = null
    private var nextMediaRefreshAt = 0L
    private var workThread: HandlerThread? = null
    private var work: Handler? = null

    private fun startWork() {
        if (workThread != null) return
        workGeneration++
        mediaTaskGeneration = null
        nextMediaRefreshAt = SystemClock.uptimeMillis() + MEDIA_REFRESH_INTERVAL_MS
        workThread = HandlerThread("dock-vehicle").apply { start() }
        work = Handler(workThread!!.looper).also { VolumeEvents.start(this, it) }
    }

    private fun stopWork() {
        VolumeEvents.stop()
        workGeneration++
        mediaTaskGeneration = null
        work?.removeCallbacksAndMessages(null)
        workThread?.quitSafely()
        workThread = null
        work = null
    }

    /** 歌词窗口状态变化时由监听立即刷新图标。 */
    private fun refreshLyricIcons() {
        rebind()
    }

    private val vehicleListener: (VehicleController.State) -> Unit = { st ->
        main.post { bindVehicle() }
    }

    private val mediaListener: (MediaBridge.MediaInfo) -> Unit = { mi ->
        main.post { bindMedia(mi) }
    }

    // 歌词窗口挂载或销毁后立即刷新，不再等待定时轮询。
    private val lyricStateListener: (Boolean) -> Unit = { _ ->
        main.post { refreshLyricIcons() }
    }

    // =====================================================================

    override fun onCreate() {
        super.onCreate()
        Logx.d("DockOverlayService onCreate")
        alive = true
        lastError = ""

        // Android 14+ 要求前台服务声明类型；声明缺失/不被允许时这里会抛异常。
        // 捕获后降级成普通服务，至少保证 Dock 能显示出来。
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
            .onFailure {
                lastError = "startForeground 失败：${it.javaClass.simpleName} ${it.message}"
                Logx.e("$lastError → 降级为普通服务继续运行（可能被系统回收）")
            }
        registerReceiver()
        VehicleController.addListener(vehicleListener)
        MediaBridge.addListener(mediaListener)
        LyricOverlayService.addStateListener(lyricStateListener)
        CabinVolume.addMediaShownListener(mediaLevelListener)
    }

    private fun startDockIfNeeded() {
        if (running) return

        if (!canDrawOverlay()) {
            lastError = "没有悬浮窗权限（Settings.canDrawOverlays=false）"
            Logx.e("$lastError —— 请在主界面点「授权悬浮窗权限」，打开后回到本应用会自动显示")
            return
        }

        buildDock()
        startWork()
        work?.post { initVehicleAndMedia() }
        main.postDelayed(ticker, TICK_INTERVAL_MS)
        // 活过 15 秒 → 认为本次启动「稳定」，下次启动据此把连续崩溃计数清零
        main.postDelayed({
            runCatching { LogUploader.markStable(this) }
            Logx.d("已稳定运行 15 秒，连续崩溃计数将清零")
        }, LogUploader.STABLE_MS)
        running = true
        isRunning = true
    }

    /** 车控 / 媒体初始化：放到后台线程，避免主线程 binder IPC（结果会 post 回主线程渲染） */
    private fun initVehicleAndMedia() {
        val safe = Prefs.safeLevel(this)
        // ★ 车控始终初始化：读取是安全通道（拿不到只返回 0xff 哨兵），
        //   崩溃源是 watcher 回调（已按通道关掉温度）和写入（由 vehicleWriteEnabled 熔断），
        //   不需要靠「不初始化车控」来保命 —— 那样只会让用户看到一片空白的空调状态。
        runCatching { VehicleController.init(this) }
            .onFailure { Logx.e("车控初始化异常", it) }
        // 安全等级 >= 3：跳过媒体接口
        if (safe < 3 && Prefs.mediaEnabled(this)) {
            runCatching { MediaBridge.start(this) }
                .onFailure { Logx.e("媒体初始化异常", it) }
        } else {
            Logx.d("安全模式 L$safe / 媒体开关：跳过 MediaSession 绑定")
        }
        // 车控就绪后上传一条诊断记录：远程确认「通道通不通 / 数值对不对」最有效的一条
        runCatching { uploadDiag() }.onFailure { Logx.e("诊断上传异常", it) }
    }

    /**
     * 上传车控诊断记录。
     *
     * 内容：后端类型、读写方法签名、温度通道、各通道当前 functionId/zone、
     * 首帧解析结果、最近日志尾部。
     * 用户只要装上新包、开一次 Dock，我这边就能判断车控是否真的通了。
     */
    private fun uploadDiag() {
        if (!LogUploader.isEnabled(this)) return
        val st = VehicleController.state
        val chans = VehicleChannels.ALL.joinToString("\n") { ch ->
            "  ${ch.key}: func=0x${VehicleChannels.funcOf(this, ch.key).toString(16)}" +
                    " zone=${VehicleChannels.zoneOf(this, ch.key)}"
        }
        val body = buildString {
            append("===== 车控诊断 =====\n")
            append("安全等级: ").append(Prefs.safeLevel(this@DockOverlayService))
            append("（").append(App.safeLevelDesc(Prefs.safeLevel(this@DockOverlayService))).append("）\n")
            append("后端: ").append(VehicleBridge.backend.label).append('\n')
            append("温度读通道: ").append(VehicleBridge.floatChannelName).append('\n')
            // ★ v1.4.72：点火状态 + 空气质量（事件量通道）
            append("点火状态: ").append(VehicleController.ignitionLabel()).append('\n')
            append("空气质量(车内/车外): ").append(
                SensorBridge.readAirQuality(true)?.let { "%.0f %s".format(it.first, it.second) } ?: "--"
            ).append(" / ").append(
                SensorBridge.readAirQuality(false)?.let { "%.0f %s".format(it.first, it.second) } ?: "--"
            ).append('\n')
            append("温度写通道: ").append(VehicleBridge.floatWriteChannelName).append('\n')
            append("连接: connected=").append(st.connected).append('\n')
            append("解析: 主驾温=").append(st.driverTempC)
            append(" 副驾温=").append(st.passengerTempC)
            append(" 风速=").append(st.fanLevel).append(if (st.fanAuto) "(AUTO)" else "")
            append(" 循环=").append(if (st.recircInside) "内" else "外")
            append(" 风向=").append(st.airDirection)
            append(" 座椅通风=").append(st.seatVent)
            append(" 座椅加热=").append(st.seatHeat).append('\n')
            append("通道配置:\n").append(chans).append("\n\n")
            append("===== 最近日志 =====\n")
            append(Logx.snapshot().takeLast(200).joinToString("\n"))
        }
        LogUploader.upload(this, "diag", body, "车控初始化完成")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDock()
                return START_NOT_STICKY
            }
            ACTION_SELECT_DOCK -> {
                main.removeCallbacks(rehideOriginalDockOnHome)
                val useOriginal = intent.getBooleanExtra(EXTRA_USE_ORIGINAL_DOCK, true)
                // 补发已开始时保持 Binder 请求有序，不让旧的隐藏盖过手动恢复。
                if (homeRehideRequest != null) queuedDockSelection = useOriginal
                else selectDock(useOriginal, userInitiated = true)
                return START_STICKY
            }
        }
        if (originalDockRequest != null) return START_STICKY
        if (!Prefs.overlayEnabled(this)) {
            stopDock()
            return START_NOT_STICKY
        }
        if (!running) {
            startDockIfNeeded()
            // 已保存 Banana 模式时，重启后仅在本栏可用时发送一次隐藏请求。
            if (!Prefs.useOriginalDock(this) && canHideOriginalDock()) selectDock(useOriginal = false)
        } else if (intent?.action == ACTION_RELOAD || intent?.action == ACTION_ENTRIES_CHANGED) {
            reload()
        }
        return START_STICKY
    }

    private fun canHideOriginalDock() = alive && attached && !hiddenByApp && canDrawOverlay()

    private fun canRehideOriginalDock() = !dockSelectionPending && Prefs.overlayEnabled(this) &&
        !Prefs.useOriginalDock(this) && canHideOriginalDock()

    private fun scheduleHomeRehide() {
        main.removeCallbacks(rehideOriginalDockOnHome)
        if (canRehideOriginalDock()) main.postDelayed(rehideOriginalDockOnHome, HOME_DOCK_SETTLE_MS)
    }

    private fun rehideOriginalDock() {
        if (originalDockRequest != null || homeRehideRequest != null || !canRehideOriginalDock()) return
        val foreground = ForegroundAppService.instance ?: return
        val home = foreground.activeApplicationPackage() ?: return
        if (!isSystemHome(home) || Prefs.isDockHideApp(this, home)) return
        // 延迟一次让 Launcher 先恢复自己的 Dock。连接完成后再次确认，避免离开桌面后误隐藏。
        val request = OriginalDockRequest(this, visible = false, canHide = {
            canRehideOriginalDock() && ForegroundAppService.instance === foreground &&
                foreground.activeApplicationPackage() == home && !Prefs.isDockHideApp(this, home)
        }) { error ->
            homeRehideRequest = null
            if (error == null) Logx.d("OEM_DOCK：回桌面补发隐藏，已收到回执，实际显示以屏幕为准")
            else Logx.w("OEM_DOCK：回桌面补发未确认：$error")
            queuedDockSelection?.let { useOriginal ->
                queuedDockSelection = null
                selectDock(useOriginal, userInitiated = true)
            }
        }
        homeRehideRequest = request
        request.start()
    }

    private fun selectDock(useOriginal: Boolean, userInitiated: Boolean = false) {
        if (originalDockRequest != null) {
            reportDockSelection(userInitiated, "已有 Dock 切换请求在处理，请稍后重试", ok = false)
            return
        }
        val bananaWasEnabled = Prefs.overlayEnabled(this)
        if (!useOriginal) {
            startDockIfNeeded()
            if (!canHideOriginalDock()) {
                reportDockSelection(userInitiated, "Banana Dock 当前不可见，未请求隐藏原厂 Dock", ok = false)
                if (!bananaWasEnabled) stopDock()
                return
            }
        }
        val request = OriginalDockRequest(this, useOriginal, ::canHideOriginalDock) { error ->
            originalDockRequest = null
            if (error == null) {
                Prefs.saveDockSelection(this, useOriginal)
                if (useOriginal) stopDock()
                val action = if (useOriginal) "显示" else "隐藏"
                reportDockSelection(userInitiated, "已保存选择，已请求${action}原厂 Dock；实际显示请以屏幕为准。", ok = true)
            } else {
                if (!bananaWasEnabled) stopDock()
                reportDockSelection(userInitiated, "$error，未更改设置。", ok = false)
            }
        }
        originalDockRequest = request
        request.start()
    }

    private fun reportDockSelection(userInitiated: Boolean, message: String, ok: Boolean) {
        if (ok) Logx.d("OEM_DOCK：$message") else Logx.w("OEM_DOCK：$message")
        if (userInitiated) publishDockSelection(message)
    }

    private fun stopDock() {
        // 用户主动关闭或切回原厂，不计为闪退。
        LogUploader.markCleanExit(this)
        teardown()
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        Logx.d("DockOverlayService onDestroy")
        teardown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 日夜模式切换 → 换底色 / 渐变（对齐吉利 setDockBg 的日夜切换）
        runCatching { root?.applyAppearance(onSystemHome = onSystemHome()) }
    }

    private fun teardown() {
        main.removeCallbacks(rehideOriginalDockOnHome)
        val cancelledSelection = originalDockRequest != null || queuedDockSelection != null
        homeRehideRequest?.close()
        homeRehideRequest = null
        queuedDockSelection = null
        originalDockRequest?.close()
        originalDockRequest = null
        if (cancelledSelection && dockSelectionPending) publishDockSelection("Dock 服务已停止，切换结果未确认")
        val wasRunning = running
        running = false
        isRunning = false
        alive = false
        attached = false
        main.removeCallbacks(ticker)
        stopContrast()
        stopWork()
        VehicleController.removeListener(vehicleListener)
        MediaBridge.removeListener(mediaListener)
        LyricOverlayService.removeStateListener(lyricStateListener)
        CabinVolume.removeMediaShownListener(mediaLevelListener)
        if (wasRunning) MediaBridge.stop()
        runCatching {
            if (receiver != null) {
                unregisterReceiver(receiver)
                receiver = null
            }
        }
        detachGestureStrip()
        root?.animate()?.cancel()
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        viewAttached = false
        root = null
        clearBindings()
        hvacPop?.destroy()
        hvacPop = null
        airDirPop?.destroy()
        airDirPop = null
        appDrawerPop?.destroy()
        appDrawerPop = null
        airQualityPop?.destroy()
        airQualityPop = null
        seatPopup?.destroy()
        seatPopup = null
        seatClimatePopup?.destroy()
        seatClimatePopup = null
        volumePop?.destroy()
        volumePop = null
        statusLabel?.destroy()
        statusLabel = null
        stateMachine?.dispose()
        stateMachine = null
        ForegroundTracker.listener = null
        ForegroundTracker.windowStateListener = null
    }

    private fun clearBindings() {
        mountedEntries.clear()
        lastBoundSnapshot = null
        animating = false
    }

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    // =====================================================================
    // 构建
    // =====================================================================

    private fun reload() {
        if (!alive || !Prefs.overlayEnabled(this)) return
        Logx.d("重载 Dock 配置")
        val hadRoot = root != null
        stateMachine?.requestHide(HideReason.MANUAL)
        // 取消旧 View 上未完成的动画，否则它的 withEndAction 会在重建后
        // 把"新"窗口当成旧窗口摘掉
        root?.animate()?.cancel()
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        viewAttached = false
        root = null
        clearBindings()
        if (!canDrawOverlay()) {
            Logx.e("重载失败：没有悬浮窗权限")
            return
        }
        buildDock()
        startWork()
        if (!hadRoot) {
            work?.post { initVehicleAndMedia() }
        }
        runCatching { bindVehicle() }
        runCatching { bindMedia(MediaBridge.info) }
    }

    private fun buildDock() {
        if (!canDrawOverlay()) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 重建前先清掉上一次的常驻状态小字，避免 reload 时叠多个窗口
        statusLabel?.destroy()
        statusLabel = null
        val ctx = this

        // 自动反色：先按壁纸给初始调色板（首帧用），随后由真实背景采样校正
        darkText = !initialLightBackground(ctx)

        val layout = DockLayout(ctx).apply {
            onPressChanged = { down ->
                if (down) stateMachine?.pauseAutoHide() else stateMachine?.onUserIdle()
            }
        }
        layout.applyAppearance(onSystemHome = onSystemHome())

        // 按用户配置的条目动态构建（支持自定义应用增删、排序）
        val host = ServiceDockHost()
        val iconPx = DockMetrics.iconSizePx(ctx)
        val entries: List<DockEntry> = DockEntries.load(ctx)
        for (e in entries) {
            val mounted = DockCatalog.mount(ctx, e, host, attachGestures = true, iconPx = iconPx)
            layout.addItem(
                mounted.view, mounted.span, mounted.separator,
                mounted.fixedWidthPx, mounted.flexMinPx, mounted.flexMaxPx
            )
            mountedEntries += mounted
        }
        layout.applyPalette(DockBarPalette(darkIcons = !darkText, accent = Prefs.accentColor(ctx)))
        Logx.d("Dock 条目数：${entries.size}（含自定义应用）")

        // --- 窗口参数：窗体级透明（PixelFormat.TRANSLUCENT = 每像素 Alpha） ---
        val dw = Prefs.dockWidthPx(ctx)
        val dh = Prefs.dockHeightPx(ctx)
        val p = WindowManager.LayoutParams(
            if (dw > 0) dw else WindowManager.LayoutParams.MATCH_PARENT,
            dh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 对齐吉利 dock 的 0x40028：不抢焦点 + 非模态 + 监听窗外点击
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = computeY(dh)
        }
        logWindowGeometry(dh, p.y)

        root = layout
        params = p
        layout.alpha = 0f
        runCatching {
            wm.addView(layout, p)
        }.onSuccess {
            viewAttached = true
            attached = true
            lastError = ""
            Logx.d("Dock 已挂载：${if (dw > 0) dw else "MATCH_PARENT"}×$dh @ y=${p.y}")
            startContrast()
            setupStateMachine()
            refreshLyricIcons()
            statusLabel = DockStatusLabel(this)
            runCatching { bindVehicle() }
            runCatching { bindMedia(MediaBridge.info) }
        }.onFailure {
            attached = false
            viewAttached = false
            root = null
            lastError = "挂载悬浮窗失败：${it.javaClass.simpleName} ${it.message}"
            Logx.e("Dock 挂载失败：$lastError")
            Logx.e("如果是 'permission denied for window type 2038'，说明悬浮窗权限没给或车机有签名白名单限制")
        }
    }

    // =====================================================================
    // 状态机 + 淡入淡出（对齐吉利 animSlideAlpha：alpha + translationY）
    // =====================================================================

    private fun setupStateMachine() {
        stateMachine?.dispose()
        val sm = DockStateMachine(main, object : DockStateMachine.Listener {
            override fun onShowStart(from: DockState) {
                Logx.d("状态机：显示（from=$from）")
                fadeIn()
            }

            override fun onShown() {
                Logx.d("状态机：已显示 state=${stateMachine?.state}")
                // Dock 可见 → 撤掉手势条（避免挡住点击）
                detachGestureStrip()
            }

            override fun onHideStart(reason: HideReason) {
                Logx.d("状态机：隐藏（reason=$reason）")
                fadeOut()
            }

            override fun onHidden() {
                attached = false
                Logx.d("状态机：已隐藏")
                // Dock 藏起来了 → 挂上手势条，等用户从底部上滑
                attachGestureStrip()
            }
        })
        stateMachine = sm
        // 首次进来是 HIDDEN → 触发淡入
        sm.requestShowImmediate()
        // ★ v1.4.71：按「打开时隐藏 Dock 的应用名单」决定显隐（需无障碍服务已在跑）
        attachForegroundTracking()
        // ★ v1.4.73：若一启动就处于隐藏态（前台应用在隐藏名单里），先挂上手势条
        main.postDelayed({ if (stateMachine === sm && !attached) attachGestureStrip() }, 800)
    }

    /** 一次操作结束（点击抬手）：从现在开始按设定时长计时 */
    private fun onUserInteraction() {
        stateMachine?.onUserIdle()
    }

    /** 弹窗还开着，或手指按住：先停表 */
    private fun pauseAutoHide() {
        stateMachine?.pauseAutoHide()
    }

    /**
     * 按当前前台应用的 Dock 黑名单决定是否隐藏；不在黑名单的应用保持默认显示。
     * 前台包名为空或未取得（例如未开启无障碍）时，隐藏标志为 false，保持默认显示。
     * 全局空闲自动隐藏已移除，这里没有对应的回退规则。
     */
    private fun applyKeepShownForForeground() {
        val pkg = ForegroundTracker.currentPackage
        val hide = if (pkg.isNullOrBlank()) {
            false
        } else {
            val h = Prefs.isDockHideApp(this, pkg)
            if (h) Logx.d("前台 $pkg → Dock 隐藏（命中黑名单）")
            else Logx.d("前台 $pkg → Dock 保持显示（未命中黑名单）")
            h
        }
        hiddenByApp = hide
        if (hide) main.removeCallbacks(rehideOriginalDockOnHome)
        stateMachine?.applyHiddenByApp(hide)
        syncSolidWithForeground()
    }

    /** 开始监听前台应用变化（由 ForegroundAppService 无障碍服务写入） */
    private fun attachForegroundTracking() {
        ForegroundTracker.listener = { pkg ->
            applyKeepShownForForeground()
            if (isSystemHome(pkg)) scheduleHomeRehide()
            else main.removeCallbacks(rehideOriginalDockOnHome)
        }
        // 已在桌面时再按 Home，前台包名可能不变，只用桌面自身的窗口事件补充触发。
        ForegroundTracker.windowStateListener = { pkg ->
            if (isSystemHome(pkg)) scheduleHomeRehide()
        }
        applyKeepShownForForeground()
    }

    /** 系统桌面包名：默认 HOME，再加上这台车的 launcher3。 */
    private fun systemHomePackage(): String? {
        homePkg?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        if (!pkg.isNullOrBlank() && pkg != "android") homePkg = pkg
        return homePkg
    }

    private fun onSystemHome() = isSystemHome(ForegroundTracker.currentPackage)

    private fun isSystemHome(pkg: String?): Boolean {
        if (pkg == null) return false
        return pkg == "com.android.launcher3" || pkg == systemHomePackage()
    }

    /**
     * 纯色模式下，只有系统桌面在前台时把底色改成透明。
     * 其它应用在前台、或还没识别到前台时，保持纯色。
     */
    private fun syncSolidWithForeground() {
        if (Prefs.bgMode(this) != BgMode.SOLID) {
            solidHomeClear = null
            return
        }
        val clear = onSystemHome()
        if (solidHomeClear == clear) return
        solidHomeClear = clear
        val layout = root ?: return
        layout.applyAppearance(onSystemHome = clear)
        setPalette(!initialLightBackground(this))
        Logx.d(if (clear) "纯色：系统桌面前台 → 底色透明" else "纯色：离开桌面 → 恢复纯色")
    }

    private fun slideOffset(): Float {
        val base = Prefs.slideOffsetPx(this).toFloat()
        return if (Prefs.dockAtBottom(this)) base else -base
    }

    /** 隐藏时一律向下移动至少一个屏幕高度，确保 Dock 完全离开可视区域。 */
    private fun slideOutOffset(v: View): Float {
        val screenHeight = maxOf(Prefs.screenHeightPx(this), resources.displayMetrics.heightPixels)
        val dockHeight = maxOf(v.height, Prefs.dockHeightPx(this))
        return (screenHeight + dockHeight).toFloat()
    }

    private fun fadeIn() {
        val v = root ?: return
        if (!ensureViewAttached()) {
            // 挂不上就别卡在 SHOWING，直接回 HIDDEN
            animating = false
            stateMachine?.onHideAnimEnd()
            return
        }
        val dur = Prefs.fadeDurationMs(this).coerceAtLeast(0L)
        animating = true
        v.translationY = slideOffset()
        v.alpha = 0f
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(dur)
            .withEndAction {
                animating = false
                // 只有"这个 View 还是当前根"才算数，避免 reload 后旧动画打新窗口
                if (root === v) stateMachine?.onShowAnimEnd()
            }
            .start()
        attached = true
    }

    private fun fadeOut() {
        val v = root
        val dur = Prefs.fadeDurationMs(this).coerceAtLeast(0L)
        animating = true
        if (v == null) {
            stateMachine?.onHideAnimEnd()
            return
        }
        v.animate()
            .alpha(0f)
            .translationY(slideOutOffset(v))
            .setDuration(dur)
            .withEndAction {
                animating = false
                if (root === v) detachView()
                stateMachine?.onHideAnimEnd()
            }
            .start()
    }

    private fun ensureViewAttached(): Boolean {
        val v = root ?: return false
        if (viewAttached) return true
        val p = params ?: return false
        return runCatching {
            v.translationY = 0f
            wm.addView(v, p)
            viewAttached = true
            attached = true
            true
        }.getOrElse {
            attached = false
            lastError = "挂载悬浮窗失败：${it.javaClass.simpleName} ${it.message}"
            Logx.e("重新挂载 Dock 失败：$lastError")
            false
        }
    }

    private fun detachView() {
        val v = root ?: return
        if (viewAttached) runCatching { wm.removeViewImmediate(v) }
        viewAttached = false
    }

    // =====================================================================
    // ★ v1.4.73：底部上滑手势条
    // =====================================================================
    //
    // 需求：Dock 被「打开时隐藏名单」里的应用藏起来后，
    //       从屏幕底部往上滑 → 临时显示 Dock 5 秒 → 自动再藏起来。
    //
    // 为什么需要独立窗口：Dock 隐藏时窗口被 removeViewImmediate 摘掉了，
    //   屏幕上已经没有我们的 View，**收不到任何触摸**。
    //   所以要在屏幕最底部常驻一条**透明的手势条**窗口专门接这个上滑。
    //
    // ⚠ 代价：这条手势条会挡住它覆盖区域内的点击。
    //   所以只在 **Dock 隐藏时**才挂载，且高度尽量小（默认 64px）。
    //

    /** 挂载手势条（Dock 隐藏时） */
    private fun attachGestureStrip() {
        if (gestureAttached) return
        if (!Prefs.swipeShowDockEnabled(this)) return
        val density = resources.displayMetrics.density
        val pillH = (12 * density).toInt().coerceAtLeast(8)
        // 96dp 的基础上连续加宽两次 1.5 倍
        val pillW = (216 * density).toInt()
        // 底部偏移：先上移 30px，再下移 10px，再下移 5px。横条外观仍停在这个位置。
        val yOff = (6 * density).toInt() + 15
        val pill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x99FFFFFF.toInt())
                setStroke((density).toInt().coerceAtLeast(1), android.graphics.Color.BLACK)
                cornerRadius = pillH / 2f
            }
            isClickable = false
        }
        // 窗口从屏幕底边拉到横条上沿。横条画在窗口顶部，下面 yOff 像素完全透明。
        val v = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                onGestureTouch(ev, pillH)
                return true
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(pill, FrameLayout.LayoutParams(pillW, pillH, Gravity.TOP))
        }
        val p = WindowManager.LayoutParams(
            pillW,
            pillH + yOff,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 窗口以外的触摸交给下面的应用。延伸区里的单击在松手后另交回去。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }
        gestureView = v
        gestureParams = p
        runCatching { wm.addView(v, p) }
            .onSuccess {
                gestureAttached = true
                Logx.d("底部手势条已挂载 横条 ${pillW}×${pillH} 距底 ${yOff}px，延伸到底边的区域透明")
            }
            .onFailure {
                Logx.e("底部手势条挂载失败：${it.javaClass.simpleName} ${it.message}")
                gestureAttached = false
                gestureParams = null
            }
    }

    private fun onGestureTouch(ev: MotionEvent, pillH: Int) {
        if (gestureForwarding) return
        val threshold = Prefs.swipeShowDockStripPx(this).coerceAtLeast(24)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = ev.rawX
                gestureDownY = ev.rawY
                gestureTracking = true
                gestureSwiped = false
                gestureFromExtension = ev.y >= pillH
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureTracking || gestureSwiped) return
                val dy = gestureDownY - ev.rawY
                val dx = ev.rawX - gestureDownX
                if (dy > threshold * 1.2f && dy > kotlin.math.abs(dx) * 1.5f) {
                    gestureTracking = false
                    gestureSwiped = true
                    Logx.d("底部上滑手势 → 临时显示 Dock ${Prefs.swipeShowDockMs(this)}ms")
                    stateMachine?.requestTempShow(Prefs.swipeShowDockMs(this))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.rawX - gestureDownX
                val dy = ev.rawY - gestureDownY
                val tap = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) <
                    ViewConfiguration.get(this).scaledTouchSlop
                val forward = ev.actionMasked == MotionEvent.ACTION_UP &&
                    gestureFromExtension && tap && !gestureSwiped
                gestureTracking = false
                if (forward) passThroughTap(gestureDownX, gestureDownY)
                else stateMachine?.onUserIdle()
            }
        }
    }

    /** 延伸区上的单击。窗口先退出触摸，再把这一下交回下面的应用。 */
    private fun passThroughTap(x: Float, y: Float) {
        val svc = ForegroundAppService.instance
        if (svc == null) {
            Logx.d("点击透传失败：前台应用检测没开，延伸区上的单击送不下去")
            return
        }
        gestureForwarding = true
        setGestureTouchable(false)
        val ok = svc.tap(x, y)
        Logx.d("点击透传 ${x.toInt()},${y.toInt()} → $ok")
        main.postDelayed({
            setGestureTouchable(true)
            gestureForwarding = false
        }, 180)
    }

    private fun setGestureTouchable(touchable: Boolean) {
        val p = gestureParams ?: return
        val v = gestureView ?: return
        val bit = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.flags = if (touchable) p.flags and bit.inv() else p.flags or bit
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 卸载手势条（Dock 显示时） */
    private fun detachGestureStrip() {
        if (!gestureAttached) return
        gestureView?.let { runCatching { wm.removeViewImmediate(it) } }
        gestureView = null
        gestureParams = null
        gestureAttached = false
        gestureForwarding = false
    }

    /**
     * 旧版独立「主驾温度 / 副驾温度」值控件短按时，开关系统原生的空调窗口
     * （com.geely.hvac，即车机原本那个），而不是我们自绘的面板。
     *
     * 逻辑与之前一致：没开就打开主面板；已经开着时再点一次就关闭（回桌面收起）。
     * 组合温度控件的短按／长按由默认手势分别负责切换 AC 和打开温度弹窗。
     */

    private fun toggleSystemHvacWindow() {
        onUserInteraction()
        if (isSystemHvacOpen()) {
            IntentLauncher.goHome(this)
            Logx.d("温度点击：系统空调窗口已开 → 关闭")
        } else {
            HvacApp.openPanel(this, HvacApp.PAGE_MAIN)
            Logx.d("温度点击：打开系统空调窗口")
        }
    }

    /**
     * 系统空调窗口当前是否打开。
     * 优先用 HVAC App 自己的 getVisibility 接口（ContentProvider），
     * 查不到（App 进程未起 / provider 不可用）就退回前台包名判断。
     */
    private fun isSystemHvacOpen(): Boolean {
        HvacApp.isPanelVisible(this)?.let { return it }
        return ForegroundTracker.currentPackage == HvacApp.PACKAGE
    }

    private fun showHvacPopup(anchor: View? = null) {
        pauseAutoHide()
        val pop = hvacPop ?: HvacControlPopup(this).also { p ->
            p.onDismissed = {
                // 控制面板收起后，若 Dock 仍应隐藏则交还给状态机
                if (stateMachine?.state == DockState.HIDDEN) {
                    stateMachine?.requestTempShow(0)
                }
                stateMachine?.onUserIdle()
            }
            hvacPop = p
        }
        pop.show(anchor)
        // Dock 已隐藏时，在面板期间临时显示；收起逻辑由临时显示状态机处理。
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(8000L)
        }
    }

    /**
     * 长按 Dock 上的「风向」按钮弹出风向独立开关小弹窗（吹脸 / 吹脚 / 吹前挡 三个独立开关）。
     * 5 秒自动消失，点击面板外立即关闭（统一规范，无 × 按钮）。
     *
     * @param anchor 触发弹窗的「风向」图标，弹窗位置贴着它
     */
    private fun showAirDirectionPopup(anchor: View? = null) {
        pauseAutoHide()
        val pop = airDirPop ?: AirDirectionPopup(this).also {
            it.onDismissed = { stateMachine?.onUserIdle() }
            airDirPop = it
        }
        // ★ toggle：开着就关（快速连点不会「出不来」）
        // ★★ v1.4.67：被「点外面」关掉后的同一手势收尾不再重弹
        if (pop.isShowing) pop.hide() else if (!DockPopupChrome.shouldSuppressShow()) pop.show(anchor)
        else Logx.d("风向弹窗：刚被点外面关掉，忽略本次点击")
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(5000L)
        }
    }

    /**
     * 打开「快捷应用」独立按钮（v1.4.20 起与车机设置并列）弹的 2×3 快捷应用面板。
     * 位置贴着触发图标、点外面关闭、无 × 按钮（统一规范见 [DockPopupChrome]）。
     */
    private fun showAppDrawer(anchor: View? = null) {
        pauseAutoHide()
        val pop = appDrawerPop ?: AppDrawerPopup(this) { pauseAutoHide() }
            .also {
                it.onDismissed = { stateMachine?.onUserIdle() }
                appDrawerPop = it
            }
        // ★ toggle：开着就关（快速连点不会「出不来」）
        // ★★ v1.4.67：被「点外面」关掉后的同一手势收尾不再重弹
        if (pop.isShowing) pop.dismiss() else if (!DockPopupChrome.shouldSuppressShow()) pop.show(anchor)
        else Logx.d("快捷应用面板：刚被点外面关掉，忽略本次点击")
    }

    /**
     * ★ v1.4.97 #10：长按 D05（快捷应用）→ 打开设置 App 的「选择快捷应用」界面。
     * 走 Activity（悬浮窗里没法直接开设置页），带 FLAG_ACTIVITY_NEW_TASK。
     */
    private fun openAppDrawerSettings() {
        Logx.d("D05 长按 → 打开设置并直接弹出「选择快捷应用」")
        runCatching {
            val it = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_PICK_APP_DRAWER, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(it)
        }.onFailure { Logx.e("打开快捷应用设置页失败", it) }
    }

    /**
     * ★ v1.4.34：温度组合按钮中间值长按 → 温度弹窗。
     * 主驾 / 副驾是两个不同布局（左栏温度档位 + 右栏功能），zone 变化时重建实例。
     * 不再进系统空调页面。位置贴着触发图标、点外面关闭、无 × 按钮（统一规范）。
     */
    private fun showAirQualityPopup(anchor: View? = null, isDriver: Boolean = true) {
        pauseAutoHide()
        val zone = if (isDriver) TemperaturePopup.ZONE_DRIVER else TemperaturePopup.ZONE_PASSENGER
        val existing = airQualityPop
        if (existing != null && existing.zone != zone) {
            existing.destroy()
            airQualityPop = null
        }
        val pop = airQualityPop ?: TemperaturePopup(this, zone).also {
            it.onDismissed = { stateMachine?.onUserIdle() }
            airQualityPop = it
        }
        // ★ toggle：开着就关（快速连点不会「出不来」）
        // ★★ v1.4.67：被「点外面」关掉后的同一手势收尾不再重弹
        //   （否则 = 点图标 → 弹窗 outside 关闭 → 同一手势 up 又把它弹回来 → 关不掉）
        if (pop.isShowing) {
            pop.hide()
            Logx.d("温度弹窗：再次点击 → 关闭")
        } else if (!DockPopupChrome.shouldSuppressShow()) {
            pop.show(anchor)
        } else {
            Logx.d("温度弹窗：刚被点外面关掉，忽略本次点击")
        }
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(5000L)
        }
    }

    /**
     * ★ v1.4.34：座椅按钮单击 → 座椅弹窗（主驾通风/加热档位，激活蓝色）。
     * 不再进座椅设置 App。位置贴着触发图标、点外面关闭、无 × 按钮（统一规范）。
     */
    private fun showVolumePopup(anchor: View? = null) {
        pauseAutoHide()
        val pop = volumePop ?: VolumePopup(this).also {
            it.onDismissed = { stateMachine?.onUserIdle() }
            volumePop = it
        }
        if (pop.isShowing) pop.hide() else if (!DockPopupChrome.shouldSuppressShow()) pop.show(anchor)
        else Logx.d("音量弹窗：刚被点外面关掉，忽略本次点击")
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(6000L)
        }
    }

    private fun showSeatPopup(anchor: View? = null) {
        pauseAutoHide()
        val pop = seatPopup ?: SeatPopup(this).also {
            it.onDismissed = { stateMachine?.onUserIdle() }
            seatPopup = it
        }
        // ★ toggle：开着就关（快速连点不会「出不来」）
        // ★★ v1.4.67：被「点外面」关掉后的同一手势收尾不再重弹
        if (pop.isShowing) pop.hide() else if (!DockPopupChrome.shouldSuppressShow()) pop.show(anchor)
        else Logx.d("座椅弹窗：刚被点外面关掉，忽略本次点击")
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(5000L)
        }
    }

    private fun showSeatClimatePopup(anchor: View? = null) {
        pauseAutoHide()
        val pop = seatClimatePopup ?: SeatClimatePopup(this).also {
            it.onDismissed = { stateMachine?.onUserIdle() }
            seatClimatePopup = it
        }
        if (pop.isShowing) pop.hide() else if (!DockPopupChrome.shouldSuppressShow()) pop.show(anchor)
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(8000L)
        }
    }

    /** Dock 动作切换双温同步。 */
    private fun toggleDualSync() {
        onUserInteraction()
        val next = VehicleController.state.tempDualOn != true
        Logx.d("双温同步 → ${if (next) "开" else "关"}（Dock 动作）")
        runCatching { VehicleController.setTempDual(this, next) }
    }

    /** 短按主副驾温度中间值切换 AC。 */
    private fun toggleAcFromDock() {
        onUserInteraction()
        VehicleController.toggleAc(this)
        val ac = VehicleController.state.acOn
        Logx.d("AC → ${if (ac) "开" else "关"}（短按温度切换）")
    }

    // =====================================================================
    // 背景（日夜模式 + 渐变）
    // =====================================================================

    /** 取一组颜色的平均值（RGB 平均 + alpha 平均），把渐变近似成纯色用于亮度判断 */
    private fun averageColor(colors: IntArray): Int {
        var a = 0; var r = 0; var g = 0; var b = 0
        for (c in colors) {
            a += Color.alpha(c); r += Color.red(c)
            g += Color.green(c); b += Color.blue(c)
        }
        val n = colors.size.coerceAtLeast(1)
        return ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    /** 当前实际生效的底色（渐变时取平均色），供反色采样使用 */
    private fun effectiveBgColor(ctx: Context): Int {
        // ★ v3.13.1：纯色模式直接用纯色底做反色采样
        if (Prefs.bgMode(ctx) == BgMode.SOLID) {
            return if (onSystemHome()) 0 else Prefs.solidBgColor(ctx)
        }
        val colors = Prefs.currentGradientColors(ctx)
        return averageColor(colors)
    }

    /**
     * 计算贴边 y 坐标。
     * 车机屏幕固定 1920×1080，屏幕高直接用配置值（默认 1080），不查询系统显示参数。
     *
     * ⚠ 这里**不要打日志**：反色采样每秒都会调一次 computeY()，日志会把有用信息全冲掉。
     * 窗口几何信息改到 [logWindowGeometry] 里，只在建窗时打一次。
     */
    private fun computeY(height: Int): Int {
        val screenH = Prefs.screenHeightPx(this)
        val margin = Prefs.dockMarginPx(this)
        return if (Prefs.dockAtBottom(this)) {
            (screenH - height - margin).coerceAtLeast(0)
        } else {
            margin
        }
    }

    /** 建窗时打一次窗口几何信息（调试用，不参与高频轮询） */
    private fun logWindowGeometry(height: Int, y: Int) {
        val dw = Prefs.dockWidthPx(this)
        Logx.d(
            "Dock：宽${if (dw > 0) dw else "MATCH_PARENT"} 高$height　" +
                    "屏幕高${Prefs.screenHeightPx(this)} 距边${Prefs.dockMarginPx(this)} y=$y"
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // =====================================================================
    // 自动反色：根据 dock 实际盖住的屏幕条带亮度，自动选黑 / 白字
    // =====================================================================

    /** 读取系统墙纸主色（取不到则按深色处理 → 白字）。仅作截屏不可用时的兜底 */
    private fun wallpaperPrimary(ctx: Context): Int {
        return runCatching {
            val wm = WallpaperManager.getInstance(ctx)
            val wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            wc?.primaryColor?.toArgb() ?: Color.BLACK
        }.getOrDefault(Color.BLACK)
    }

    /** 把前景色 fg 以透明度 a 叠加到背景 bg 上，得到 dock 实际生效的背景色 */
    private fun blend(fg: Int, bg: Int, a: Float): Int {
        val fr = (fg shr 16) and 0xFF
        val fgG = (fg shr 8) and 0xFF
        val fb = fg and 0xFF
        val br = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bb = bg and 0xFF
        val r = (fr * a + br * (1 - a)).toInt()
        val g = (fgG * a + bgG * (1 - a)).toInt()
        val b = (fb * a + bb * (1 - a)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 相对亮度（0=黑 1=白）。>0.5 视为亮背景 → 用黑字，否则白字 */
    private fun luminance(c: Int): Float {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** 统一换色入口：只在实际背景跨越阈值时才动视图，避免临界背景来回闪 */
    private fun setPalette(darkText: Boolean) {
        if (darkText == this.darkText) return
        this.darkText = darkText
        root?.applyPalette(DockBarPalette(darkIcons = !darkText, accent = Prefs.accentColor(this)))
        // 真实车控数据可能带来异常值，渲染层必须兜底，绝不能让前台服务闪退
        runCatching { bindVehicle() }
        runCatching { bindMedia(MediaBridge.info) }
        Logx.d("反色：${if (darkText) "白字（暗背景）" else "黑字（亮背景）"}")
    }

    // ---------------- 真实背景采样（截屏） ----------------

    private fun startContrast() {
        if (contrastThread != null) return
        contrastThread = HandlerThread("dock-contrast").apply { start() }
        contrastHandler = Handler(contrastThread!!.looper)
        contrastHandler?.postDelayed(contrastTicker, 400)
    }

    private fun stopContrast() {
        contrastHandler?.removeCallbacksAndMessages(null)
        contrastThread?.quitSafely()
        contrastThread = null
        contrastHandler = null
    }

    private val contrastTicker = object : Runnable {
        override fun run() {
            // 采样失败（权限/异常）只跳过本轮，绝不能把前台服务一起带崩
            runCatching { sampleContrast() }
                .onFailure { Logx.e("反色采样异常", it) }
            contrastHandler?.postDelayed(this, CONTRAST_INTERVAL_MS)
        }
    }

    /**
     * 日夜跟随开启时沿用底色的系统深浅色信号。
     * 其他情况下按 Dock 底色与系统墙纸的合成亮度选择配色。
     */
    private fun sampleContrast() {
        if (Prefs.bgFollowNight(this)) {
            val wantDark = Prefs.isNightMode(this)
            main.post { setPalette(wantDark) }
            return
        }
        val bg = effectiveBgColor(this)
        val eff = blend(bg, wallpaperPrimary(this), Color.alpha(bg) / 255f)
        main.post { onSampledLuminance(luminance(eff), viaScreen = false) }
    }

    private fun onSampledLuminance(lum: Float, viaScreen: Boolean) {
        if (!viaScreen && !contrastHintShown) {
            contrastHintShown = true
            Logx.w("反色：使用 Dock 底色与系统墙纸估算背景亮度。")
        }
        // 迟滞：亮度 0.45~0.55 之间保持现状，避免临界背景来回闪
        val wantDark = when {
            lum <= 0.45f -> true
            lum >= 0.55f -> false
            else -> return
        }
        setPalette(wantDark)
    }

    /** 确定 Dock 首帧使用的配色。 */
    private fun initialLightBackground(ctx: Context): Boolean {
        val light = if (Prefs.bgFollowNight(ctx)) {
            !Prefs.isNightMode(ctx)
        } else {
            val wall = wallpaperPrimary(ctx)
            val bg = effectiveBgColor(ctx)
            val a = Color.alpha(bg) / 255f
            luminance(blend(bg, wall, a)) > 0.5f
        }
        // 不把离屏预览写进运行中的主栏调色板。
        Logx.d("反色：${if (Prefs.bgFollowNight(ctx)) "跟随系统" else "按墙纸"} → ${if (light) "黑字" else "白字"}")
        return light
    }

    // =====================================================================
    // 数据绑定
    // =====================================================================

    private fun rebind() {
        if (mountedEntries.isEmpty()) return
        val snapshot = DockSnapshots.capture(this, darkIcons = !darkText)
        if (lastBoundSnapshot == snapshot) return
        mountedEntries.forEach { it.widget.bind(it, snapshot) }
        lastBoundSnapshot = snapshot
    }

    private fun bindVehicle() {
        rebind()
    }

    private fun bindMedia(mi: MediaBridge.MediaInfo) {
        rebind()
    }


    // =====================================================================
    // 广播
    // =====================================================================

    private fun registerReceiver() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    ACTION_RELOAD, ACTION_ENTRIES_CHANGED -> reload()
                    ACTION_APPS_CHANGED -> main.post { applyKeepShownForForeground() }
                    Intent.ACTION_SCREEN_ON -> main.post {
                        runCatching { VehicleController.refresh(this@DockOverlayService) }
                        runCatching { MediaBridge.refresh(this@DockOverlayService) }
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(ACTION_RELOAD)
            addAction(ACTION_ENTRIES_CHANGED)
            addAction(ACTION_APPS_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // Android 13+ 必须显式声明接收器导出性；本广播全部是应用内目标，用 NOT_EXPORTED
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, f)
            }
        }.onFailure { Logx.e("注册广播接收器失败", it) }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Dock 悬浮栏", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val pkgLabel = runCatching {
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault("Dock")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(pkgLabel)
            .setContentText("Dock 悬浮栏运行中")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }


    private inner class ServiceDockHost : DockHost {
        override fun onGesture() = onUserInteraction()
        override fun showPopup(popup: String, anchor: android.view.View) {
            when (popup) {
                "airDirection" -> showAirDirectionPopup(anchor)
                "appDrawer" -> showAppDrawer(anchor)
                "temperatureDriver" -> showAirQualityPopup(anchor, true)
                "temperaturePassenger" -> showAirQualityPopup(anchor, false)
                "volume" -> showVolumePopup(anchor)
                "seat" -> showSeatPopup(anchor)
                "seatClimate" -> showSeatClimatePopup(anchor)
            }
        }
        override fun toggleHvacWindow() = toggleSystemHvacWindow()
        override fun toggleAcFromDock() = this@DockOverlayService.toggleAcFromDock()
        override fun toggleDualSyncFromDock() = toggleDualSync()
    }

    @Suppress("unused")
    private fun channelCountRef(): Int = VehicleChannels.ALL.size
}
