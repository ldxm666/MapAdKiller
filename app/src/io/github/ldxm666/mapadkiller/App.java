package io.github.ldxm666.mapadkiller;

import android.app.Application;
import android.content.Context;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 进程：绑定 LSPosed 服务（用于写 RemotePreferences）。
 * Hook 进程不经过本类（Config 走 XposedModule#getRemotePreferences）。
 */
public final class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static volatile XposedService service;

    public static XposedService svc() { return service; }

    /** App 侧写入；返回 false 表示服务未连接或写入未落盘（UI 提示稍后重试） */
    public static boolean writeBoolean(String key, boolean value) {
        XposedService s = service;
        if (s == null) return false;
        try {
            // 必须用 commit()：apply() 是异步落盘，用户切完开关立刻退出设置页/清后台时
            // 这次写入会被丢掉，表现就是"设完再打开又全变回开启"。
            return s.getRemotePreferences(Config.PREF_GROUP)
                    .edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 恢复默认：清空全部配置键（全部显示） */
    public static boolean clearAll() {
        XposedService s = service;
        if (s == null) return false;
        try {
            return s.getRemotePreferences(Config.PREF_GROUP).edit().clear().commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写入字符串集合（自动学习到的广告 SDK 清单走这条路：App 侧写，hook 侧只读） */
    public static boolean writeStringSet(String key, java.util.Set<String> value) {
        XposedService s = service;
        if (s == null) return false;
        try {
            return s.getRemotePreferences(Config.PREF_GROUP)
                    .edit().putStringSet(key, new java.util.LinkedHashSet<>(value)).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- 服务未绑定时收到上报，先挂这里，绑定后补写 ----
    private static final java.util.Set<String> pendingLearned = new java.util.LinkedHashSet<>();

    public static synchronized void addPendingLearned(java.util.Collection<String> roots) {
        pendingLearned.addAll(roots);
    }

    private static synchronized void flushPending() {
        if (pendingLearned.isEmpty()) return;
        try {
            java.util.Set<String> merged = new java.util.LinkedHashSet<>();
            XposedService s = service;
            if (s != null) {
                java.util.Set<String> old = s.getRemotePreferences(Config.PREF_GROUP)
                        .getStringSet("sdk_learned", null);
                if (old != null) merged.addAll(old);
            }
            merged.addAll(pendingLearned);
            if (s != null && s.getRemotePreferences(Config.PREF_GROUP).edit()
                    .putStringSet("sdk_learned", merged).commit()) {
                pendingLearned.clear();
            }
        } catch (Throwable ignored) {}
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
    public static final String UI_PREFS = "mak_ui";
    /**
     * v1.0.8 换过键名与组件名（hide_launcher_icon / .LauncherAlias → hide_icon_v2 / .DesktopAlias）。
     *
     * 原因：旧版把桌面入口禁掉之后，LSPosed 管理器解析模块设置入口用的是同一个
     * MAIN + LAUNCHER 查询，于是入口一起消失 —— 用户被锁在设置页外面。
     * 换名之后旧机上残留的「已禁用」组件覆盖记录不再匹配，新组件默认启用，
     * 升级一次图标就自己回来了；再配上磁贴入口，这个坑不会再踩第二次。
     */
    public static final String K_HIDE_ICON = "hide_icon_v2";
    public static final String LAUNCHER_ALIAS = "io.github.ldxm666.mapadkiller.DesktopAlias";

    public static boolean hideIcon(Context c) {
        try {
            return c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(K_HIDE_ICON, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写开关并立即生效；返回 false 表示系统拒绝了组件状态变更。 */
    public static boolean setHideIcon(Context c, boolean hide) {
        try {
            c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(K_HIDE_ICON, hide).commit();
        } catch (Throwable ignored) {}
        return applyLauncherState(c);
    }

    /** 按已保存的状态刷新桌面入口（App 每次启动都调一次，防止状态与存储漂移）。 */
    public static boolean applyLauncherState(Context c) {
        boolean hide = hideIcon(c);
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.ComponentName cn =
                    new android.content.ComponentName(c.getPackageName(), LAUNCHER_ALIAS);
            pm.setComponentEnabledSetting(cn,
                    hide ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                         : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        applyLauncherState(this);
        try {
            XposedServiceHelper.registerListener(this);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onServiceBind(XposedService s) {
        service = s;
        flushPending();          // 补写服务没连上时收到的学习结果
        try { LearnedProvider.flushToRemote(getApplicationContext()); } catch (Throwable ignored) {}
    }

    @Override
    public void onServiceDied(XposedService s) {
        if (service == s) service = null;
    }
}
