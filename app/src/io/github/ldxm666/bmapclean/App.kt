package io.github.ldxm666.bmapclean

import android.app.Application

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 进程：绑定 LSPosed 服务，用于读写 RemotePreferences。
 *
 * Hook 进程不走本类（那边是 {@link Cfg#prefs()} → XposedModule#getRemotePreferences）。
 * 两个进程读的是**同一个 group**，所以设置页改完、强停百度地图重开即生效。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        self = this
        try {
            XposedServiceHelper.registerListener(this)
            android.util.Log.i(TAG, "registerListener ok")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "registerListener failed", t)
        }
    }

    override fun onServiceBind(s: XposedService) {
        service = s
        android.util.Log.i(TAG, "onServiceBind ok")
        migrateMapLabels(s)
        ensureToken()
        notifyReport()
    }

    override fun onServiceDied(s: XposedService) {
        android.util.Log.w(TAG, "onServiceDied")
        if (service === s) service = null
        notifyReport()
    }

    // ── v2.0.0 合并版：由 io.github.ldxm666.mapclean.App（真正的 Application）驱动 ──
    //    本类不再作为 manifest 的 application，只当静态持有者用（服务对象两边共用同一个）。

    companion object {

        @Volatile
        private var service: XposedService? = null

        @JvmStatic
        fun svc(): XposedService? {
            return service
        }

        @JvmStatic
        fun connected(): Boolean {
            return service != null
        }

        /** 设置页写开关。必须 commit()：apply() 异步落盘，用户切完就退出会丢。 */
        @JvmStatic
        fun write(key: String, value: Boolean): Boolean {
            // ★ 免 root 内置版：服务通道写的是 LSPatch 的 lspatch-xposed-remote.db，
            //   真机实测会 SQLiteCantOpenDatabaseException —— commit() 返回 true 但读不回来，
            //   这就是用户反馈的「开关拨了、重开又变回去」。宿主自己的 SharedPreferences
            //   （bmapclean_config.xml）永远可写，且 hook 侧读的就是同一个文件，所以直接走本地。
            if (Cfg.embedded()) return Cfg.write(key, value)
            val s = service
            if (s != null) {
                try {
                    if (s.getRemotePreferences(Cfg.GROUP).edit().putBoolean(key, value).commit()) {
                        Cfg.invalidate()
                        return true
                    }
                } catch (ignored: Throwable) { }
            }
            // 服务未连上（模块版首启 / 内置版降级）：本地后端兜底，不再直接返回 false。
            return Cfg.write(key, value)
        }

        /** 设置页读开关（服务未连上时按「可见」返回，与 hook 侧失效安全一致）。 */
        @JvmStatic
        fun read(key: String): Boolean {
            return read(key, true)
        }

        /** 缺省值由调用方指定（默认「关」的键，如 mine_apply） */
        @JvmStatic
        fun read(key: String, def: Boolean): Boolean {
            if (Cfg.embedded()) return Cfg.visible(key, def)
            val s = service
            if (s != null) {
                try {
                    return s.getRemotePreferences(Cfg.GROUP).getBoolean(key, def)
                } catch (ignored: Throwable) { }
            }
            return Cfg.visible(key, def)
        }

        /** 诊断摘要（hook 侧通过广播回报，落在本地 SharedPreferences） */
        @JvmStatic
        fun diag(): String? {
            val r = lastReport() ?: return null
            return r[0] + " · " + r[1] + "  （" + r[2] + "）"
        }

        /** @return {stage 中文描述, detail, 时间文本} 或 null */
        @JvmStatic
        fun lastReport(): Array<String>? {
            try {
                val sp = app()!!.getSharedPreferences(ReportReceiver.P, android.content.Context.MODE_PRIVATE)
                val stage = sp.getString("stage", null)
                if (stage == null) return null
                val detail = sp.getString("detail", "")
                val ts = sp.getLong("ts", 0L)
                val when0 = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(ts))
                val label = if ("hook" == stage) "钩子注入" else "首页规则执行"
                return arrayOf(label, detail ?: "", when0)
            } catch (t: Throwable) {
                return null
            }
        }

        /** hook 是否曾经回报过（= 模块确实在目标进程里跑起来了） */
        @JvmStatic
        fun hookReportAt(): Long {
            try {
                return app()!!.getSharedPreferences(ReportReceiver.P, android.content.Context.MODE_PRIVATE)
                        .getLong("hook_ts", 0L)
            } catch (t: Throwable) {
                return 0L
            }
        }

        /**
         * 最近一次收到任何回报的时间（钩子注入 **或** 首页规则执行）。
         *
         * 判「模块是否生效」必须用这个而不是 hookReportAt()：钩子注入那条广播发得极早
         * （进程刚起、Application 还没 onCreate 完），此时设置页进程往往不在运行，
         * 系统可能直接把广播丢掉；而扫描回报一定在首页起来之后才发，必然能送达。
         * 只看 hook_ts 会误报"尚未收到回报"（真机实测）。
         */
        @JvmStatic
        fun lastReportAt(): Long {
            try {
                val sp = app()!!.getSharedPreferences(ReportReceiver.P, android.content.Context.MODE_PRIVATE)
                val a = sp.getLong("hook_ts", 0L)
                val b = sp.getLong("scan_ts", 0L)
                return if (a > b) a else b
            } catch (t: Throwable) {
                return 0L
            }
        }

        @JvmStatic
        fun reportCount(): Int {
            try {
                return app()!!.getSharedPreferences(ReportReceiver.P, android.content.Context.MODE_PRIVATE)
                        .getInt("count", 0)
            } catch (t: Throwable) {
                return 0
            }
        }

        // ── UI 刷新通知 ────────────────────────────────────────────────────
        @Volatile
        private var reportListener: Runnable? = null

        /** 主线程 Handler：广播是在 binder 线程收到的，绝不能在那里碰视图树 */
        private val MAIN = android.os.Handler(android.os.Looper.getMainLooper())

        @JvmStatic
        fun setReportListener(r: Runnable?) { reportListener = r }

        /**
         * 通知设置页刷新。
         *
         * ⚠ 必须 post 到主线程：本方法由 ReportReceiver.onReceive（binder 线程）调用，
         * 若直接 run()，就会有一个非 UI 线程去 removeAllViews/addView，
         * 与 UI 线程的 render() 并发改同一棵树 —— 实测表现就是**设置页整片空白**，
         * 而且没有 FATAL、主线程还停在 epoll 上，极其难查。
         */
        @JvmStatic
        fun notifyReport() {
            val r = reportListener
            if (r == null) return
            MAIN.post(object : Runnable {
                override fun run() {
                    try { r.run() } catch (t: Throwable) {
                        android.util.Log.w(TAG, "report listener failed", t)
                    }
                }
            })
        }

        // ── token（防伪广播） ───────────────────────────────────────────────
        const val K_TOKEN = "app_token"

        @JvmStatic
        fun token(): String {
            // 内置版 remote 读不到 ⇒ 回落到宿主本地（hook 侧就是从那里读的，两边必须同一个源）
            val local = Cfg.token()
            if (local.length > 0) return local
            val s = service
            if (s == null) return ""
            try {
                return s.getRemotePreferences(Cfg.GROUP).getString(K_TOKEN, "") ?: ""
            } catch (t: Throwable) {
                return ""
            }
        }

        /** 首次运行时生成 token 并写进配置（hook 侧只读得到它）。 */
        @JvmStatic
        fun ensureToken() {
            // 内置版写 remote 是坏库；token 必须落在 hook 侧读得到的地方（宿主本地 prefs）。
            try {
                val lp = Cfg.prefs()
                if (lp != null) {
                    val cur = lp.getString(K_TOKEN, "")
                    if (cur == null || cur.length == 0) {
                        Cfg.writeString(K_TOKEN, java.util.UUID.randomUUID().toString())
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "ensureToken local failed: " + t)
            }
            val s = service
            if (s == null) return
            try {
                // 内置版：remote 是只读代理，写进去也读不回来。
                // 迁移标记必须落在 hook 侧真正读的那个后端上。
                val p = if (Cfg.embedded())
                        Cfg.prefs() else s.getRemotePreferences(Cfg.GROUP)
                if (p == null) return
                val cur = p.getString(K_TOKEN, "")
                if (cur == null || cur.length == 0) {
                    p.edit().putString(K_TOKEN, java.util.UUID.randomUUID().toString()).commit()
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "ensureToken failed: " + t)
            }
        }

        @JvmStatic
        fun resetAll(): Boolean {
            var ok = false
            val tk = token()
            // 本地后端先清（内置版只有这一条通道）
            try {
                val lp = Cfg.prefs()
                if (lp != null) {
                    val e = lp.edit().clear()
                    if (tk != null && tk.length > 0) e.putString(K_TOKEN, tk)   // token 保留
                    ok = e.commit()
                    Cfg.invalidate()
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "resetAll local failed: " + t)
            }
            val s = service
            if (s == null) return ok
            try {
                val e = s.getRemotePreferences(Cfg.GROUP).edit().clear()
                if (tk != null && tk.length > 0) e.putString(K_TOKEN, tk)       // token 保留
                return e.commit() || ok
            } catch (t: Throwable) {
                return ok
            }
        }

        private const val TAG = "BMapCleanApp"

        @Volatile
        private var self: Application? = null

        @JvmStatic
        fun app(): Application? { return self }

        @JvmStatic
        fun attach(a: Application?) { self = a }

        @JvmStatic
        fun onBind(s: XposedService?) {
            service = s
            // 这条日志同时在给"设置页百度那一页能不能读写"提供判据：
            // 以前合并没有把本类接上，service 恒为 null，百度组每个开关写完都会被回滚并弹
            // 「LSPosed 服务未连接」；日志里也永远看不到这一行。
            android.util.Log.i(TAG, "onServiceBind ok (driven by mapclean.App)")
            if (s != null) migrateMapLabels(s)
            ensureToken()
            notifyReport()
        }

        @JvmStatic
        fun onDied(s: XposedService?) {
            if (service === s) service = null
            notifyReport()
        }

        /** Replace the retired category UI once; subsequent imports keep their own value. */
        private fun migrateMapLabels(s: XposedService) {
            try {
                val p = s.getRemotePreferences(Cfg.GROUP)
                if (p.getInt("_map_label_schema", 0) >= 212) return
                val edit = p.edit()
                for (key in p.all.keys) if (key.startsWith("map_label_")) edit.remove(key)
                edit.putBoolean(Spec.K_MAP_POI, false).putInt("_map_label_schema", 212).commit()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "map label migration failed: " + t.javaClass.simpleName)
            }
        }
    }
}