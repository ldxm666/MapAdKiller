package io.github.ldxm666.mapadkiller

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.util.Log

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Properties

/**
 * 官方基线抓取（v2.2.5）。
 *
 * <p><b>为什么需要它</b>：本模块此前在没拿到官方原样数据的情况下猜 payload schema
 * （`split`/`margin`/`radius`/… 全猜错），把高德本来正常的
 * `home_and_company_data` 挡掉，导致「设置家」一行消失。规则改成：
 * <b>先有官方基线，再谈改动</b>。
 *
 * <p><b>行为</b>：模块刚安装 / 刚升级（没有当前 versionCode 的基线标记）后的
 * <b>第一次宿主启动</b>，[maybeCapture] 返回 `true`，`MainHook`
 * 据此在本进程内 <b>一个 hook 都不装</b>（等价于模块没装，高德跑纯官方）。后台线程
 * `mak-baseline` 等首页稳定后，把宿主 `shared_prefs/` 原样复制到
 * `<宿主 filesDir>/baseline/<模块 versionName>/shared_prefs/`，写 `meta.json`
 * 与标记 `baseline_captured_v<versionCode>=true`。<b>下一次启动起 hook 才生效</b>。
 *
 * <p><b>标记存哪</b>：LSPosed 的 RemotePreferences 在 hook 进程里是**只读实现**
 * （实测 `SharedPreferences.edit()` 抛 `UnsupportedOperationException:
 * Read only implementation`），所以标记落在 `<宿主 filesDir>/baseline/state.properties`
 * —— 模块自己管理的子树，**不是**宿主 shared_prefs。RemotePreferences 只用于面板
 * 「重新抓取」的单向信号（App 侧可写，hook 侧可读）。
 *
 * <p><b>硬约束</b>（实现上逐条对应）：
 * <ul>
 *   <li>宿主 `shared_prefs` <b>只读</b>：只 `FileInputStream`，从不
 *       `getSharedPreferences(...).edit()` 写宿主任何键；</li>
 *   <li>所有异常 `try/catch` 吞掉，只写 `baseline_failed_v<code>` 标记 +
 *       日志，<b>绝不抛出到宿主启动路径</b>；</li>
 *   <li>抓取全程在后台线程，主线程只做「读一个小 state 文件」这一件事；</li>
 *   <li>不依赖重启设备：LSPosed 下装完只需强停宿主再打开。</li>
 * </ul>
 *
 * <p><b>时机</b>：优先检测 `shared_prefs/main_page.xml` 的
 * `lastModified() >= 本次进程启动时刻`（说明这次启动真的把首页数据写盘了），
 * 命中后再等 [SETTLE_MS] 让同批写入落完；兜底 [FALLBACK_MS] 后无条件抓
 * （meta 里标 `"fallback": true`）。不能只看「文件存在」：老用户重装模块时该文件
 * 早就存在，会秒判成旧数据。
 */
object Baseline {

    /** 模块自己的 prefs group（LSPosed RemotePreferences）。面板侧用 XposedService 同 group 读写。 */
    const val PREF_GROUP = "mapadkiller_baseline"

    const val K_CAPTURED = "baseline_captured_v"
    const val K_FAILED = "baseline_failed_v"
    const val K_GAVE_UP = "baseline_gave_up_v"
    const val K_ATTEMPTS = "baseline_attempts_v"
    const val K_VERSION = "baseline_version"
    const val K_CODE = "baseline_code"
    const val K_CAPTURED_AT = "baseline_captured_at"
    const val K_PATH = "baseline_path"
    const val K_FILES = "baseline_files"
    const val K_FALLBACK = "baseline_fallback"
    const val K_FAIL_REASON = "baseline_failed_reason"
    /** 面板「重新抓取」信号（RemotePreferences，App 侧写入，hook 侧只读）。 */
    const val K_RECAPTURE_AT = "baseline_recapture_at"
    private const val K_RECAPTURE_HANDLED = "recapture_handled_at"

    /** 基线根目录名（位于宿主 `getFilesDir()` 下）。 */
    const val DIR = "baseline"
    private const val STATE_FILE = "state.properties"

    /** 首页稳定兜底：进程启动后最多等这么久就无条件抓。 */
    private const val FALLBACK_MS = 90_000L
    /**
     * 兜底抓取的文件数下限：真实家用机的 shared_prefs 有上百个文件，个位数说明这次启动要么闪退、
     * 要么还没写出配置。这种半成品不能当「已抓到」——否则首启一次闪退就把官方数据永久写坏了。
     */
    private const val MIN_FILES = 20
    /** 检测到 main_page.xml 本次启动被写过之后，再等这么久让同批 prefs 落盘。 */
    private const val SETTLE_MS = 5_000L
    /** 关键 prefs 齐全后，目录还要稳定这么久才算抓完（避免抓到一半就重启）。 */
    private const val STABLE_MS = 2_500L
    /** 等关键 prefs 齐全的上限：首启慢网/隐私弹窗都可能拖到十几秒。 */
    private const val MAX_SETTLE_MS = 40_000L
    /** 等宿主 Context 的上限（onPackageReady 时 Application 可能还没创建）。 */
    private const val CONTEXT_WAIT_MS = 20_000L
    /** 失败重试次数上限：超过就放弃基线（保证模块不会永远 passthrough）。 */
    private const val MAX_ATTEMPTS = 5
    /** 面板「开始抓取」写入的采集模式标记（宿主机 files/baseline/ 下，与 CaptureGate 同名）。 */
    private const val F_CAPTURE_MODE = "capture.mode"
    /** 抓到基线后落的空文件，供面板判断「已就绪」。 */
    private const val F_CAPTURE_DONE = "captured.marker"

    /** 关键单文件：除了整目录复制，再单独存一份到 `<baseline>/<ver>/key/`。 */
    private val KEY_FILES = arrayOf(
            "main_page.xml",
            "toolbox.xml",
            "sync_time_file.xml",
            "cloudconfig_aocs_sp_key_value.xml"
    )

    /** 本次进程是否处于「抓取模式」＝ 所有 hook 必须透传。 */
    @Volatile
    private var sCapturing = false
    /** 每个进程只判定一次（onPackageReady 可能被调用多次）。 */
    @Volatile
    private var sDecided = false

    /** 所有 hook 安装处的总闸门：true ⇒ 本进程不装任何 hook（纯官方）。 */
    @JvmStatic
    fun isCapturing(): Boolean {
        return sCapturing
    }

    /**
     * 在 `mapadkiller.MainHook.onPackageReady` 的**最早期**调用（同步，只读标记）。
     *
     * @return true = 本次启动是「基线抓取启动」，调用方必须直接 return、不装任何 hook。
     */
    @JvmStatic
    @Synchronized
    fun maybeCapture(): Boolean {
        if (sDecided) return sCapturing
        sDecided = true
        try {
            val version = moduleVersionName()
            val code = moduleVersionCode()
            // 每次模块加载都把「现在到底抓到没」回报给设置页（用户升级模块后第一次开高德，
            // 首页那一行就能立刻对上，不用等他自己再点一次抓取）。
            if (isMainProcess()) reportStateAsync(version, code)
            if (!baselineEnabled()) {
                H.log(Log.INFO, TAG, "event=baseline_skip reason=disabled")
                sCapturing = false
                return false
            }
            val state = readState()
            if (state == null) {
                H.log(Log.WARN, TAG, "event=baseline_skip reason=no_state_store version=" + version)
                sCapturing = false
                return false
            }
            val stateFile = stateFile()
            H.log(Log.INFO, TAG, "event=baseline_state path=" + (if (stateFile == null) "null" else stateFile.absolutePath)
                    + " exists=" + (stateFile != null && stateFile.isFile))
            val recaptureAt = remoteRecaptureAt()
            val handledAt = parseLong(state.getProperty(K_RECAPTURE_HANDLED), 0L)
            val captured = "true" == state.getProperty(K_CAPTURED + code)
            // 只要「抓完的时间」晚于「用户点抓取的时间」，这次请求客观上就已经被满足了。
            // 之前只看 handled_at，只要它被任何一次隐式抓取写成 0，请求就永远 pending，
            // 于是每次冷启动都判成抓取模式 —— 用户看到的正是「抓取成功但模块怎么都不生效」。
            val capturedAt = parseLong(state.getProperty(K_CAPTURED_AT), 0L)
            val requestSatisfied = recaptureAt <= 0L || handledAt >= recaptureAt
                    || (captured && capturedAt >= recaptureAt)
            // 面板「开始抓取 / 重新抓取」写入的显式采集模式（files/baseline/capture.mode）。
            // 必须排在「已抓到就跳过」**前面**，否则已抓过的用户点「重新抓取」只会白重启两次、根本不重抓。
            val modeFile = captureModeFile()
            val userCapture = modeFile != null && modeFile.isFile

            if (!userCapture && captured && requestSatisfied) {
                H.log(Log.INFO, TAG, "event=baseline_skip reason=captured v=" + version
                        + " code=" + code + " files=" + state.getProperty(K_FILES, "0"))
                sCapturing = false
                return false
            }
            // 用户主动点了「开始抓取 / 重新抓取」：一直抓到成功为止，不受 attempt 次数限制，
            // 已抓过也要重抓一次（换城市 / 高德大版本更新后的标准入口）。
            if (userCapture) {
                if (isMainProcess()) {
                    state.setProperty(K_ATTEMPTS + code, "0")
                    state.remove(K_GAVE_UP + code)
                    saveState(state)
                    setCaptureRunning(true)
                    startWorker(version, code, recaptureAt)
                }
                sCapturing = true
                H.log(Log.INFO, TAG, "event=baseline_mode on reason=user_capture captured=" + captured
                        + " version=" + version + " code=" + code + " process=" + processName())
                return true
            }

            // 子进程一律跟随主进程：没基线就一起透传（否则主进程纯官方、子进程却挂了 hook）。
            // 子进程不写 .attempt、也不做 force_release 判定，避免把主进程「本次启动刚写」的标记
            // 误判成「上次已 passthrough」。
            if (!captured && !isMainProcess()) {
                sCapturing = true
                H.log(Log.INFO, TAG, "event=baseline_passthrough process=" + processName() + " mode=subprocess")
                return true
            }
            // v2.2.6：删掉「passthrough 只允许一次」的强制放行 —— 真机上第二次启动就带着空基线
            // 把 hook 全装上，工具宫格图标整片不显示。现在**没抓到基线就一直跑纯官方**，
            // 安全阀只剩 MAX_ATTEMPTS 次上限（抓不到才放行，绝不会把模块永久锁死）。
            val attemptMark = attemptFile(code)
            if (!captured && attemptMark != null && attemptMark.isFile) {
                H.log(Log.INFO, TAG, "event=baseline_passthrough reason=retry v=" + version
                        + " code=" + code)
            }
            if (!captured && "true" == state.getProperty(K_GAVE_UP + code)) {
                H.log(Log.INFO, TAG, "event=baseline_skip reason=give_up v=" + version + " code=" + code)
                sCapturing = false
                return false
            }
            val attempts = parseLong(state.getProperty(K_ATTEMPTS + code), 0L).toInt()
            if (attempts >= MAX_ATTEMPTS) {
                state.setProperty(K_GAVE_UP + code, "true")
                saveState(state)
                H.log(Log.WARN, TAG, "event=baseline_skip reason=max_attempts attempts=" + attempts)
                sCapturing = false
                return false
            }

            if (isMainProcess()) markAttempt(code)
            sCapturing = true
            H.log(Log.INFO, TAG, "event=baseline_mode on version=" + version + " code=" + code
                    + " attempt=" + (attempts + 1) + " recapture=" + (recaptureAt > handledAt)
                    + " process=" + processName())
            // 只有主进程负责真正抓取；子进程（:locationservice 等）只需要跟着 passthrough。
            if (isMainProcess()) {
                startWorker(version, code, recaptureAt)
            } else {
                H.log(Log.INFO, TAG, "event=baseline_worker_skipped reason=subprocess process=" + processName())
            }
            return true
        } catch (t: Throwable) {
            // 任何异常都不能影响宿主启动
            sCapturing = false
            H.log(Log.WARN, TAG, "event=baseline_mode_failed", t)
            return false
        }
    }

    // ══════════════════════════════════════════════════════════════════ 后台抓取

    /** 启动 4 秒后把当前基线状态广播给设置页（抓取会话自己会在结束时回报，这里跳过）。 */
    private fun reportStateAsync(version: String, code: Int) {
        try {
            val t = Thread(object : Runnable {
                override fun run() {
                    try {
                        sleep(4000L)
                        if (sCapturing) return
                        val ctx = waitHostContext(CONTEXT_WAIT_MS) ?: return
                        val st = readState()
                        val cap = st != null && "true" == st.getProperty(K_CAPTURED + code)
                        val files = if (st == null) 0 else parseLong(st.getProperty(K_FILES), 0L).toInt()
                        CaptureReport.attach(ctx)
                        CaptureReport.send(ctx, CaptureReport.S_STATE, cap, files, version, null)
                    } catch (ignored: Throwable) { }
                }
            }, "mak-report-state")
            t.isDaemon = true
            t.start()
        } catch (ignored: Throwable) { }
    }

    private fun startWorker(version: String, code: Int, recaptureAt: Long) {
        try {
            val t = Thread(object : Runnable {
                override fun run() {
                    val t0 = System.currentTimeMillis()
                    try {
                        runCapture(version, code, recaptureAt, t0)
                    } catch (error: Throwable) {
                        fail(version, code, error)
                    }
                }
            }, "mak-baseline")
            t.isDaemon = true
            t.start()
        } catch (t: Throwable) {
            fail(version, code, t)
        }
    }

    @Throws(Exception::class)
    private fun runCapture(version: String, code: Int, recaptureAt: Long, t0: Long) {
        H.log(Log.INFO, TAG, "event=baseline_capture_start version=" + version + " code=" + code
                + " process=" + processName())
        val ctx = waitHostContext(CONTEXT_WAIT_MS)
                ?: throw IllegalStateException("no_host_context")
        CaptureReport.attach(ctx)
        // 「用户主动点的抓取」= 面板写了 capture.mode，或面板/兜底文件写了 recapture 请求。
        // 只在这条路上自动重启（用户刚点完按钮，重启是他预期的）；首次安装的隐式抓取只提示、
        // 不杀进程，避免用户正开着导航时被打断。
        val modeFile0 = captureModeFile()
        val explicit = (recaptureAt > 0L)
                || (modeFile0 != null && modeFile0.isFile)

        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val mainPage = File(prefsDir, "main_page.xml")

        var homeSeen = false
        while (System.currentTimeMillis() - t0 < FALLBACK_MS) {
            try {
                if (mainPage.isFile && mainPage.lastModified() >= t0) {
                    homeSeen = true
                    break
                }
            } catch (ignored: Throwable) { }
            sleep(1000L)
        }
        var fallback = false
        if (homeSeen) {
            H.log(Log.INFO, TAG, "event=baseline_home_ready file=main_page.xml wait_ms="
                    + (System.currentTimeMillis() - t0))
            sleep(SETTLE_MS)
            // 首启时 shared_prefs 是陆续落盘的：只等 5 秒会抓到「首页有了、工具箱/云控还没写」的半套，
            // 重启后就是图标不显示、家/单位不生效（真机 92 个文件 vs 完整 143 个）。这里等关键文件齐
            // 且目录连续 STABLE_MS 没有新写入为止，最多 MAX_SETTLE_MS。
            waitPrefsComplete(prefsDir)
        } else {
            fallback = true
            H.log(Log.INFO, TAG, "event=baseline_home_timeout fallback_ms=" + FALLBACK_MS)
        }

        val files = capture(ctx, version, code, prefsDir, fallback)
        // 关键文件不齐 = 这次抓的是半套（首启常见），一律不算成功，避免带着半套基线重启。
        val complete = keyFilesComplete(ctx, version)
        if (!complete || (fallback && files < MIN_FILES)) {
            val reason = if (complete) "fallback_thin" else "prefs_incomplete"
            // ★ 只有「本来就没抓到过」才删半成品目录。
            // 旧实现无条件 deleteRecursively(baseline/<ver>)：已经有一份好基线的用户点一次重抓、
            // 恰好这次抓薄了，好基线就被连根删掉，之后永远显示未抓取 —— 这就是真机反馈的
            // 「抓取云数据一直失败」。
            val st = readState()
            val hasGood = st != null && "true" == st.getProperty(K_CAPTURED + code)
            if (!hasGood) {
                try {
                    deleteRecursively(File(File(ctx.filesDir, DIR), version))
                } catch (ignored: Throwable) { }
            }
            H.log(Log.WARN, TAG, "event=baseline_capture_retry files=" + files
                    + " reason=" + reason + " keep_good=" + hasGood + " version=" + version)
            setCaptureRunning(false)
            // 手动采集模式下薄基线不算成功：撤掉采集标记，避免下次启动无限重抓（用户可再点一次）。
            try {
                val mode = captureModeFile()
                if (mode != null && mode.isFile) mode.delete()
            } catch (ignored: Throwable) { }
            // 请求也要「消费掉」：否则每次冷启动都判定 pending → 面板一请求就重启 → 抓取失败 →
            // 又重启，用户眼里就是高德无限重启。失败原因回报给设置页，用户可再点一次。
            if (recaptureAt > 0L) {
                try {
                    val s2 = st ?: Properties()
                    s2.setProperty(K_RECAPTURE_HANDLED, recaptureAt.toString())
                    s2.setProperty(K_FAIL_REASON, reason)
                    saveState(s2)
                } catch (ignored: Throwable) { }
            }
            clearRecaptureRequest()
            CaptureReport.send(ctx, CaptureReport.S_FAILED, hasGood, files, version, reason)
            return
        }
        markCaptured(version, code, files, fallback, recaptureAt)
        H.log(Log.INFO, TAG, "event=baseline_capture_done files=" + files
                + " version=" + version + " mode=" + (if (fallback) "fallback" else "home")
                + " cost_ms=" + (System.currentTimeMillis() - t0))
        // 抓完了必须让设置页知道：宿主进程写不进 RemotePreferences（hook 侧只读），
        // 只能走显式广播。设置页首页那一行就靠这条消息从「未抓取」变成「已抓取 N 项」。
        CaptureReport.send(ctx, CaptureReport.S_DONE, true, files, version, null)
        maybeAutoRestart(ctx, explicit, files)
    }

    /**
     * 抓完之后的收尾：用户主动点的抓取 → 3 秒后自动重启宿主，回到「带 hook 的一次干净启动」；
     * 首次安装的隐式抓取 → 只提示，不动进程（用户可能正在导航）。
     */
    private fun maybeAutoRestart(ctx: Context, explicit: Boolean, files: Int) {
        if (!isMainProcess()) return
        try {
            if (explicit) {
                io.github.ldxm666.mapclean.CaptureGate.toast(ctx,
                        "✓ 官方数据已抓取完成（" + files + " 项）· 正在重启高德启用模块…")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable {
                    override fun run() {
                        try {
                            io.github.ldxm666.mapclean.CaptureGate.restartHost(ctx)
                        } catch (ignored: Throwable) { }
                    }
                }, 3000L)
                H.log(Log.INFO, TAG, "event=baseline_auto_restart files=" + files)
            } else {
                io.github.ldxm666.mapclean.CaptureGate.toast(ctx,
                        "✓ 官方数据已抓取完成（" + files + " 项）· 重新打开高德即可启用模块")
                H.log(Log.INFO, TAG, "event=baseline_restart_hint files=" + files)
            }
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "event=baseline_restart_hint_failed err=" + t)
        }
    }

    /** 等关键 prefs 齐 + 目录稳定；超时就让上层按「不完整」处理。 */
    private fun waitPrefsComplete(prefsDir: File) {
        val start = System.currentTimeMillis()
        var lastChange = 0L
        var lastStamp = -1L
        while (System.currentTimeMillis() - start < MAX_SETTLE_MS) {
            var stamp = 0L
            var missing = 0
            try {
                for (name in KEY_FILES) {
                    val f = File(prefsDir, name)
                    if (!f.isFile) missing++
                    else stamp = Math.max(stamp, f.lastModified())
                }
                val all = prefsDir.listFiles()
                if (all != null) {
                    for (f in all) if (f != null) stamp = Math.max(stamp, f.lastModified())
                }
            } catch (ignored: Throwable) { }
            if (stamp != lastStamp) {
                lastStamp = stamp
                lastChange = System.currentTimeMillis()
            } else if (missing == 0 && System.currentTimeMillis() - lastChange >= STABLE_MS) {
                H.log(Log.INFO, TAG, "event=baseline_prefs_complete wait_ms="
                        + (System.currentTimeMillis() - start))
                return
            }
            sleep(500L)
        }
        H.log(Log.WARN, TAG, "event=baseline_prefs_timeout wait_ms="
                + (System.currentTimeMillis() - start))
    }

    /** 抓下来的基线里关键文件是否齐全。 */
    private fun keyFilesComplete(ctx: Context, version: String): Boolean {
        try {
            val keyDir = File(File(File(ctx.filesDir, DIR), version), "key")
            for (name in KEY_FILES) {
                if (!File(keyDir, name).isFile) {
                    H.log(Log.WARN, TAG, "event=baseline_incomplete missing=" + name)
                    return false
                }
            }
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    /** 真正做复制；返回复制的 prefs 文件数。 */
    @Throws(Exception::class)
    private fun capture(ctx: Context, version: String, code: Int, srcPrefs: File, fallback: Boolean): Int {
        val out = File(File(ctx.filesDir, DIR), version)
        val outPrefs = File(out, "shared_prefs")
        deleteRecursively(out)
        if (!outPrefs.isDirectory && !outPrefs.mkdirs()) {
            throw java.io.IOException("mkdirs_failed " + outPrefs)
        }

        val files = copyTree(srcPrefs, outPrefs)
        if (files <= 0) throw java.io.IOException("no_files_copied src=" + srcPrefs)

        val keyDir = File(out, "key")
        if (!keyDir.isDirectory) keyDir.mkdirs()
        var keyFiles = 0
        for (name in KEY_FILES) {
            val s = File(srcPrefs, name)
            if (!s.isFile) continue
            if (copyFile(s, File(keyDir, name))) keyFiles++
        }

        writeMeta(out, version, code, files, keyFiles, ctx, fallback)
        return files
    }

    private fun copyTree(srcDir: File, dstDir: File): Int {
        var n = 0
        try {
            val list = srcDir.listFiles() ?: return 0
            for (s in list) {
                if (s == null) continue
                if (s.isDirectory) {
                    val sub = File(dstDir, s.name)
                    if (sub.isDirectory || sub.mkdirs()) n += copyTree(s, sub)
                } else if (s.isFile) {
                    if (copyFile(s, File(dstDir, s.name))) n++
                }
            }
        } catch (ignored: Throwable) { }
        return n
    }

    private fun copyFile(src: File, dst: File): Boolean {
        var ins: InputStream? = null
        var out: OutputStream? = null
        try {
            ins = FileInputStream(src)
            out = FileOutputStream(dst)
            val buf = ByteArray(8192)
            var r = ins.read(buf)
            while (r > 0) { out.write(buf, 0, r); r = ins.read(buf) }
            out.flush()
            return true
        } catch (t: Throwable) {
            return false
        } finally {
            closeQuietly(ins)
            closeQuietly(out)
        }
    }

    private fun writeMeta(out: File, version: String, code: Int, fileCount: Int, keyCount: Int,
                          ctx: Context, fallback: Boolean) {
        try {
            val o = org.json.JSONObject()
            o.put("versionName", version)
            o.put("versionCode", code)
            o.put("capturedAt", System.currentTimeMillis())
            o.put("capturedAtText", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.US).format(java.util.Date()))
            o.put("fileCount", fileCount)
            o.put("fallback", fallback)
            o.put("keyFileCount", keyCount)
            try {
                o.put("hostPackage", ctx.packageName)
                val pi: PackageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                o.put("hostVersionName", pi.versionName)
                o.put("hostVersionCode", pi.versionCode)
            } catch (ignored: Throwable) { }
            writeText(File(out, "meta.json"), o.toString(2))
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "event=baseline_meta_failed", t)
        }
    }

    /** 抓取进行中标记：面板靠它把通栏显示成「正在抓取」而不是「已抓到」。 */
    private fun setCaptureRunning(on: Boolean) {
        try {
            val d = stateDir() ?: return
            val f = File(d, "capture.running")
            if (!on) {
                if (f.isFile) f.delete()
                return
            }
            if (!f.isFile) f.createNewFile()
        } catch (ignored: Throwable) { }
    }

    private fun markCaptured(version: String, code: Int, files: Int, fallback: Boolean, recaptureAt: Long) {
        setCaptureRunning(false)
        try {
            var p = readState()
            if (p == null) p = Properties()
            p.setProperty(K_CAPTURED + code, "true")
            p.setProperty(K_VERSION, version)
            p.setProperty(K_CODE, code.toString())
            p.setProperty(K_CAPTURED_AT, System.currentTimeMillis().toString())
            p.setProperty(K_PATH, "files/" + DIR + "/" + version)
            p.setProperty(K_FILES, files.toString())
            p.setProperty(K_FALLBACK, fallback.toString())
            p.setProperty(K_ATTEMPTS + code, "0")
            p.remove(K_FAILED + code)
            // 只有「用户显式请求的那一次」才推进 handled_at；隐式抓取写 0 会把旧请求复活，
            // 从而导致「抓到 → 重启 → 又判 pending → 再抓」的死循环（线上就是这么转圈）。
            if (recaptureAt > 0L) p.setProperty(K_RECAPTURE_HANDLED, recaptureAt.toString())
            saveState(p)
            // 抓完就撤掉采集模式标记：用户没看到弹窗、进程被杀，下次启动也不会无限重抓。
            try {
                val mode = captureModeFile()
                if (mode != null && mode.isFile) mode.delete()
            } catch (ignored: Throwable) { }
            // 面板靠这个空文件判断「已抓到」——写失败不影响抓取本身。
            // restart.pending 让冷启动这一次（hook 还没生效的会话）弹窗提示并自动重启，
            // 用户不用自己再手动杀一次进程。
            try {
                val done = captureDoneFile()
                if (done != null && (done.isFile || done.createNewFile())) {
                    done.setLastModified(System.currentTimeMillis())
                }
                val dir = stateDir()
                if (dir != null) {
                    val pending = File(dir, "restart.pending")
                    if (!pending.isFile) pending.createNewFile()
                }
            } catch (ignored: Throwable) { }
            // 尽力镜像到 RemotePreferences（只读实现会抛，忽略即可）
            try {
                val rp = remotePrefs()
                if (rp != null) {
                    rp.edit().putBoolean(K_CAPTURED + code, true)
                            .putString(K_VERSION, version)
                            .putInt(K_FILES, files)
                            .commit()
                }
            } catch (ignored: Throwable) { }
        } catch (t: Throwable) {
            val sf = stateFile()
            H.log(Log.WARN, TAG, "event=baseline_mark_failed path="
                    + (if (sf == null) "null" else sf.absolutePath) + " err=" + t, t)
        }
    }

    private fun fail(version: String, code: Int, error: Throwable?) {
        setCaptureRunning(false)
        var attempts = 0
        try {
            val p = readState()
            if (p != null) {
                attempts = parseLong(p.getProperty(K_ATTEMPTS + code), 0L).toInt() + 1
                p.setProperty(K_FAILED + code, "true")
                p.setProperty(K_ATTEMPTS + code, attempts.toString())
                p.setProperty(K_FAIL_REASON, if (error == null) "unknown"
                else (error.javaClass.simpleName + ": " + error.message))
                p.setProperty("baseline_failed_at", System.currentTimeMillis().toString())
                if (attempts >= MAX_ATTEMPTS) p.setProperty(K_GAVE_UP + code, "true")
                saveState(p)
            }
        } catch (ignored: Throwable) { }
        H.log(Log.WARN, TAG, "event=baseline_capture_failed version=" + version
                + " attempts=" + attempts + " reason=" + (if (error == null) "unknown" else error), error)
    }

    // ══════════════════════════════════════════════════════════════════ 面板 API

    /**
     * 面板「重新抓取基线」：清掉当前模块版本的标记，下次宿主启动重抓。
     * 由模块 App 进程调用（[App.svc] 已连上 LSPosed 服务时可用）。
     */
    @JvmStatic
    fun requestRecapture(): Boolean {
        var ok = false
        sLastRequestError = null
        try {
            val s = App.svc()
            if (s != null) {
                val p = s.getRemotePreferences(PREF_GROUP)
                ok = p.edit().putLong(K_RECAPTURE_AT, System.currentTimeMillis()).commit()
                if (!ok) sLastRequestError = "write_remote_prefs_failed"
            } else {
                sLastRequestError = "service_not_bound"
            }
        } catch (t: Throwable) {
            sLastRequestError = "remote_prefs:" + t.javaClass.simpleName
        }
        // root 兜底：**只写一个请求文件，绝不删 state.properties**。
        // 旧实现 `rm -f state.properties` 会把已经抓到的好基线标记一起删掉，一旦重抓失败
        // 用户就永久没有基线（真机反馈「抓取一直失败」有一部分就是它）。
        if (!ok) {
            try {
                val dir = File(File("/data/data/com.autonavi.minimap", "files"), DIR)
                val req = File(dir, "recapture.request")
                val cmd = "mkdir -p '" + dir.absolutePath + "'" +
                        " && date +%s > '" + req.absolutePath + "'" +
                        " && chmod 666 '" + req.absolutePath + "'"
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                if (p.waitFor() == 0) {
                    ok = true
                    sLastRequestError = null
                } else if (sLastRequestError == null) {
                    sLastRequestError = "su_denied"
                }
            } catch (t: Throwable) {
                if (sLastRequestError == null) sLastRequestError = "su_unavailable"
            }
        }
        // 设置页进程里 H 类初始化会失败（没有 libxposed API），这里必须走 SafeLog：
        // 之前就是这一句把已经写成功的请求打断，用户看到「请求失败」。
        SafeLog.i(TAG, "event=baseline_recapture_requested ok=" + ok
                + " err=" + sLastRequestError)
        return ok
    }

    /** 最近一次「请求重新抓取」失败的原因；成功或还没请求过返回 null。 */
    @JvmStatic
    fun lastRequestError(): String? {
        return sLastRequestError
    }

    /** 面板显示用：当前版本是否已抓到基线（先读 RemotePreferences 镜像，再读 state，最后 root）。 */
    @JvmStatic
    fun isCaptured(): Boolean {
        val rp = panelPrefs()
        if (rp != null) {
            try {
                if (rp.getBoolean(K_CAPTURED + moduleVersionCode(), false)) return true
            } catch (ignored: Throwable) { }
        }
        try {
            val p = readState()
            if (p != null) return "true" == p.getProperty(K_CAPTURED + moduleVersionCode())
        } catch (ignored: Throwable) { }
        try {
            val st = File(File(File("/data/data/com.autonavi.minimap", "files"), DIR), STATE_FILE)
            val pr = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '" + st.absolutePath + "'"))
            val key = K_CAPTURED + moduleVersionCode() + "=true"
            // ★ 原来这里既不关流也不销毁进程：每次面板探测都漏一个 fd + 一个 su 子进程。
            BufferedReader(InputStreamReader(pr.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    if (line.trim() == key) return true
                    line = r.readLine()
                }
            }
            return false
        } catch (t: Throwable) {
            return false
        }
    }

    /** 面板（模块 App 进程）读 RemotePreferences：hook 侧可读、App 侧经 LSPosed 服务读。 */
    private fun panelPrefs(): SharedPreferences? {
        return try {
            val s = App.svc()
            s?.getRemotePreferences(PREF_GROUP)
        } catch (t: Throwable) {
            null
        }
    }

    /** 面板显示用：已抓到的配置文件数（读不到返回 0）。 */
    @JvmStatic
    fun capturedFiles(): Int {
        val rp = panelPrefs()
        if (rp != null) {
            try {
                val n = rp.getInt(K_FILES, 0)
                if (n > 0) return n
            } catch (ignored: Throwable) { }
        }
        try {
            val p = readState()
            if (p != null) return parseLong(p.getProperty(K_FILES), 0L).toInt()
        } catch (ignored: Throwable) { }
        return 0
    }

    /** 面板显示用：基线抓取时间（0 = 不知道）。 */
    @JvmStatic
    fun capturedAt(): Long {
        val rp = panelPrefs()
        if (rp != null) {
            try {
                val t = rp.getLong(K_CAPTURED_AT, 0L)
                if (t > 0L) return t
            } catch (ignored: Throwable) { }
        }
        try {
            val p = readState()
            if (p != null) return parseLong(p.getProperty(K_CAPTURED_AT), 0L)
        } catch (ignored: Throwable) { }
        return 0L
    }

    /** hook 侧：面板是否请求了「重新抓取」且还没处理过。 */
    @JvmStatic
    fun hasPendingRecapture(): Boolean {
        return try {
            val at = remoteRecaptureAt()
            if (at <= 0L) return false
            val p = readState()
            val handled = if (p == null) 0L else parseLong(p.getProperty(K_RECAPTURE_HANDLED), 0L)
            at > handled
        } catch (t: Throwable) {
            false
        }
    }

    /** hook 侧：面板请求的时间戳（0 = 没请求）。 */
    @JvmStatic
    fun pendingRecaptureAt(): Long {
        return try {
            remoteRecaptureAt()
        } catch (t: Throwable) {
            0L
        }
    }

    /**
     * 面板「导出基线」：把宿主 `files/baseline/` 打包到
     * `/sdcard/Download/MapAdKiller-baseline-<ver>-<ts>.zip`。
     *
     * <p>模块 App 与宿主不同 UID，正常读不到宿主 data 目录 ⇒ 走 `su`（LSPosed 用户必有 root）
     * 先把目录拷到 Download 临时目录，再在 App 进程内打 zip（Android 没有 zip 命令，tar 不能出 .zip）。
     * 内置版（同 UID）能直接读时走进程内直接打包。返回产物路径，失败返回 null。
     */
    @JvmStatic
    fun exportBaseline(appCtx: Context?): String? {
        val version = moduleVersionName()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
        val name = "MapAdKiller-baseline-" + version + "-" + stamp + ".zip"
        var download = File("/sdcard/Download")
        try {
            if (!download.isDirectory) download = File("/storage/emulated/0/Download")
            if (!download.isDirectory && !download.mkdirs()) return null
            val target = File(download, name)
            // 1) 同 UID（内置版）能直接读宿主目录 ⇒ 进程内打 zip
            try {
                val base = File(File("/data/data/com.autonavi.minimap/files"), DIR)
                if (base.isDirectory && base.canRead() && zipDir(base, target, DIR)) {
                    SafeLog.i(TAG, "event=baseline_export_done mode=direct path=" + target.absolutePath)
                    return target.absolutePath
                }
            } catch (ignored: Throwable) { }
            // 2) root 兜底：先拷到 Download 临时目录，再打 zip
            val tmp = File(download, ".mak-baseline-tmp-" + stamp)
            deleteRecursively(tmp)
            val cmd = "rm -rf '" + tmp.absolutePath + "' && cp -r /data/data/com.autonavi.minimap/files/" +
                    DIR + " '" + tmp.absolutePath + "'"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            if (p.waitFor() != 0 || !tmp.isDirectory) return null
            val ok = zipDir(tmp, target, DIR)
            deleteRecursively(tmp)
            if (ok) {
                SafeLog.i(TAG, "event=baseline_export_done mode=root path=" + target.absolutePath)
                return target.absolutePath
            }
            return null
        } catch (t: Throwable) {
            SafeLog.w(TAG, "event=baseline_export_failed", t)
            return null
        }
    }

    private fun zipDir(srcDir: File, zipFile: File, rootName: String): Boolean {
        var zos: java.util.zip.ZipOutputStream? = null
        try {
            zos = java.util.zip.ZipOutputStream(
                    java.io.BufferedOutputStream(FileOutputStream(zipFile)))
            val ok = zipInto(zos, srcDir, rootName)
            zos.finish()
            return ok
        } catch (t: Throwable) {
            return false
        } finally {
            closeQuietly(zos)
        }
    }

    private fun zipInto(zos: java.util.zip.ZipOutputStream, dir: File, entryPrefix: String): Boolean {
        try {
            val list = dir.listFiles() ?: return true
            val buf = ByteArray(8192)
            for (f in list) {
                if (f == null) continue
                val entry = entryPrefix + "/" + f.name
                if (f.isDirectory) {
                    if (!zipInto(zos, f, entry)) return false
                } else if (f.isFile) {
                    var ins: InputStream? = null
                    try {
                        zos.putNextEntry(java.util.zip.ZipEntry(entry))
                        ins = FileInputStream(f)
                        var r = ins.read(buf)
                        while (r > 0) { zos.write(buf, 0, r); r = ins.read(buf) }
                        zos.closeEntry()
                    } finally {
                        closeQuietly(ins)
                    }
                }
            }
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    // ══════════════════════════════════════════════════════════════════ 内部工具

    private val TAG: String = MainHook.TAG

    /** 最近一次「请求重新抓取」失败的原因，面板用来显示（不参与控制逻辑）。 */
    @Volatile
    private var sLastRequestError: String? = null

    /** RemotePreferences（hook 侧只读；面板侧可写）。拿不到返回 null。 */
    private fun remotePrefs(): SharedPreferences? {
        return try {
            val m = H.module ?: return null
            m.getRemotePreferences(PREF_GROUP)
        } catch (t: Throwable) {
            null
        }
    }

    /** 面板请求的「重新抓取」时间戳；0 = 没请求过。 */
    private fun remoteRecaptureAt(): Long {
        var at = 0L
        try {
            val p = remotePrefs()
            if (p != null) at = p.getLong(K_RECAPTURE_AT, 0L)
        } catch (ignored: Throwable) { }
        // root 兜底通道：面板 App 写不进 RemotePreferences 时会在宿主 files/baseline 下留
        // 一个 recapture.request（内容是 unix 秒）。
        try {
            val dir = stateDir()
            val req = if (dir == null) null else File(dir, "recapture.request")
            if (req != null && req.isFile) {
                val txt = readSmallText(req)
                val sec = parseLong(if (txt == null) null else txt.trim(), 0L)
                if (sec > 0L) at = Math.max(at, sec * 1000L)
            }
        } catch (ignored: Throwable) { }
        return at
    }

    /** 读一个很小的文本文件（最多 256 字节），失败返回 null。 */
    private fun readSmallText(f: File): String? {
        var ins: InputStream? = null
        try {
            ins = FileInputStream(f)
            val buf = ByteArray(256)
            val n = ins.read(buf)
            if (n <= 0) return null
            return String(buf, 0, n, charset("UTF-8"))
        } catch (t: Throwable) {
            return null
        } finally {
            closeQuietly(ins)
        }
    }

    /** 删掉 root 兜底请求文件（处理完就撤，避免每次启动都重抓）。 */
    private fun clearRecaptureRequest() {
        try {
            val dir = stateDir()
            val req = if (dir == null) null else File(dir, "recapture.request")
            if (req != null && req.isFile) req.delete()
        } catch (ignored: Throwable) { }
    }

    /**
     * 状态目录：`/data/data/<当前进程包>/files/baseline`。
     *
     * <p>onPackageReady 极早期 `ActivityThread.currentPackageName()` 可能拿不到，
     * 所以解析失败时回退到宿主包名（本闸门只对高德生效）。两条路径都试一遍取存在的那个。
     */
    private fun stateDir(): File? {
        val a = stateDirFor(processPackage())
        if (a != null && a.exists()) return a
        val b = stateDirFor(MainHook.PKG_AMAP)
        return a ?: b
    }

    private fun stateDirFor(pkg: String?): File? {
        if (pkg == null || pkg.isEmpty()) return null
        return File("/data/data/" + pkg + "/files/" + DIR)
    }

    private fun stateFile(): File? {
        val dir = stateDir() ?: return null
        return File(dir, STATE_FILE)
    }

    private fun attemptFile(code: Int): File? {
        val dir = stateDir() ?: return null
        return File(dir, ".attempt_v" + code)
    }

    /** 采集模式标记文件：`files/baseline/capture.mode`（面板写，这里只读）。 */
    private fun captureModeFile(): File? {
        val dir = stateDir() ?: return null
        return File(dir, F_CAPTURE_MODE)
    }

    /** 基线已就绪标记：`files/baseline/captured.marker`（这里写，面板只读）。 */
    private fun captureDoneFile(): File? {
        val dir = stateDir() ?: return null
        return File(dir, F_CAPTURE_DONE)
    }

    /** 同步写一个「已 passthrough 过一次」的小标记（失败也无所谓，内存标志仍在）。 */
    private fun markAttempt(code: Int) {
        try {
            val dir = stateDir()
            val f = attemptFile(code)
            if (dir == null || f == null) return
            if (!dir.isDirectory && !dir.mkdirs()) return
            val out = FileOutputStream(f)
            out.write(System.currentTimeMillis().toString().toByteArray(charset("UTF-8")))
            out.flush()
            closeQuietly(out)
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "event=baseline_attempt_mark_failed err=" + t)
        }
    }

    /** 总开关（RemotePreferences，面板可写）：false ⇒ 完全不抓基线、hook 正常生效。 */
    private fun baselineEnabled(): Boolean {
        return try {
            val p = remotePrefs()
            p == null || p.getBoolean("baseline_enabled", true)
        } catch (t: Throwable) {
            true
        }
    }

    private fun readState(): Properties? {
        val f = stateFile() ?: return null
        val p = Properties()
        if (!f.isFile) return p
        var ins: InputStream? = null
        try {
            ins = FileInputStream(f)
            p.load(ins)
            return p
        } catch (t: Throwable) {
            return null
        } finally {
            closeQuietly(ins)
        }
    }

    private fun saveState(p: Properties) {
        val f = stateFile() ?: return
        val dir = f.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) return
        val tmp = File(f.absolutePath + ".tmp")
        var out: OutputStream? = null
        try {
            out = FileOutputStream(tmp)
            p.store(out, "MapAdKiller baseline state")
            out.flush()
            closeQuietly(out)
            out = null
            if (!tmp.renameTo(f)) {
                // rename 失败就退回直接写
                out = FileOutputStream(f)
                p.store(out, "MapAdKiller baseline state")
                out.flush()
            }
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "event=baseline_state_write_failed path=" + f.absolutePath
                    + " err=" + t)
        } finally {
            closeQuietly(out)
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun moduleVersionName(): String {
        try {
            val v = Class.forName("io.github.ldxm666.mapclean.Version")
            val n = v.getField("NAME").get(null)
            if (n is String && n.isNotEmpty()) return n
        } catch (ignored: Throwable) { }
        return "unknown"
    }

    private fun moduleVersionCode(): Int {
        try {
            val v = Class.forName("io.github.ldxm666.mapclean.Version")
            return v.getField("CODE").getInt(null)
        } catch (ignored: Throwable) { }
        return 0
    }

    @JvmStatic
    fun processName(): String? {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val v = at.getMethod("currentProcessName").invoke(null)
            if (v is String) return v
        } catch (ignored: Throwable) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) return android.app.Application.getProcessName()
        } catch (ignored: Throwable) { }
        return null
    }

    private fun processPackage(): String? {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val v = at.getMethod("currentPackageName").invoke(null)
            if (v is String) return v
        } catch (ignored: Throwable) { }
        val name = processName()
        if (name != null) {
            val i = name.indexOf(':')
            return if (i > 0) name.substring(0, i) else name
        }
        return null
    }

    /** 本进程启动时刻（wall clock，毫秒）。 */
    private fun processStartWallTime(): Long {
        return try {
            System.currentTimeMillis() -
                    (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
        } catch (t: Throwable) {
            0L
        }
    }

    private fun isMainProcess(): Boolean {
        val name = processName()
        return name == null || name.indexOf(':') < 0
    }

    private fun waitHostContext(timeoutMs: Long): Context? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                val c = Config.hostCtx()
                if (c != null) return c
            } catch (ignored: Throwable) { }
            sleep(500L)
        }
        return try {
            Config.hostCtx()
        } catch (t: Throwable) {
            null
        }
    }

    private fun parseLong(s: String?, def: Long): Long {
        if (s == null) return def
        return try {
            java.lang.Long.parseLong(s.trim())
        } catch (t: Throwable) {
            def
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun deleteRecursively(f: File?) {
        try {
            if (f == null || !f.exists()) return
            val list = f.listFiles()
            if (list != null) for (c in list) deleteRecursively(c)
            f.delete()
        } catch (ignored: Throwable) { }
    }

    private fun writeText(f: File, text: String) {
        var out: OutputStream? = null
        try {
            out = FileOutputStream(f)
            out.write(text.toByteArray(charset("UTF-8")))
            out.flush()
        } catch (ignored: Throwable) {
        } finally {
            closeQuietly(out)
        }
    }

    private fun closeQuietly(o: Any?) {
        try {
            if (o is InputStream) o.close()
            else if (o is OutputStream) o.close()
        } catch (ignored: Throwable) { }
    }

    /** 调试用：列出当前基线目录。 */
    @JvmStatic
    fun listBaselineDirs(ctx: Context): List<String> {
        val out = ArrayList<String>()
        try {
            val base = File(ctx.filesDir, DIR)
            val list = base.listFiles()
            if (list != null) for (f in list) if (f.isDirectory) out.add(f.name)
        } catch (ignored: Throwable) { }
        return out
    }
}
