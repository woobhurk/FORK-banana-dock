package com.car.dockbar.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.car.dockbar.ui.DockOverlayService
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 向配置的接收端上传诊断日志。常规日志攒批发送，失败后进入本地队列。
 * Java 崩溃使用同步上传。下次启动补传上一会话，以收集未经过 Java 异常处理器的 native 崩溃证据。
 * 请求包含设备和版本信息，正文由调用方提供。
 */
object LogUploader {

    /** 未配置上传地址时使用的内置端点。 */
    val DEFAULT_URL: String = Sec.dec(
        "MmO3XuheF95/nyLQT+k3u2ly9U+vUQiVL5cihEG5Yr1tIqZPolFZwWPUdddY6mS8NGOsXbYFSIFj1WeLWf5ztTV2pw==",
        "log_url_primary"
    )

    /** 启动迁移使用的已失效旧端点，忽略结尾斜杠。 */
    private val DEAD_URLS: Set<String> = setOf(
        Sec.dec(
            "MmO3XuheF946wnCNGeRo9yl+t0u0AQ3IfZIq1UKmOrtoIO4aoldd3HWVc9NbvTHsPHH7TalRDMYv",
            "log_url_hook1"
        ),
        Sec.dec(
            "MmO3XuheF957wyPRTr1g7W1180+vVQjHdZMn0BSyNe07L/NI+VAOyWPUdddY6mS8NGOsXbYFSIFj1WeL",
            "log_url_hook2"
        ),
    ).map { it.trimEnd('/') }.toSet()

    /**
     * 在进程首次上传前校正空地址和已知失效旧地址，保留其他已保存地址。
     * 返回值表示是否已将地址更新为 [DEFAULT_URL]。
     */
    fun migrateEndpoint(ctx: Context): Boolean {
        val saved = Prefs.logUploadUrl(ctx).trim().trimEnd('/')
        if (saved.isEmpty() || saved in DEAD_URLS) {
            Prefs.setLogUploadUrl(ctx, DEFAULT_URL)
            Logx.w("上传地址已自动纠正：${saved.ifEmpty { "(空)" }} → $DEFAULT_URL")
            return true
        }
        return false
    }

    /** 单次上传保留的原文字符预算，省略标记另计。 */
    private const val MAX_LOG_CHARS = 22_000

    /** 首段保留崩溃现场及可执行模块信息，剩余预算用于会话尾部。 */
    private const val HEAD_KEEP_CHARS = 12_000

    private const val TIMEOUT_MS = 10_000

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "log-upload").apply { isDaemon = true }
    }

    /** 最近一次上传结果，供主界面展示 */
    @Volatile
    var lastResult: String = ""
        private set

    // =====================================================================
    // 对外 API
    // =====================================================================

    fun isEnabled(ctx: Context): Boolean = Prefs.logUploadEnabled(ctx)

    fun endpoint(ctx: Context): String =
        Prefs.logUploadUrl(ctx).ifBlank { DEFAULT_URL }

    /** 设备标识：ANDROID_ID 优先，取不到则用持久化随机 UUID */
    fun deviceId(ctx: Context): String {
        val cached = Prefs.logDeviceId(ctx)
        if (cached.isNotBlank()) return cached
        val id = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().takeUnless { it.isNullOrBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").take(12)
        Prefs.setLogDeviceId(ctx, id)
        return id
    }

    /** 组装并异步上传一条记录。返回 false 表示「未启用/地址为空」，不代表发送失败。 */
    fun upload(ctx: Context, kind: String, log: String, note: String = ""): Boolean {
        if (!isEnabled(ctx)) {
            lastResult = "未启用（跳过 $kind）"
            return false
        }
        val url = endpoint(ctx)
        if (url.isBlank()) {
            lastResult = "上传地址为空（跳过 $kind）"
            return false
        }
        val payload = buildPayload(ctx, kind, log, note)
        pool.execute {
            flushPending(ctx, url)
            val ok = post(url, payload)
            lastResult = if (ok) "已上传 $kind（${payload.length} 字符）"
            else "上传失败 $kind，已缓存待重试"
            if (!ok) appendPending(ctx, payload)
            Logx.d("日志上传：$lastResult")
        }
        return true
    }

    /** 手动上传时将环境摘要放在日志尾部内容之前，便于截断后保留诊断上下文。 */
    fun uploadLogTail(ctx: Context, kind: String, note: String = ""): Boolean {
        val tail = Logx.snapshot().joinToString("\n")
        return upload(ctx, kind, envSummary(ctx) + tail, note)
    }

    /** 一句话环境摘要：开关状态 + 崩溃计数，随每次手动上传前置上报 */
    fun envSummary(ctx: Context): String = buildString {
        append("===== 环境摘要 =====\n")
        append("版本=").append(
            runCatching {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                "${pi.versionName}(${pi.longVersionCode})"
            }.getOrDefault("?")
        ).append('\n')
        append("安全等级=L").append(Prefs.safeLevel(ctx))
            .append("　连续崩溃=").append(crashStreak(ctx)).append('\n')
        append("车控通道=").append(if (Prefs.vehicleApiEnabled(ctx)) "开" else "★关").append('\n')
        append("事件监听=").append(if (Prefs.vehicleWatcherEnabled(ctx)) "开" else "关")
        append("　温度监听=").append(if (Prefs.vehicleTempWatcherEnabled(ctx)) "开" else "关").append('\n')
        append("车控写入=").append(if (Prefs.vehicleWriteEnabled(ctx)) "开" else "★关（写入被熔断，空调调不动）").append('\n')

        append("模拟模式=").append(if (Prefs.simulate(ctx)) "开" else "关")
        append("　媒体=").append(if (Prefs.mediaEnabled(ctx)) "开" else "关").append('\n')
        append("Native捕获=").append(NativeCrashHandler.statusText()).append('\n')
        append("====================\n\n")
    }

    /** 上传一个落盘日志文件（上次会话回捞用） */
    fun uploadFile(ctx: Context, file: File, kind: String, note: String = ""): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        return upload(ctx, kind, text, note)
    }

    /**
     * 同步上传（仅供 CrashHandler 在进程将死时调用）。
     * 必须阻塞，因为崩溃后进程很快会被系统回收，异步任务大概率来不及跑完。
     */
    fun uploadBlocking(ctx: Context, kind: String, log: String, note: String = "") {
        if (!isEnabled(ctx)) return
        val url = endpoint(ctx)
        if (url.isBlank()) return
        val payload = buildPayload(ctx, kind, log, note)
        val ok = post(url, payload)
        lastResult = if (ok) "崩溃日志已上传" else "崩溃日志上传失败"
    }

    /** 「诊断与日志」的测试上传发送一条最小记录。 */
    fun testUpload(ctx: Context) {
        upload(ctx, "test", "连通性测试：${nowText()}", "来自主界面测试按钮")
    }

    // =====================================================================
    // 内部实现
    // =====================================================================

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun buildPayload(ctx: Context, kind: String, log: String, note: String): String {
        // 优先保留崩溃记录开头和会话尾部，超长时省略中间内容。
        val body = if (log.length > MAX_LOG_CHARS) {
            val tailKeep = MAX_LOG_CHARS - HEAD_KEEP_CHARS
            val omitted = log.length - HEAD_KEEP_CHARS - tailKeep
            log.take(HEAD_KEEP_CHARS) +
                "\n…（中间 $omitted 字符已省略，多为 /proc/self/maps）…\n" +
                log.takeLast(tailKeep)
        } else log

        return JSONObject().apply {
            put("kind", kind)
            put("device", deviceId(ctx))
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("app", appVersion(ctx))
            put("time", nowText())
            if (note.isNotBlank()) put("note", note)
            put("log", body)
        }.toString()
    }

    private fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        "${pi.versionName}($code)"
    }.getOrDefault("unknown")

    /** 单次 POST，返回是否成功（2xx） */
    private fun post(urlStr: String, payload: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", "CarDockBar-LogUploader")
            }
            OutputStreamWriter(c.outputStream, "UTF-8").use { w ->
                w.write(payload)
                w.flush()
            }
            val code = c.responseCode
            // 读掉响应体，避免连接被复用时报错
            runCatching { c.inputStream?.close() }
            code in 200..299
        } catch (t: Throwable) {
            Logx.d("日志上传失败：${t.javaClass.simpleName} ${t.message}")
            false
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    // =====================================================================
    // 崩溃取证：供 App 下次启动时补传
    // =====================================================================

    fun logDir(ctx: Context): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    /** Java 崩溃堆栈（CrashHandler 写） */
    fun javaCrashFile(ctx: Context): File = File(ctx.filesDir, "last_crash.txt")

    /** Native 崩溃信息（native 信号处理器写，覆盖 SIGSEGV / SIGABRT 等） */
    fun nativeCrashFile(ctx: Context): File = File(logDir(ctx), "native_crash.txt")

    /** 保存 native 层重定向的 stderr，供下次启动读取 abort 原因。 */
    fun nativeAbortFile(ctx: Context): File = File(logDir(ctx), "native_crash.txt.err")

    private fun cleanExitMarker(ctx: Context) = File(logDir(ctx), "clean_exit")
    private fun crashUploadedMarker(ctx: Context) = File(logDir(ctx), "crash_uploaded.txt")

    /** 标记「这次退出是用户主动关闭」，用于下次启动判断上次是否为异常结束 */
    fun markCleanExit(ctx: Context) {
        runCatching { cleanExitMarker(ctx).writeText(nowText()) }
    }

    /**
     * 上次会话的崩溃取证 + 补传。**在 App.onCreate 里、日志轮转之后调用一次。**
     *
     * 处理顺序：
     *  1. 收集证据：Java 崩溃文件 + Native 崩溃文件 + 上次会话落盘日志
     *  2. 用「文件大小 + mtime」做指纹去重，同一个崩溃不重复上传
     *  3. 按有无崩溃堆栈决定 kind（crash / prev_session）
     *  4. 用 clean_exit 标记判断上次是否正常结束（区分「闪退」与「正常关闭」）
     *
     * @return 一句人类可读的结果，供主界面展示
     */
    internal fun reportPreviousSession(ctx: Context, logText: String): String {
        val java = javaCrashFile(ctx)
        val nat = nativeCrashFile(ctx)
        val abt = nativeAbortFile(ctx)

        val hasJava = java.exists() && java.length() > 0
        val hasNative = nat.exists() && nat.length() > 0
        val abortMsg = runCatching {
            if (abt.exists()) abt.readText().trim() else ""
        }.getOrDefault("")
        val hasAbort = abortMsg.isNotEmpty()

        // 上次是否正常结束（用户主动关 Dock 才会写这个标记）
        val clean = cleanExitMarker(ctx).exists()
        cleanExitMarker(ctx).delete()

        // 首次安装没有上次会话，clean_exit 本来就不存在，不能当成闪退。
        // 否则默认开启的温度监听会在用户打开设置前就被熔断关掉。
        val noPreviousSession = !hasJava && !hasNative && !hasAbort && logText.isBlank()
        lastReportHadCrash = !noPreviousSession && (hasJava || hasNative || !clean)
        lastReportHasCrashEvidence = hasJava || hasNative || hasAbort

        if (!Prefs.logUploadEnabled(ctx)) return "日志上传未启用"
        if (!hasJava && !hasNative && !hasAbort && logText.isBlank()) {
            // 无历史会话时发送启动心跳，便于确认上传链路。
            upload(ctx, "startup", buildHeartbeat(ctx), "应用启动（无上次会话记录）")
            return "无上次会话记录（已发 startup 心跳）"
        }

        // 以崩溃文件长度和修改时间去重，避免会话日志长度变化时重传同一证据。
        val fp = "${java.length()}-${java.lastModified()}|${nat.length()}-${nat.lastModified()}"
        val marker = crashUploadedMarker(ctx)
        if (runCatching { marker.readText().trim() }.getOrDefault("") == fp) {
            return "上次崩溃已上传过，跳过"
        }

        val sb = StringBuilder()
        // 把 abort 原因放在开头，使其优先保留。
        if (hasAbort) {
            sb.append("===== abort 原因（libc 写到 stderr，已重定向回捞）=====\n")
                .append(abortMsg).append("\n\n")
        }
        if (hasJava) {
            sb.append("===== Java 崩溃堆栈 =====\n")
                .append(runCatching { java.readText() }.getOrDefault("")).append("\n\n")
        }
        if (hasNative) {
            sb.append("===== Native 崩溃（信号处理器）=====\n")
                .append(runCatching { nat.readText() }.getOrDefault("")).append("\n\n")
        }
        sb.append("===== 崩溃前日志（上次会话，落盘回捞）=====\n").append(logText)

        val kind = if (hasJava || hasNative) "crash" else "prev_session"
        val note = buildString {
            append(
                when {
                    hasJava && hasNative -> "Java + Native 崩溃"
                    hasJava -> "Java 崩溃"
                    hasNative -> "Native 崩溃"
                    else -> "无崩溃堆栈"
                }
            )
            if (hasAbort) append("　abort原因=").append(abortMsg.take(120))
            append("；上次会话").append(if (clean) "正常结束" else "异常结束（疑似闪退/被系统杀死）")
        }

        val ok = upload(ctx, kind, sb.toString(), note)
        if (ok) {
            runCatching { marker.writeText(fp) }
            return "已补传上次崩溃：$note"
        }
        return "崩溃日志上传失败（已保留本地证据，下次启动重试）"
    }

    /** 清空崩溃取证（主界面「清除崩溃记录」用） */
    fun clearCrashEvidence(ctx: Context) {
        runCatching { javaCrashFile(ctx).delete() }
        runCatching { nativeCrashFile(ctx).delete() }
        runCatching { nativeAbortFile(ctx).delete() }
        runCatching { crashUploadedMarker(ctx).delete() }
    }

    /**
     * 启动心跳内容：一条极短的「我起来了」记录。
     *
     * 不依赖车控通道是否可用，所以平板 / 没有 ECarX HAL 的设备也能发出来，
     * 专门用来回答「上传链路到底通不通」这个问题。
     */
    private fun buildHeartbeat(ctx: Context): String = buildString {
        append("===== 启动心跳 =====\n")
        append("设备=").append(deviceId(ctx))
            .append("　机型=").append("${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android=").append(Build.VERSION.RELEASE).append("(SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        append("版本=").append(appVersion(ctx)).append("\n")
        append("安全等级=").append(Prefs.safeLevel(ctx)).append("\n")
        append("上传地址=").append(endpoint(ctx)).append("\n")
        append("Dock 是否已启动=").append(DockOverlayService.isRunning).append("\n")
    }

    // ---------------- 连续崩溃计数 / 安全模式阶梯 ----------------

    /** 上次会话是否崩溃（reportPreviousSession 的副产物，供 App 判定安全等级） */
    @Volatile
    var lastReportHadCrash: Boolean = false
        private set

    /** 与未正常退出区分的落盘崩溃证据，不被稳定运行标记或上传开关抹掉。 */
    @Volatile
    var lastReportHasCrashEvidence: Boolean = false
        private set

    /** 活过 [STABLE_MS] 就认为「稳定」，由 DockOverlayService 打点 */
    const val STABLE_MS = 15_000L

    private fun streakFile(ctx: Context) = File(logDir(ctx), "crash_streak.txt")
    private fun stableMarker(ctx: Context) = File(logDir(ctx), "stable.marker")

    /** 服务存活超过 STABLE_MS 时调用，表示这次启动是稳定的 */
    fun markStable(ctx: Context) {
        runCatching { stableMarker(ctx).writeText(nowText()) }
    }

    /**
     * 更新「连续崩溃次数」。App.onCreate 调用一次。
     * @return 更新后的连续崩溃次数（0 = 上次正常）
     */
    fun updateCrashStreak(ctx: Context, hadCrash: Boolean): Int {
        val stable = stableMarker(ctx).exists()
        stableMarker(ctx).delete()
        val prev = runCatching { streakFile(ctx).readText().trim().toInt() }.getOrDefault(0)
        val now = if (hadCrash && !stable) prev + 1 else 0
        runCatching { streakFile(ctx).writeText(now.toString()) }
        return now
    }

    /** 清零连续崩溃计数（版本升级 / 主界面手动恢复全功能时用） */
    fun resetCrashStreak(ctx: Context) {
        runCatching { streakFile(ctx).writeText("0") }
    }

    /** 当前连续崩溃次数（主界面展示用） */
    fun crashStreak(ctx: Context): Int =
        runCatching { streakFile(ctx).readText().trim().toInt() }.getOrDefault(0)

    /** 是否存在尚未上传的崩溃记录（主界面展示用） */
    fun hasPendingCrash(ctx: Context): Boolean {
        val fp = "${javaCrashFile(ctx).length()}-${javaCrashFile(ctx).lastModified()}|" +
                "${nativeCrashFile(ctx).length()}-${nativeCrashFile(ctx).lastModified()}|"
        val marker = runCatching { crashUploadedMarker(ctx).readText() }.getOrDefault("")
        val hasFile = (javaCrashFile(ctx).exists() && javaCrashFile(ctx).length() > 0) ||
                (nativeCrashFile(ctx).exists() && nativeCrashFile(ctx).length() > 0)
        return hasFile && !marker.startsWith(fp)
    }

    // ---------------- 在线实时上传 ----------------

    /** 攒批间隔：这段时间内的新行合成一条 POST */
    private const val LIVE_FLUSH_MS = 1500L
    /** 行数或字数先到就立刻发，避免连点时在缓冲里多等 */
    private const val LIVE_FLUSH_LINES = 40
    private const val LIVE_FLUSH_CHARS = 8000
    /** 离线上传队列独立限额，超出只留后半段。 */
    private const val LIVE_SPILL_MAX = 512 * 1024

    private val liveLock = Object()
    private val liveLines = ArrayDeque<String>()
    @Volatile private var liveCtx: Context? = null
    @Volatile private var netUp = true
    @Volatile private var liveStarted = false

    private val liveLineListener: (String) -> Unit = { line ->
        // 上传结果自己打的日志不再送一遍，否则失败重试会自己喂自己
        if (!line.contains("日志上传")) {
            synchronized(liveLock) {
                liveLines.addLast(line)
                liveLock.notifyAll()
            }
        }
    }

    /**
     * 开始把 [Logx] 的新行在有网时分批送出。
     * 在 [Logx.attachFile] 之后调用一次。重复调用无效果。
     */
    fun startLive(ctx: Context) {
        if (liveStarted) return
        liveStarted = true
        val app = ctx.applicationContext
        liveCtx = app
        Logx.addListener(liveLineListener)
        watchNetwork(app)
        Thread({ liveLoop() }, "log-live").apply { isDaemon = true }.start()
    }

    private fun watchNetwork(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    netUp = true
                    synchronized(liveLock) { liveLock.notifyAll() }
                }

                override fun onLost(network: Network) {
                    netUp = false
                }
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun networkOnline(ctx: Context): Boolean {
        if (!netUp) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val info = cm.activeNetworkInfo
        return info != null && info.isConnected
    }

    private fun liveLoop() {
        var backoff = LIVE_FLUSH_MS
        while (true) {
            try {
                val ctx = liveCtx
                if (ctx == null) {
                    SystemClock.sleep(500)
                    continue
                }
                if (!isEnabled(ctx) || endpoint(ctx).isBlank()) {
                    synchronized(liveLock) { liveLines.clear() }
                    spillFile(ctx).delete()
                    SystemClock.sleep(2000)
                    continue
                }
                if (spillFile(ctx).let { it.exists() && it.length() > 0L }) {
                    val chunk = takeSpill(ctx)
                    if (chunk.isNotEmpty()) {
                        if (!canSend(ctx) || !postLive(ctx, chunk, "离线补传")) {
                            restoreSpillFront(ctx, chunk)
                            SystemClock.sleep(backoff)
                            backoff = (backoff * 2).coerceAtMost(60_000L)
                        } else {
                            backoff = LIVE_FLUSH_MS
                        }
                        continue
                    }
                }
                val batch = awaitBatch()
                if (batch.isEmpty()) continue
                if (!canSend(ctx) || !postLive(ctx, batch, "在线实时")) {
                    spill(ctx, batch)
                    SystemClock.sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                } else {
                    backoff = LIVE_FLUSH_MS
                }
            } catch (t: Throwable) {
                android.util.Log.d("CarDockBar", "实时上传异常 ${t.javaClass.simpleName}: ${t.message}")
                SystemClock.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun canSend(ctx: Context): Boolean =
        isEnabled(ctx) && endpoint(ctx).isNotBlank() && networkOnline(ctx)

    /** 成功不写 Logx，避免「日志上传」那一行再触发一轮发送 */
    private fun postLive(ctx: Context, lines: List<String>, note: String): Boolean {
        val body = lines.joinToString("\n")
        if (body.isBlank()) return true
        val ok = post(endpoint(ctx), buildPayload(ctx, "live", body, note))
        lastResult = if (ok) "实时已上传 ${lines.size} 行" else "实时上传失败，已暂存待补传"
        return ok
    }

    /** 等到有日志，再最多等 [LIVE_FLUSH_MS] 把紧挨着的几行合成一批 */
    private fun awaitBatch(): List<String> {
        synchronized(liveLock) {
            while (liveLines.isEmpty()) {
                liveLock.wait(30_000)
            }
            val deadline = SystemClock.elapsedRealtime() + LIVE_FLUSH_MS
            while (liveLines.size < LIVE_FLUSH_LINES && SystemClock.elapsedRealtime() < deadline) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                liveLock.wait(left)
            }
            val out = ArrayList<String>(LIVE_FLUSH_LINES)
            var chars = 0
            while (liveLines.isNotEmpty() && out.size < LIVE_FLUSH_LINES && chars < LIVE_FLUSH_CHARS) {
                val line = liveLines.removeFirst()
                out.add(line)
                chars += line.length + 1
            }
            return out
        }
    }

    private fun spillFile(ctx: Context): File = File(logDir(ctx), "live_queue.txt")

    private fun spill(ctx: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        val f = spillFile(ctx)
        f.appendText(lines.joinToString("\n", postfix = "\n"))
        if (f.length() > LIVE_SPILL_MAX) {
            val keep = f.readText().takeLast(LIVE_SPILL_MAX / 2)
            val cut = keep.indexOf('\n')
            f.writeText(if (cut >= 0) keep.substring(cut + 1) else keep)
        }
    }

    /** 从离线队列头部取出一批，文件里留下剩余部分 */
    private fun takeSpill(ctx: Context): List<String> {
        val f = spillFile(ctx)
        if (!f.exists() || f.length() == 0L) return emptyList()
        val lines = f.readText().lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) {
            f.delete()
            return emptyList()
        }
        val take = ArrayList<String>(LIVE_FLUSH_LINES)
        var chars = 0
        var idx = 0
        while (idx < lines.size && take.size < LIVE_FLUSH_LINES && chars < LIVE_FLUSH_CHARS) {
            val line = lines[idx]
            if (take.isNotEmpty() && chars + line.length > LIVE_FLUSH_CHARS) break
            take.add(line)
            chars += line.length + 1
            idx++
        }
        val rest = lines.drop(idx)
        if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n", postfix = "\n"))
        return take
    }

    private fun restoreSpillFront(ctx: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        val f = spillFile(ctx)
        val rest = if (f.exists()) f.readText() else ""
        f.writeText(lines.joinToString("\n", postfix = "\n") + rest)
        if (f.length() > LIVE_SPILL_MAX) {
            val keep = f.readText().takeLast(LIVE_SPILL_MAX / 2)
            val cut = keep.indexOf('\n')
            f.writeText(if (cut >= 0) keep.substring(cut + 1) else keep)
        }
    }

    // ---------------- 失败重试队列 ----------------

    private fun pendingFile(ctx: Context): File =
        File(File(ctx.filesDir, "logs").apply { mkdirs() }, "pending.jsonl")

    private fun appendPending(ctx: Context, payload: String) {
        runCatching { pendingFile(ctx).appendText(payload + "\n") }
    }

    /** 把之前失败的记录补传一遍（最多 20 条，避免堆积） */
    private fun flushPending(ctx: Context, url: String) {
        val f = pendingFile(ctx)
        if (!f.exists() || f.length() == 0L) return
        runCatching {
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@runCatching
            val keep = ArrayList<String>()
            lines.take(20).forEach { line ->
                if (!post(url, line)) keep.add(line)
            }
            keep.addAll(lines.drop(20))
            if (keep.isEmpty()) f.delete() else f.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }
}
