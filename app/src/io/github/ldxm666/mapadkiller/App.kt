package io.github.ldxm666.mapadkiller

import android.app.Application
import android.content.Context

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 进程：绑定 LSPosed 服务（用于写 RemotePreferences）。
 * Hook 进程不经过本类（Config 走 XposedModule#getRemotePreferences）。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        applyLauncherState(this)
        try {
            XposedServiceHelper.registerListener(this)
        } catch (ignored: Throwable) {}
    }

    override fun onServiceBind(s: XposedService) {
        service = s
        flushPending()          // 补写服务没连上时收到的学习结果
        try { LearnedProvider.flushToRemote(applicationContext) } catch (ignored: Throwable) {}
    }

    override fun onServiceDied(s: XposedService) {
        if (service === s) service = null
    }

    companion object {

        @Volatile
        private var service: XposedService? = null

        // ── v2.0.0 合并版：由 io.github.ldxm666.mapclean.App（真正的 Application）驱动 ──
        @Volatile
        private var CTX: Context? = null

        @JvmStatic
        fun svc(): XposedService? {
            return service
        }

        /** App 侧写入；返回 false 表示服务未连接或写入未落盘（UI 提示稍后重试） */
        @JvmStatic
        fun writeBoolean(key: String, value: Boolean): Boolean {
            val s = service ?: return false
            try {
                // 必须用 commit()：apply() 是异步落盘，用户切完开关立刻退出设置页/清后台时
                // 这次写入会被丢掉，表现就是"设完再打开又全变回开启"。
                return s.getRemotePreferences(Config.PREF_GROUP)
                        .edit().putBoolean(key, value).commit()
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * App 侧写 int（设置页的滑块走这条）。
         *
         * <p>必须走服务代理的 remote prefs：模块 App 和地图进程**不是一个进程**，
         * 直接写本地 SharedPreferences 只有 App 自己看得见，hook 侧永远读不到 ——
         * 表现就是「滑块拖了没反应、巡航不触发」。
         */
        @JvmStatic
        fun writeInt(key: String, value: Int): Boolean {
            val s = service ?: return false
            try {
                return s.getRemotePreferences(Config.PREF_GROUP).edit().putInt(key, value).commit()
            } catch (t: Throwable) {
                return false
            }
        }

        /** App 侧读 int：服务没连上就返回 fallback（内置版由面板自己读写，不走这里）。 */
        @JvmStatic
        fun readInt(key: String, fallback: Int): Int {
            val s = service ?: return fallback
            try {
                return s.getRemotePreferences(Config.PREF_GROUP).getInt(key, fallback)
            } catch (t: Throwable) {
                return fallback
            }
        }

        /** 恢复默认：清空全部配置键（全部显示） */
        @JvmStatic
        fun clearAll(): Boolean {
            val s = service ?: return false
            try {
                return s.getRemotePreferences(Config.PREF_GROUP).edit().clear().commit()
            } catch (t: Throwable) {
                return false
            }
        }

        /** 写入字符串集合（自动学习到的广告 SDK 清单走这条路：App 侧写，hook 侧只读） */
        @JvmStatic
        fun writeStringSet(key: String, value: Set<String>): Boolean {
            val s = service ?: return false
            try {
                return s.getRemotePreferences(Config.PREF_GROUP)
                        .edit().putStringSet(key, java.util.LinkedHashSet(value)).commit()
            } catch (t: Throwable) {
                return false
            }
        }

        // ---- 服务未绑定时收到上报，先挂这里，绑定后补写 ----
        private val pendingLearned: MutableSet<String> = java.util.LinkedHashSet()

        @JvmStatic
        @Synchronized
        fun addPendingLearned(roots: Collection<String>) {
            pendingLearned.addAll(roots)
        }

        @Synchronized
        private fun flushPending() {
            if (pendingLearned.isEmpty()) return
            try {
                val merged: MutableSet<String> = java.util.LinkedHashSet()
                val s = service
                if (s != null) {
                    val old = s.getRemotePreferences(Config.PREF_GROUP)
                            .getStringSet("sdk_learned", null)
                    if (old != null) merged.addAll(old)
                }
                merged.addAll(pendingLearned)
                if (s != null && s.getRemotePreferences(Config.PREF_GROUP).edit()
                                .putStringSet("sdk_learned", merged).commit()) {
                    pendingLearned.clear()
                }
            } catch (ignored: Throwable) {}
        }

        // ══════════════════════════════════════════════════════════ 桌面图标隐藏

        /**
         * 「隐藏桌面图标」开关。
         *
         * 实现方式：MainActivity 自己**不带** MAIN/LAUNCHER（因此不进桌面），
         * 桌面入口挂在 <activity-alias name=".LauncherAlias"> 上。
         * 开关只改这个 alias 的组件启用状态；MainActivity 本体始终 enabled + exported，
         * 所以隐藏图标后本设置页依然可以被显式组件名拉起：
         *     adb shell am start -n io.github.ldxm666.mapadkiller/.MainActivity
         *
         * 状态存在模块 App 自己的 SharedPreferences（不进 LSPosed RemotePreferences）——
         * 这是 App 侧组件可见性，与 hook 侧配置无关，也不需要地图 App 重启。
         */
        const val UI_PREFS = "mak_ui"

        /**
         * v1.1.0 换过键名与组件名（hide_launcher_icon / .LauncherAlias → hide_icon_v2 / .DesktopAlias）。
         *
         * 原因：旧版把桌面入口禁掉之后，LSPosed 管理器解析模块设置入口用的是同一个
         * MAIN + LAUNCHER 查询，于是入口一起消失 —— 用户被锁在设置页外面。
         * 换名之后旧机上残留的「已禁用」组件覆盖记录不再匹配，新组件默认启用，
         * 升级一次图标就自己回来了；再配上磁贴入口，这个坑不会再踩第二次。
         */
        const val K_HIDE_ICON = "hide_icon_v2"
        const val LAUNCHER_ALIAS = "io.github.ldxm666.mapadkiller.DesktopAlias"

        @JvmStatic
        fun hideIcon(c: Context): Boolean {
            try {
                return c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                        .getBoolean(K_HIDE_ICON, false)
            } catch (t: Throwable) {
                return false
            }
        }

        /** 写开关并立即生效；返回 false 表示系统拒绝了组件状态变更。 */
        @JvmStatic
        fun setHideIcon(c: Context, hide: Boolean): Boolean {
            try {
                c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(K_HIDE_ICON, hide).commit()
            } catch (ignored: Throwable) {}
            return applyLauncherState(c)
        }

        /** 按已保存的状态刷新桌面入口（App 每次启动都调一次，防止状态与存储漂移）。 */
        @JvmStatic
        fun applyLauncherState(c: Context): Boolean {
            val hide = hideIcon(c)
            try {
                val pm = c.packageManager
                val cn = android.content.ComponentName(c.packageName, LAUNCHER_ALIAS)
                pm.setComponentEnabledSetting(cn,
                        if (hide) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP)
                return true
            } catch (t: Throwable) {
                return false
            }
        }

        @JvmStatic
        fun attach(a: Application) {
            CTX = a
            if (MainHook.PKG_SELF == a.packageName) {
                try { applyLauncherState(a) } catch (ignored: Throwable) {}
            }
        }

        @JvmStatic
        fun onBind(s: XposedService) {
            service = s
            migrateAmapHomepage(s)
            migrateMapLabels(s)
            flushPending()          // 补写服务没连上时收到的学习结果
            try {
                LearnedProvider.flushToRemote(if (CTX == null) null else CTX!!.applicationContext)
            } catch (ignored: Throwable) {}
        }

        @JvmStatic
        fun onDied(s: XposedService) {
            if (service === s) service = null
        }

        private fun migrateMapLabels(service: XposedService) {
            try {
                val p = service.getRemotePreferences(Config.PREF_GROUP)
                if (p.getInt("_map_label_schema", 0) >= 212) return
                val edit = p.edit()
                for (key in p.all.keys) if (key.startsWith("map_label_")) edit.remove(key)
                edit.putBoolean(Config.K_MAP_POI, false).putInt("_map_label_schema", 212).commit()
            } catch (error: Throwable) {
                android.util.Log.w("MapCleanApp", "label migration", error)
            }
        }

        /** One-time move from overlapping text rules to the requested compact homepage. */
        private fun migrateAmapHomepage(s: XposedService) {
            try {
                val p = s.getRemotePreferences(Config.PREF_GROUP)
                val schema = p.getInt("_amap_protocol_schema", 0)
                if (schema >= 211) return
                val edit = p.edit()
                if (schema < 210) {
                    val previous = org.json.JSONObject()
                    val hidden = arrayOf(Config.K_FEED_WEATHER, Config.K_FEED_BOARD, Config.K_FEED_CONTENT,
                            Config.K_FEED_FESTIVAL, Config.K_HOME_CHIPS)
                    for (key in hidden) previous.put(key, p.getBoolean(key, true))
                    previous.put(Config.K_RIDE_CARD_OFF, p.getBoolean(Config.K_RIDE_CARD_OFF, true))
                    for (key in hidden) edit.putBoolean(key, false)
                    edit.putBoolean(Config.K_RIDE_CARD_OFF, true)
                        .putString("_amap_legacy_home_snapshot", previous.toString())
                }
                // v211: 「设置家 / 设置单位 / 常去地点」 is a core map feature, not part of the
                // recommendation feed, so it is not hidden by default any more. The 210 step above
                // switched it off for everybody, which is what read as "设置家无效"; this restores
                // it only for installs whose own snapshot says it used to be on, so an explicit
                // choice made before that migration is never overwritten.
                var restore = true
                try {
                    restore = org.json.JSONObject(
                            p.getString("_amap_legacy_home_snapshot", "{}"))
                            .optBoolean(Config.K_HOME_CHIPS, true)
                } catch (ignored: Throwable) { }
                if (restore) edit.putBoolean(Config.K_HOME_CHIPS, true)
                edit.putInt("_amap_protocol_schema", 211).commit()
            } catch (t: Throwable) {
                android.util.Log.w("MapCleanApp", "homepage migration", t)
            }
        }
    }
}
