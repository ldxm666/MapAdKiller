package io.github.ldxm666.mapclean

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Properties

/**
 * 首次使用的「采集模式」标记（v2.2.6）。
 *
 * <p><b>为什么需要</b>：高德的首页卡片、金刚位、工具宫格全是**服务端下发**的。内置版装完后
 * 如果模块的 hook 一上来就生效，宿主手里还没有这批数据，界面上就是一排空壳
 * （真机反馈：工具宫格图标整片不显示）。所以第一次必须先让高德跑一遍纯官方，
 * 由 `mapadkiller.Baseline` 把官方 `shared_prefs` 原样抓下来，再启用模块。
 *
 * <p>标记就是宿主 `filesDir/baseline/` 下的两个空文件，面板与 hook 两侧都只认文件，
 * 不经过 SharedPreferences（宿主 prefs 只读，绝不写）：
 * <ul>
 *   <li>[F_MODE] —— 采集模式开关（面板写，Baseline 读）；</li>
 *   <li>[F_DONE] —— 基线已抓到（Baseline 写，面板读）。</li>
 * </ul>
 *
 * <p>内置版面板与宿主同进程同 UID，直接读写即可；模块版（LSPosed）没有面板，走不到这里。
 */
class CaptureGate private constructor() {

    companion object {

        private const val TAG = "MapCleanCapture"

        /** 与 `mapadkiller.Baseline.DIR` 保持一致。 */
        const val DIR = "baseline"
        const val F_MODE = "capture.mode"
        const val F_DONE = "captured.marker"

        private fun dir(c: Context): File? {
            try {
                val d = File(c.filesDir, DIR)
                if (!d.isDirectory && !d.mkdirs()) return null
                return d
            } catch (t: Throwable) {
                return null
            }
        }

        /** 采集模式是否开着。 */
        @JvmStatic
        fun captureMode(c: Context): Boolean {
            try {
                val d = dir(c)
                return d != null && File(d, F_MODE).isFile
            } catch (t: Throwable) {
                return false
            }
        }

        /** 官方基线是否已经抓到。 */
        @JvmStatic
        fun captured(c: Context): Boolean {
            try {
                val d = dir(c)
                return d != null && File(d, F_DONE).isFile
            } catch (t: Throwable) {
                return false
            }
        }

        /** 抓取是否正在进行（Baseline 写的 files/baseline/capture.running 标记）。 */
        @JvmStatic
        fun capturing(c: Context): Boolean {
            try {
                val f = file(c, "capture.running")
                return f != null && f.isFile
            } catch (t: Throwable) {
                return false
            }
        }

        /** 上次抓到的配置文件数（state.properties 里的 baseline_files），读不到返回 0。 */
        @JvmStatic
        fun capturedFiles(c: Context): Int {
            val d = dir(c) ?: return 0
            var input: InputStream? = null
            try {
                input = java.io.FileInputStream(File(d, "state.properties"))
                val p = Properties()
                p.load(input)
                return Integer.parseInt(p.getProperty("baseline_files", "0").trim())
            } catch (t: Throwable) {
                return 0
            } finally {
                try {
                    input?.close()
                } catch (ignored: Throwable) {
                }
            }
        }

        /** 开/关采集模式。true=写入标记，false=删除标记。 */
        @JvmStatic
        fun setCaptureMode(c: Context, on: Boolean): Boolean {
            try {
                val d = dir(c) ?: return false
                val f = File(d, F_MODE)
                if (!on) return !f.exists() || f.delete()
                val out = FileOutputStream(f)
                try {
                    out.write(System.currentTimeMillis().toString().toByteArray(charset("UTF-8")))
                    out.flush()
                } finally {
                    out.close()
                }
                // ★ 「重新抓取」必须先把上一轮的「已抓到 / 请重启」标记清掉：
                // 否则面板读到 mode&&done 就以为已经抓完，直接弹「启用模块功能？」并重启，
                // 真机表现就是「数据都没抓到就被重启了」（2026-10-27 复查确认）。
                var stale = File(d, F_DONE)
                if (stale.isFile) stale.delete()
                stale = File(d, F_RESTART)
                if (stale.isFile) stale.delete()
                return true
            } catch (t: Throwable) {
                return false
            }
        }

        /** 抓到基线后由 Baseline 落的「该重启一次」标记。 */
        const val F_RESTART = "restart.pending"

        @Volatile
        private var promptShown = false

        @Volatile
        private var toastShown = false

        @Volatile
        private var ticks = 0

        private fun file(c: Context, name: String): File? {
            val d = dir(c)
            return if (d == null) null else File(d, name)
        }

        /** 是否需要提示「抓到了，重启一下」。 */
        @JvmStatic
        fun restartPending(c: Context): Boolean {
            try {
                val f = file(c, F_RESTART)
                return f != null && f.isFile
            } catch (t: Throwable) {
                return false
            }
        }

        @JvmStatic
        fun clearRestartPending(c: Context) {
            try {
                val f = file(c, F_RESTART)
                if (f != null && f.isFile) f.delete()
            } catch (ignored: Throwable) {
            }
        }

        /**
         * 宿主每次 resume 时调一次：抓到基线就弹窗提示，并自动重启一次让 hook 生效。
         *
         * <p>抓取是在 `Baseline` 的后台线程里做的，通常比首个 Activity 的 resume 晚几秒，
         * 所以没看到标记时每 2 秒复查一次，最多 60 秒，之后不再打扰。
         */
        @JvmStatic
        fun tick(a: android.app.Activity?) {
            if (a == null || promptShown || a.isFinishing) return
            // 采集模式下先给一次明确提示：用户刚装完打开高德，界面上看不到任何「模块」痕迹，
            // 不给提示就不知道「正在抓官方数据、可以正常用」。
            if (!toastShown) {
                try {
                    if (captureMode(a) && !captured(a)) {
                        toastShown = true
                        android.widget.Toast.makeText(
                            a.applicationContext,
                            "正在抓取官方数据，请正常用一次地图（首页 / 搜索 / 我的）",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (ignored: Throwable) {
                }
            }
            if (!restartPending(a)) {
                if (ticks++ > 30) return
                try {
                    a.window.decorView.postDelayed(object : Runnable {
                        override fun run() {
                            tick(a)
                        }
                    }, 2000L)
                } catch (ignored: Throwable) {
                }
                return
            }
            promptShown = true
            // 只提示、不自动杀进程：抓取刚写完配置时用户可能还在浏览，自动重启会被当成「没抓到就重启」。
            // 提示走高德同款的底部小气泡（Toast），不弹大白框。
            val files = capturedFiles(a)
            toast(a, "✓ 官方数据已抓取完成（" + files + " 项），点悬浮球 → 重启并启用模块")
            // 提示一次就清标记。以前不清：用户重启后标记还在，于是每次打开都弹这条
            // （用户反馈「抓好了还每次打开都弹」就是这个）。
            clearRestartPending(a)
            Log.i(TAG, "event=capture_done_toast files=" + files + " cleared=1")
        }

        @Volatile
        private var recaptureChecked = false

        /**
         * 模块版（LSPosed）专用：设置页点了「抓取云数据」之后，宿主进程下次 resume 时自己重启一次
         * 进入采集模式。
         *
         * <p>为什么必须有这一步：`Baseline.maybeCapture()` 只在**进程启动**时判定一次，
         * 而高德被拉起来时进程多半还活着，光把界面切到前台根本不会触发抓取 —— 用户看到的就是
         * 「点了抓取，什么都没发生」。这里读面板写下的请求（RemotePreferences 或 root 兜底文件），
         * 满足条件就写 `capture.mode` 再重启，下一次启动就是干净的一次采集。
         *
         * <p>安全阀：每个进程只判定一次；只认 10 分钟内的请求（避免很早以前的遗留请求在用户
         * 开车导航时突然把高德重启）。
         */
        @JvmStatic
        fun applyRecaptureRequest(activity: android.app.Activity?) {
            if (recaptureChecked || activity == null) return
            recaptureChecked = true
            try {
                val at = io.github.ldxm666.mapadkiller.Baseline.pendingRecaptureAt()
                if (at <= 0L) return
                if (System.currentTimeMillis() - at > 10L * 60L * 1000L) {
                    Log.i(TAG, "event=recapture_request_stale at=" + at)
                    return
                }
                if (!io.github.ldxm666.mapadkiller.Baseline.hasPendingRecapture()) return
                if (!setCaptureMode(activity, true)) return
                Log.i(TAG, "event=recapture_request_applied at=" + at)
                // 这里**不**自杀重启：HyperOS/MIUI 的 Aurogon（应用冻结）对「短时间内被连续杀掉
                // 两次」的应用会拉黑一段时间，闹钟拉起会被静默丢掉 —— 真机上表现就是抓完数据
                // 高德再也没回来。改成让用户自己关掉再打开：一次流程只在高德进程内自杀一次
                // （抓取完成后的 Baseline.maybeAutoRestart），实测闹钟能把高德拉回来。
                toast(activity, "已请求抓取 · 请关掉高德再打开一次，即开始抓取")
                Log.i(TAG, "event=recapture_user_restart_hint at=" + at)
            } catch (ignored: Throwable) {
            }
        }

        /** 底部小气泡提示（浅色系统 Toast，和高德自己的「再按一次退出」同款）。 */
        @JvmStatic
        fun toast(c: Context, text: String) {
            try {
                android.widget.Toast.makeText(c.applicationContext, text, android.widget.Toast.LENGTH_LONG)
                    .show()
            } catch (ignored: Throwable) {
            }
        }

        /**
         * 重启宿主：先排一个 1.5 秒后的系统闹钟拉起启动页，再杀掉自己。
         * 没有 root 不能 `am force-stop`，进程死了闹钟还在系统里，起来就是干净的一次启动。
         * 采集模式标记由调用方自己清除（该标记只在抓取期间有意义）。
         */
        @JvmStatic
        fun restartHost(c: Context) {
            try {
                val app: Context = c.applicationContext ?: c
                // 注意：这里不动 capture.mode —— 「开始抓取」那条路要带着标记重启，
                // 需要退出采集模式的调用方自己先 setCaptureMode(false)。
                clearRestartPending(app)
                val i = app.packageManager.getLaunchIntentForPackage(app.packageName)
                if (i != null) {
                    i.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    var flags = android.app.PendingIntent.FLAG_CANCEL_CURRENT
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        flags = flags or android.app.PendingIntent.FLAG_IMMUTABLE
                    }
                    val pi = android.app.PendingIntent.getActivity(app, 0x4D43, i, flags)
                    val am = app.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                    // 闹钟必须排在自杀**之后**：kill 在 +800ms，这里排 +2500ms。
                    // 之前是 +1500ms，闹钟把启动页拉起来的却是还活着的旧进程，接着 kill 一到
                    // 就把这个刚起来的窗口一起带走了 —— 真机上表现就是「高德直接没了」。
                    if (am != null) {
                        val at = System.currentTimeMillis() + 2500L
                        // setAlarmClock 在真机上抛 SecurityException（高德没有 SCHEDULE_EXACT_ALARM
                        // 权限），所以只用 setExactAndAllowWhileIdle，失败再退回 inexact。
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 23) {
                                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                            } else {
                                am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                            }
                            Log.i(TAG, "event=restart_alarm kind=exact at=" + at)
                        } catch (t: Throwable) {
                            am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                            Log.i(TAG, "event=restart_alarm kind=inexact at=" + at)
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
            try {
                val app2 = c.applicationContext ?: c
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable {
                    private var waited = 0
                    override fun run() {
                        // 抓取线程还没落完盘就不杀进程（否则会抓到一半被杀，重启后又是半套基线）。
                        try {
                            if (capturing(app2) && waited < 20) {
                                waited++
                                android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(this, 1000L)
                                return
                            }
                        } catch (ignored: Throwable) {
                        }
                        try {
                            android.os.Process.killProcess(android.os.Process.myPid())
                        } catch (ignored: Throwable) {
                        }
                    }
                }, 800L)
            } catch (ignored: Throwable) {
            }
        }
    }
}
